#!/bin/bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
URL_BASE="https://packages-cf.termux.dev/apt/termux-main/pool/main"
PACKAGES=(
    "w/wlroots/wlroots_0.17.4-1_aarch64.deb"
    "w/wayland/wayland_1.22.0-1_aarch64.deb"
    "l/libxkbcommon/libxkbcommon_1.7.0-1_aarch64.deb"
    "p/pixman/pixman_0.43.4-1_aarch64.deb"
    "l/libdrm/libdrm_2.4.120-1_aarch64.deb"
    "l/libffi/libffi_3.4.6-1_aarch64.deb"
)
JNILIBS_DIR="$REPO_ROOT/app/android/app/src/main/jniLibs/arm64-v8a"
INCLUDE_DIR="$REPO_ROOT/app/android/app/src/main/cpp/include"
WORK_DIR="${TMPDIR:-/tmp}/wlroots_deps"
mkdir -p "$JNILIBS_DIR" "$INCLUDE_DIR" "$WORK_DIR"
cd "$WORK_DIR"
for pkg in "${PACKAGES[@]}"; do
    f="$(basename "$pkg")"
    echo "Downloading $f..."
    curl -fsSL "$URL_BASE/$pkg" -o "$f"
    rm -rf data control.tar.* data.tar.* debian-binary
    if command -v bsdtar >/dev/null 2>&1; then bsdtar -xf "$f"; else ar -x "$f"; fi
    data_archive="$(ls data.tar.* 2>/dev/null | head -1)"
    [ -n "$data_archive" ] || { echo "no data.tar in $f" >&2; exit 1; }
    tar -xf "$data_archive"
    if [ -d ./data/data/com.termux/files/usr/lib ]; then
        find ./data/data/com.termux/files/usr/lib -name '*.so*' -type f -exec cp -f {} "$JNILIBS_DIR/" \;
        find ./data/data/com.termux/files/usr/lib -name '*.so*' -type l -exec cp -a {} "$JNILIBS_DIR/" \;
    fi
    if [ -d ./data/data/com.termux/files/usr/include ]; then
        cp -r ./data/data/com.termux/files/usr/include/* "$INCLUDE_DIR/"
    fi
    rm -rf data control.tar.* data.tar.* debian-binary
done
echo "Done: deps staged."
