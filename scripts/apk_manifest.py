#!/usr/bin/env python3
"""Dump the facts we verify before installing a DroidDesk APK.

Parses the binary AndroidManifest.xml directly (no aapt2 needed, works in
Termux/proot) and prints JSON with package, version, application label and
the activities' intent filters.

Usage: apk_manifest.py <app.apk>
"""
import json
import struct
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12
# Attribute resource ids, used when aapt2 strips attribute names.
ATTR_IDS = {0x01010003: "name", 0x01010001: "label", 0x0101021B: "versionCode", 0x0101021C: "versionName"}


def _strings(data, off):
    _, header_size, _ = struct.unpack_from("<HHI", data, off)
    count, _, flags, strings_start, _ = struct.unpack_from("<IIIII", data, off + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % count, data, off + header_size)
    base = off + strings_start
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            n = data[p]; p += 2 if n & 0x80 else 1  # utf-16 length, skipped
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]; p += 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]; p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out


def parse(apk):
    data = zipfile.ZipFile(apk).read("AndroidManifest.xml")
    strings, res_ids, events = [], [], []
    off = struct.unpack_from("<H", data, 2)[0]
    while off < len(data):
        ctype, hsize, csize = struct.unpack_from("<HHI", data, off)
        if ctype == RES_STRING_POOL:
            strings = _strings(data, off)
        elif ctype == 0x0180:  # resource map
            res_ids = list(struct.unpack_from("<%dI" % ((csize - hsize) // 4), data, off + hsize))
        elif ctype == RES_XML_START_ELEMENT:
            p = off + hsize
            _, name_i, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, p)
            attrs = {}
            for i in range(attr_count):
                a = p + attr_start + i * attr_size
                _, an, raw, _, _, dtype, dval = struct.unpack_from("<IIIHBBI", data, a)
                key = strings[an] if an < len(strings) and strings[an] else ""
                if not key and an < len(res_ids):
                    key = ATTR_IDS.get(res_ids[an], hex(res_ids[an]))
                if raw != 0xFFFFFFFF:
                    val = strings[raw]
                elif dtype == TYPE_STRING:
                    val = strings[dval]
                elif dtype == TYPE_INT_BOOLEAN:
                    val = dval != 0
                elif dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
                    val = dval
                else:
                    val = "@0x%08x" % dval
                attrs[key] = val
            events.append(("start", strings[name_i], attrs))
        elif ctype == RES_XML_END_ELEMENT:
            events.append(("end", strings[struct.unpack_from("<II", data, off + hsize)[1]], None))
        off += csize
    return events


def summarize(apk):
    result = {"activities": []}
    activity = intent_filter = None
    for kind, tag, attrs in parse(apk):
        if kind == "start":
            if tag == "manifest":
                result.update(package=attrs.get("package"), versionCode=attrs.get("versionCode"),
                              versionName=attrs.get("versionName"))
            elif tag == "application":
                result["label"] = attrs.get("label")
            elif tag in ("activity", "activity-alias"):
                activity = {"name": attrs.get("name"), "intentFilters": []}
                result["activities"].append(activity)
            elif tag == "intent-filter" and activity is not None:
                intent_filter = {"actions": [], "categories": []}
                activity["intentFilters"].append(intent_filter)
            elif tag in ("action", "category") and intent_filter is not None:
                intent_filter["actions" if tag == "action" else "categories"].append(attrs.get("name"))
        elif tag in ("activity", "activity-alias"):
            activity = None
        elif tag == "intent-filter":
            intent_filter = None
    filters = [f for a in result["activities"] for f in a["intentFilters"]]
    result["declaresHome"] = any(
        "android.intent.action.MAIN" in f["actions"]
        and {"android.intent.category.HOME", "android.intent.category.DEFAULT"} <= set(f["categories"])
        for f in filters)
    result["declaresLauncher"] = any(
        "android.intent.action.MAIN" in f["actions"] and "android.intent.category.LAUNCHER" in f["categories"]
        for f in filters)
    return result


if __name__ == "__main__":
    print(json.dumps(summarize(sys.argv[1]), indent=2))
