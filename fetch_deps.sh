#!/usr/bin/env bash
# Stage wlroots + friends (aarch64) from the official Termux apt repositories
# into the Android project (jniLibs/arm64-v8a and cpp/include).
#
# Package versions are NOT hardcoded: they are resolved from the repositories'
# Packages indexes at run time, and every .deb is verified against the SHA256
# published in that index before extraction. The resolved set is printed and
# written to $WORK_DIR/resolved-deps.txt.
#
# Note: compositor_jni.cpp does not include or link any of these libraries yet
# (CMakeLists.txt only links log/android), so no specific wlroots API version
# is required. If code starts using wlroots, pin WLROOTS_SERIES accordingly.
#
# Environment overrides:
#   TERMUX_MIRROR    apt mirror base (default: https://packages-cf.termux.dev/apt)
#   JNILIBS_DIR      output dir for .so files (default: repo jniLibs/arm64-v8a)
#   INCLUDE_DIR      output dir for headers   (default: repo cpp/include)
#   WORK_DIR         scratch dir              (default: mktemp -d)
#   WLROOTS_SERIES   require this wlroots series, e.g. "0.18" (default: any)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERMUX_MIRROR="${TERMUX_MIRROR:-https://packages-cf.termux.dev/apt}"
ARCH="aarch64"
JNILIBS_DIR="${JNILIBS_DIR:-$REPO_ROOT/app/android/app/src/main/jniLibs/arm64-v8a}"
INCLUDE_DIR="${INCLUDE_DIR:-$REPO_ROOT/app/android/app/src/main/cpp/include}"
WLROOTS_SERIES="${WLROOTS_SERIES:-}"

# repo|dist|package|expected lib glob|expected header (relative to usr/include)
# termux-main  -> dists/stable/main ; termux-x11 -> dists/x11/main
DEPS=(
    "termux-x11|x11|wlroots|libwlroots*.so|wlroots-*/wlr/backend.h"
    "termux-main|stable|libwayland|libwayland-server.so|wayland-server.h"
    "termux-x11|x11|libxkbcommon|libxkbcommon.so|xkbcommon/xkbcommon.h"
    "termux-main|stable|libpixman|libpixman-1.so|pixman-1/pixman.h"
    "termux-main|stable|libdrm|libdrm.so|xf86drm.h"
    "termux-main|stable|libffi|libffi.so|ffi.h"
)

die() { echo "fetch_deps: ERROR: $*" >&2; exit 1; }

for tool in curl ar tar sha256sum awk find; do
    command -v "$tool" >/dev/null 2>&1 || die "required tool '$tool' not found"
done

if [ -z "${WORK_DIR:-}" ]; then
    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/droiddesk_deps.XXXXXX")"
    trap 'rm -rf "$WORK_DIR"' EXIT
fi
mkdir -p "$JNILIBS_DIR" "$INCLUDE_DIR" "$WORK_DIR"
MANIFEST="$WORK_DIR/resolved-deps.txt"
: > "$MANIFEST"

fetch() { # url dest
    curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$2" "$1" \
        || die "download failed (HTTP error or network): $1"
}

# Print "Version|Filename|SHA256" of a package from a Packages index.
lookup() { # index package
    awk -v want="$2" '
        BEGIN { RS = ""; FS = "\n" }
        {
            n = v = f = s = ""
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^Package: /)  n = substr($i, 10)
                if ($i ~ /^Version: /)  v = substr($i, 10)
                if ($i ~ /^Filename: /) f = substr($i, 11)
                if ($i ~ /^SHA256: /)   s = substr($i, 9)
            }
            if (n == want) { print v "|" f "|" s; exit }
        }' "$1"
}

extract_data() { # data-archive destdir
    case "$1" in
        *.tar.zst) command -v zstd >/dev/null 2>&1 || die "zstd needed for $1"
                   zstd -dc "$1" | tar -x -C "$2" ;;
        *.tar.xz|*.tar.gz|*.tar.bz2|*.tar.lzma|*.tar) tar -xf "$1" -C "$2" ;;
        *) die "unsupported data archive: $1" ;;
    esac
}

declare -A INDEX_DONE=()
for entry in "${DEPS[@]}"; do
    IFS='|' read -r repo dist pkg lib_glob header_glob <<< "$entry"
    index="$WORK_DIR/Packages-$repo"
    if [ -z "${INDEX_DONE[$repo]:-}" ]; then
        url="$TERMUX_MIRROR/$repo/dists/$dist/main/binary-$ARCH/Packages"
        echo "Fetching index $url"
        fetch "$url" "$index"
        grep -q '^Package: ' "$index" || die "not a valid Packages index: $url"
        INDEX_DONE[$repo]=1
    fi

    meta="$(lookup "$index" "$pkg")"
    [ -n "$meta" ] || die "package '$pkg' not found in $repo ($dist/main/$ARCH)"
    IFS='|' read -r version filename sha256 <<< "$meta"
    [ -n "$filename" ] && [ -n "$sha256" ] || die "incomplete metadata for $pkg in $repo"

    if [ "$pkg" = wlroots ] && [ -n "$WLROOTS_SERIES" ]; then
        case "$version" in
            "$WLROOTS_SERIES".*) ;;
            *) die "wlroots $version in $repo does not match required series $WLROOTS_SERIES" ;;
        esac
    fi

    pkg_dir="$WORK_DIR/pkg-$pkg"
    rm -rf "$pkg_dir"
    mkdir -p "$pkg_dir/deb" "$pkg_dir/root"
    deb="$pkg_dir/$(basename "$filename")"
    echo "Downloading $pkg $version ($repo)"
    fetch "$TERMUX_MIRROR/$repo/$filename" "$deb"

    [ "$(head -c 8 "$deb")" = '!<arch>' ] || die "$deb is not an ar archive (.deb)"
    echo "$sha256  $deb" | sha256sum -c --quiet - || die "SHA256 mismatch for $deb"

    (cd "$pkg_dir/deb" && ar x "$deb") || die "ar failed on $deb"
    [ -f "$pkg_dir/deb/debian-binary" ] || die "$deb has no debian-binary member"
    data_archive="$(find "$pkg_dir/deb" -maxdepth 1 -name 'data.tar*' | head -1)"
    [ -n "$data_archive" ] || die "no data.tar* member in $deb"
    extract_data "$data_archive" "$pkg_dir/root"

    usr="$pkg_dir/root/data/data/com.termux/files/usr"
    [ -d "$usr/lib" ] || die "$pkg: no usr/lib in package"
    compgen -G "$usr/lib/$lib_glob" >/dev/null || die "$pkg: expected $lib_glob not found"
    compgen -G "$usr/include/$header_glob" >/dev/null || die "$pkg: expected header $header_glob not found"

    find "$usr/lib" -maxdepth 1 -name '*.so*' \( -type f -o -type l \) \
        -exec cp -a {} "$JNILIBS_DIR/" \;
    cp -a "$usr/include/." "$INCLUDE_DIR/"

    echo "$repo $pkg $version $filename sha256=$sha256" >> "$MANIFEST"
done

echo "Resolved dependencies:"
cat "$MANIFEST"
echo "Staged libs into $JNILIBS_DIR"
echo "Staged headers into $INCLUDE_DIR"
