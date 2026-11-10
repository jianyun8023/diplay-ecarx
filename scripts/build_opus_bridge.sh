#!/usr/bin/env bash
# Maintenance only: APK builds consume the checked-in libraries, without running the NDK.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}
toolchains=("$ndk"/toolchains/llvm/prebuilt/*)
bin=${toolchains[0]}/bin
jni=$root/shared/src/main/jni
for abi in arm64-v8a armeabi-v7a; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android ;;
        armeabi-v7a) target=armv7a-linux-androideabi ;;
    esac
    libs=$root/shared/src/main/jniLibs/$abi
    "$bin/${target}24-clang" -O2 -fPIC -shared -Wall -Wextra -Werror -Wno-unused-parameter \
        -Wl,--no-undefined,-z,max-page-size=16384,-soname,libopus_encoder.so \
        -I"$jni/opus/include" "$jni/opus_encoder_jni.c" -L"$libs" -lopus \
        -o "$libs/libopus_encoder.so"
    "$bin/llvm-strip" --strip-unneeded "$libs/libopus_encoder.so"
done
