#!/usr/bin/env bash
set -e

CORE_REPO="${1:-opentjcodes/Manta}"
ROOT_DIR="$(pwd)"
JNI_DEST_ABS="$ROOT_DIR/app/src/main/jniLibs"
INCLUDE_DEST_ABS="$ROOT_DIR/app/src/main/cpp/include"

echo "==> Syncing Latest Manta Core Engine from https://github.com/${CORE_REPO} ..."
mkdir -p "$JNI_DEST_ABS" "$INCLUDE_DEST_ABS"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RELEASE_URL="https://github.com/${CORE_REPO}/releases/latest/download/Manta-Core-Engine-SDK.tar.gz"

if curl -fsSL "$RELEASE_URL" -o "$TMP_DIR/Manta-Core-Engine-SDK.tar.gz" 2>/dev/null; then
    tar -xzf "$TMP_DIR/Manta-Core-Engine-SDK.tar.gz" -C "$TMP_DIR" || true
    SO_PATH="$TMP_DIR/android-jniLibs/arm64-v8a/libmanta_core.so"
    if [ -f "$SO_PATH" ]; then
        if grep -a -q "__mantaKernelV25" "$SO_PATH" 2>/dev/null || grep -a -q "MANTADB3" "$SO_PATH" 2>/dev/null; then
            echo "==> Verified prebuilt Manta-Core-Engine-SDK.tar.gz has latest v2.5 (MANTADB3 + Kernel Scripts)!"
            cp -r "$TMP_DIR/android-jniLibs/"* "$JNI_DEST_ABS/"
            if [ -f "$TMP_DIR/include/manta_core.h" ]; then
                cp "$TMP_DIR/include/manta_core.h" "$INCLUDE_DEST_ABS/manta_core.h"
            fi
            ls -lh "$JNI_DEST_ABS/arm64-v8a/libmanta_core.so"
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

# cargo-ndk v4+ requires running inside the directory containing Cargo.toml
pushd "$TMP_DIR/manta-core-src" >/dev/null
cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -o "$JNI_DEST_ABS" \
    build --release
popd >/dev/null

if [ -f "$TMP_DIR/manta-core-src/include/manta_core.h" ]; then
    cp "$TMP_DIR/manta-core-src/include/manta_core.h" "$INCLUDE_DEST_ABS/manta_core.h"
fi

echo "==> Successfully compiled latest Manta Core from source:"
ls -lh "$JNI_DEST_ABS/arm64-v8a/libmanta_core.so"
