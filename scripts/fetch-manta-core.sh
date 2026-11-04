#!/usr/bin/env bash
set -e

CORE_REPO="${1:-opentjcodes/Manta}"
JNI_DEST="app/src/main/jniLibs"
INCLUDE_DEST="app/src/main/cpp/include"

echo "==> Syncing Manta Core Engine from https://github.com/${CORE_REPO} ..."
mkdir -p "$JNI_DEST" "$INCLUDE_DEST"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RELEASE_URL="https://github.com/${CORE_REPO}/releases/latest/download/Manta-Core-Engine-SDK.tar.gz"

if curl -fsSL "$RELEASE_URL" -o "$TMP_DIR/Manta-Core-Engine-SDK.tar.gz"; then
    echo "==> Downloaded prebuilt Manta-Core-Engine-SDK.tar.gz from latest release!"
    tar -xzf "$TMP_DIR/Manta-Core-Engine-SDK.tar.gz" -C "$TMP_DIR"
    cp -r "$TMP_DIR/android-jniLibs/"* "$JNI_DEST/"
    if [ -f "$TMP_DIR/include/manta_core.h" ]; then
        cp "$TMP_DIR/include/manta_core.h" "$INCLUDE_DEST/manta_core.h"
    fi
    echo "==> Installed Android JNI libraries:"
    ls -lh "$JNI_DEST/arm64-v8a/libmanta_core.so"
    exit 0
fi

echo "==> Prebuilt release archive not found yet; building Manta Core from source (${CORE_REPO})..."
git clone --depth 1 "https://github.com/${CORE_REPO}.git" "$TMP_DIR/manta-core-src"

if ! command -v cargo-ndk &>/dev/null; then
    cargo install cargo-ndk --locked
fi

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

if [ -z "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_VER=$(ls -1 "$ANDROID_HOME/ndk" | sort -V | tail -n 1)
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VER"
fi

cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -o "$JNI_DEST" \
    build --release --manifest-path "$TMP_DIR/manta-core-src/Cargo.toml"

if [ -f "$TMP_DIR/manta-core-src/include/manta_core.h" ]; then
    cp "$TMP_DIR/manta-core-src/include/manta_core.h" "$INCLUDE_DEST/manta_core.h"
fi

echo "==> Successfully compiled Manta Core from source:"
ls -lh "$JNI_DEST/arm64-v8a/libmanta_core.so"
