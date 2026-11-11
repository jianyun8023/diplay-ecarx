#!/bin/sh
# Builds libdiplay_chacha.so into shared/src/main/jniLibs for the two shipped ABIs.
# Needs rustup targets aarch64-linux-android and armv7-linux-androideabi, and ANDROID_NDK_HOME.
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname -s | tr A-Z a-z)-x86_64/bin"
OUT=../../jniLibs
# Keep build-machine paths (panic locations) out of the checked-in libraries.
export RUSTFLAGS="--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}/registry/src=/crates --remap-path-prefix=$PWD=/diplay-chacha"
build() { # rust target, clang prefix, ABI directory
    upper=$(echo "$1" | tr a-z- A-Z_)
    env "CC_$(echo "$1" | tr - _)=$BIN/${2}28-clang" "AR_$(echo "$1" | tr - _)=$BIN/llvm-ar" \
        "CFLAGS_$(echo "$1" | tr - _)=${4:-}" \
        "CARGO_TARGET_${upper}_LINKER=$BIN/${2}28-clang" \
        cargo build --release --locked --target "$1"
    mkdir -p "$OUT/$3"
    cp "target/$1/release/libdiplay_chacha.so" "$OUT/$3/"
}
build aarch64-linux-android aarch64-linux-android arm64-v8a
# ring's ARM assembly needs ARMv7 with Thumb-2 spelled out.
build armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a "-march=armv7-a -mthumb"
