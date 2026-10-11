LOCAL_PATH := $(call my-dir)
JNI_ROOT := $(LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex_echo
LOCAL_SRC_FILES := speex_echo_jni.c \
    speexdsp/mdf.c speexdsp/preprocess.c speexdsp/fftwrap.c \
    speexdsp/kiss_fft.c speexdsp/kiss_fftr.c speexdsp/filterbank.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexdsp $(LOCAL_PATH)/speexdsp/include
LOCAL_CFLAGS := -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= -Wno-unused-parameter
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

# libopus (fixed-point) built as a static library, then linked into the Opus encoder JNI shim.
# The Android MediaCodec Opus *encoder* only exists on API 30+, so 820A/Android 9 must encode the
# CarPlay telephony uplink in-process.
include $(LOCAL_PATH)/opus/Android.opus.mk

# Android.opus.mk re-points LOCAL_PATH to opus/. Restore it from the saved JNI_ROOT
# (my-dir is derived from MAKEFILE_LIST and can no longer resolve to this dir here).
include $(CLEAR_VARS)
LOCAL_PATH := $(JNI_ROOT)
LOCAL_MODULE := opus_encoder
LOCAL_SRC_FILES := opus_encoder_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/opus/include
LOCAL_CFLAGS := -O2 -Wno-unused-parameter
LOCAL_STATIC_LIBRARIES := opus
include $(BUILD_SHARED_LIBRARY)
