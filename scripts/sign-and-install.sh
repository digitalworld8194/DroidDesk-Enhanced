#!/usr/bin/env bash
# Verify, sign with the permanent DroidDesk key and install the daily-driver
# APK (com.orailnoor.droiddesk.preview) with `adb install -r`, keeping its data.
#
#   scripts/sign-and-install.sh --ci [<run-id>]   download the APK built by CI
#                                                 for HEAD (or that run)
#   scripts/sign-and-install.sh <app.apk>         use a local APK
#   --no-install                                  stop after signing/verifying
#
# The keystore lives outside the repository (see SIGNING.md). This script never
# uninstalls anything: if the installed app has a different certificate it
# refuses to install.
set -euo pipefail

PACKAGE=com.orailnoor.droiddesk.preview
ORIGINAL_PACKAGE=com.orailnoor.droiddesk
LABEL=DroidDesk
# SHA-256 of the permanent signing certificate (public, not a secret).
EXPECTED_CERT_SHA256=c5478ae29cb5b7cfccdcfb0de763b0d8eca16c8b90ee204731e7b8d890005ee1
HOOK_PREFIX=/data/user/0/$PACKAGE/files/usr
KEYSTORE_PROPERTIES=${DROIDDESK_KEYSTORE_PROPERTIES:-$HOME/.droiddesk-signing/keystore.properties}

repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
die() { echo "ERROR: $*" >&2; exit 1; }

install=1 source_apk="" ci=0 run_id=""
while (($#)); do
  case $1 in
    --no-install) install=0 ;;
    --ci) ci=1; if [[ ${2:-} =~ ^[0-9]+$ ]]; then run_id=$2; shift; fi ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) source_apk=$1 ;;
  esac
  shift
done

if ((ci)); then
  sha=$(git -C "$repo" rev-parse HEAD)
  slug=$(git -C "$repo" remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')
  if [[ -z $run_id ]]; then
    run_id=$(gh run list --repo "$slug" --commit "$sha" \
      --workflow build.yml --status success --limit 1 --json databaseId --jq '.[0].databaseId')
    [[ -n $run_id ]] || die "no successful CI run for $sha yet (gh run watch)"
  fi
  TMPDIR="$work" gh run download "$run_id" --repo "$slug" \
    --pattern 'DroidDesk-Preview-release-*' --dir "$work/ci"
  source_apk=$(find "$work/ci" -name 'DroidDesk-Preview-release.apk' | head -1)
fi
[[ -f $source_apk ]] || die "APK not found: ${source_apk:-<none>} (pass a path or --ci)"
[[ -f $KEYSTORE_PROPERTIES ]] || die "missing $KEYSTORE_PROPERTIES (see SIGNING.md)"

echo "== Verifying $source_apk"
python3 "$repo/scripts/apk_manifest.py" "$source_apk" > "$work/manifest.json"
python3 - "$work/manifest.json" "$PACKAGE" "$LABEL" <<'EOF'
import json, sys
m, package, label = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
print(f"package={m['package']} label={m['label']} version={m['versionName']} ({m['versionCode']}) "
      f"home={m['declaresHome']} launcher={m['declaresLauncher']}")
assert m["package"] == package, f"package is {m['package']}, expected {package}"
assert m["label"] == label, f"label is {m['label']}, expected {label}"
assert m["declaresHome"], "APK does not declare MAIN/HOME/DEFAULT"
assert m["declaresLauncher"], "APK has no MAIN/LAUNCHER entry"
EOF
unzip -l "$source_apk" | grep -q ' lib/arm64-v8a/' || die "no arm64-v8a libraries"
unzip -p "$source_apk" lib/arm64-v8a/libsocket_hook_variant.so 2>/dev/null | strings | grep -qx "$HOOK_PREFIX" \
  || die "libsocket_hook_variant.so missing or not built for $HOOK_PREFIX"

echo "== Signing with the permanent key"
prop() { grep "^$1=" "$KEYSTORE_PROPERTIES" | cut -d= -f2-; }
out="$repo/dist/DroidDesk-signed.apk"
mkdir -p "$repo/dist"
DD_STORE_PASS=$(prop storePassword) DD_KEY_PASS=$(prop keyPassword) apksigner sign \
  --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
  --ks-pass env:DD_STORE_PASS --key-pass env:DD_KEY_PASS \
  --out "$out" "$source_apk"
cert_sha() { apksigner verify --print-certs "$1" | grep -m1 -oE 'certificate SHA-256 digest: [0-9a-f]{64}' | awk '{print $NF}'; }
apksigner verify "$out" || die "signature verification failed"
signed_sha=$(cert_sha "$out")
[[ $signed_sha == "$EXPECTED_CERT_SHA256" ]] || die "signed with unexpected certificate $signed_sha"
echo "certificate SHA-256: $signed_sha"
echo "APK: $out"
echo "APK SHA-256: $(sha256sum "$out" | cut -d' ' -f1)"
((install)) || exit 0

echo "== Installing $PACKAGE"
adb get-state > /dev/null || die "adb not connected (reconnect wireless debugging)"
installed_path=$(adb shell pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p' | head -1)
if [[ -n $installed_path ]]; then
  adb pull "$installed_path" "$work/installed.apk" > /dev/null
  installed_sha=$(cert_sha "$work/installed.apk")
  [[ $installed_sha == "$signed_sha" ]] || die "installed $PACKAGE is signed with $installed_sha, \
not the permanent key; refusing to install (adb install -r would fail and data must not be lost)"
fi
adb install -r "$out"
adb shell pm path "$ORIGINAL_PACKAGE" > /dev/null || echo "WARNING: $ORIGINAL_PACKAGE is not installed" >&2
adb shell pm path "$PACKAGE"
