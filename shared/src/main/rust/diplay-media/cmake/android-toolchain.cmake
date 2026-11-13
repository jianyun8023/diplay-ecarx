# Selects the Android ABI for the vendored libopus CMake build inside libopus_sys.
# build.sh sets OPUS_ANDROID_ABI / OPUS_ANDROID_PLATFORM / ANDROID_NDK for each target.
set(ANDROID_ABI "$ENV{OPUS_ANDROID_ABI}" CACHE STRING "Android ABI" FORCE)
set(ANDROID_PLATFORM "$ENV{OPUS_ANDROID_PLATFORM}" CACHE STRING "Android platform" FORCE)
set(ANDROID_NDK "$ENV{ANDROID_NDK}" CACHE PATH "Android NDK" FORCE)
include("$ENV{ANDROID_NDK}/build/cmake/android.toolchain.cmake")
