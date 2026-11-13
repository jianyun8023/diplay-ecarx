# Selects the Android ABI for the vendored libopus CMake build inside libopus_sys.
# build.sh sets OPUS_ANDROID_ABI / OPUS_ANDROID_PLATFORM / ANDROID_NDK for each target.
set(ANDROID_ABI "$ENV{OPUS_ANDROID_ABI}" CACHE STRING "Android ABI" FORCE)
set(ANDROID_PLATFORM "$ENV{OPUS_ANDROID_PLATFORM}" CACHE STRING "Android platform" FORCE)
set(ANDROID_NDK "$ENV{ANDROID_NDK}" CACHE PATH "Android NDK" FORCE)
# libopus is built as a static library only, so the CMake compiler probe must not try to link an
# executable (that needs the API-level crt objects; the Android toolchain's default probe target can
# miss them on some hosts). Probe with a static library instead.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
include("$ENV{ANDROID_NDK}/build/cmake/android.toolchain.cmake")
