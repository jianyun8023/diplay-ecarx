#!/bin/sh
# Builds libdiplay_media.so into shared/src/main/jniLibs (or DIPLAY_JNI_LIBS_DIR) for the shipped ABIs.
# With no arguments it builds both ABIs; pass one or more cargo target triples to build a subset.
# Needs rustup targets aarch64-linux-android and armv7-linux-androideabi, and ANDROID_NDK_HOME.
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname -s | tr A-Z a-z)-x86_64/bin"
OUT=${DIPLAY_JNI_LIBS_DIR:-../../jniLibs}
TARGET_DIR=${CARGO_TARGET_DIR:-target}
# Keep build-machine paths (panic locations) out of the checked-in libraries.
export RUSTFLAGS="--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}/registry/src=/crates --remap-path-prefix=$PWD=/diplay-media"
build() { # rust target, clang prefix, ABI directory
    upper=$(echo "$1" | tr a-z- A-Z_)
    env "CC_$(echo "$1" | tr - _)=$BIN/${2}28-clang" "AR_$(echo "$1" | tr - _)=$BIN/llvm-ar" \
        "CFLAGS_$(echo "$1" | tr - _)=${4:-}" \
        "CARGO_TARGET_${upper}_LINKER=$BIN/${2}28-clang" \
        cargo build --release --locked --lib --target "$1"
    mkdir -p "$OUT/$3"
    cp "$TARGET_DIR/$1/release/libdiplay_media.so" "$OUT/$3/"
}
if [ "$#" -eq 0 ]; then
    set -- aarch64-linux-android armv7-linux-androideabi
fi
for target in "$@"; do
    case "$target" in
        aarch64-linux-android)
            build aarch64-linux-android aarch64-linux-android arm64-v8a ;;
        armv7-linux-androideabi)
            # ring's ARM assembly needs ARMv7 with Thumb-2 spelled out.
            build armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a "-march=armv7-a -mthumb" ;;
        *)
            echo "unsupported target: $target" >&2
            exit 2 ;;
    esac
done
