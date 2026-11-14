#!/bin/sh
# Low-level compilation only; use scripts/check_rust_android_native.sh for verified Gradle inputs.
# With no arguments it builds every ABI; pass one or more cargo target triples to build a subset.
# Needs rustup targets aarch64-linux-android, armv7-linux-androideabi and x86_64-linux-android, and ANDROID_NDK_HOME.
# ARM64 is the default production package; ARMv7 is the fallback; x86_64 is test-only (emulator).
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname -s | tr A-Z a-z)-x86_64/bin"
OUT=${DIPLAY_JNI_LIBS_DIR:-../../../../../.native/rust-android}
TOOLCHAIN="$PWD/cmake/android-toolchain.cmake"
TARGET_DIR=${CARGO_TARGET_DIR:-target}
# Keep build-machine paths (panic locations) out of the checked-in libraries.
export RUSTFLAGS="--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}/registry/src=/crates --remap-path-prefix=$PWD=/diplay-media"
build() { # rust target, clang prefix, ABI directory
    upper=$(echo "$1" | tr a-z- A-Z_)
    env "CC_$(echo "$1" | tr - _)=$BIN/${2}28-clang" "AR_$(echo "$1" | tr - _)=$BIN/llvm-ar" \
        "CFLAGS_$(echo "$1" | tr - _)=${4:-}" \
        "CARGO_TARGET_${upper}_LINKER=$BIN/${2}28-clang" \
        "CMAKE_TOOLCHAIN_FILE=$TOOLCHAIN" "ANDROID_NDK=$ANDROID_NDK_HOME" \
        "OPUS_ANDROID_ABI=$3" "OPUS_ANDROID_PLATFORM=android-28" \
        cargo build --release --locked --lib --target "$1"
    mkdir -p "$OUT/$3"
    cp "$TARGET_DIR/$1/release/libdiplay_media.so" "$OUT/$3/"
}
if [ "$#" -eq 0 ]; then
    set -- aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
fi
for target in "$@"; do
    case "$target" in
        aarch64-linux-android)
            build aarch64-linux-android aarch64-linux-android arm64-v8a ;;
        armv7-linux-androideabi)
            # ring's ARM assembly needs ARMv7 with Thumb-2 spelled out.
            build armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a "-march=armv7-a -mthumb" ;;
        x86_64-linux-android)
            build x86_64-linux-android x86_64-linux-android x86_64 ;;
        *)
            echo "unsupported target: $target" >&2
            exit 2 ;;
    esac
done
