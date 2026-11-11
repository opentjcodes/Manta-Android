#!/usr/bin/env bash
set -e

CORE_REPO="${1:-opentjcodes/Manta}"
JNI_DEST="app/src/main/jniLibs"
INCLUDE_DEST="app/src/main/cpp/include"

echo "==> Syncing Latest Manta Core Engine from https://github.com/${CORE_REPO} ..."
mkdir -p "$JNI_DEST" "$INCLUDE_DEST"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RELEASE_URL="https://github.com/${CORE_REPO}/releases/latest/download/Manta-Core-Engine-SDK.tar.gz"

USE_PREBUILT=0
if curl -fsSL "$RELEASE_URL" -o "$TMP_DIR/Manta-Core-Engine-SDK.tar.gz" 2>/dev/null; then
    tar -xzf "$TMP_DIR/Manta-Core-Engine-SDK.tar.gz" -C "$TMP_DIR" || true
    SO_PATH="$TMP_DIR/android-jniLibs/arm64-v8a/libmanta_core.so"
    if [ -f "$SO_PATH" ]; then
        # Verify the prebuilt binary contains the latest Manta Core v2.5 transactional storage & kernel symbols
        if strings "$SO_PATH" 2>/dev/null | grep -q "MANTADB3"; then
            echo "==> Verified prebuilt Manta-Core-Engine-SDK.tar.gz has latest v2.5 (MANTADB3 + Kernel Scripts)!"
            cp -r "$TMP_DIR/android-jniLibs/"* "$JNI_DEST/"
            if [ -f "$TMP_DIR/include/manta_core.h" ]; then
                cp "$TMP_DIR/include/manta_core.h" "$INCLUDE_DEST/manta_core.h"
            fi
            ls -lh "$JNI_DEST/arm64-v8a/libmanta_core.so"
            exit 0
        else
            echo "==> Prebuilt release is older than Manta Core v2.5; compiling latest main branch from source..."
        fi
    fi
fi

echo "==> Cloning & compiling latest Manta Core v2.5 from https://github.com/${CORE_REPO}.git ..."
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

echo "==> Successfully compiled latest Manta Core from source:"
ls -lh "$JNI_DEST/arm64-v8a/libmanta_core.so"
