#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "opus.h"

/* The Opus encoder is unavailable in the Android MediaCodec on API < 30 (the software encoder
 * c2.android.opus.encoder ships only from Android 11). CarPlay's telephony uplink negotiates Opus,
 * so 820A / Android 9 head units fail at MediaCodec.createEncoderByType and the whole uplink goes
 * silent. This JNI shim wraps libopus so the uplink encodes in-process. 48 kHz mono, 20 ms frames. */

typedef struct {
    OpusEncoder *enc;
} Encoder;

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_OpusNativeEncoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels, jint bitrate, jboolean voice) {
    (void) env;
    (void) clazz;
    if (sampleRate <= 0 || channels <= 0 || bitrate <= 0) return 0;
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(
        sampleRate,
        channels,
        voice ? OPUS_APPLICATION_VOIP : OPUS_APPLICATION_AUDIO,
        &err);
    if (enc == NULL || err != OPUS_OK) {
        if (enc != NULL) opus_encoder_destroy(enc);
        return 0;
    }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    // DTX keeps silence cheap; VBR lets the codec drop rate for quiet frames.
    opus_encoder_ctl(enc, OPUS_SET_DTX(1));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    Encoder *e = calloc(1, sizeof(Encoder));
    if (e == NULL) {
        opus_encoder_destroy(enc);
        return 0;
    }
    e->enc = enc;
    return (jlong) (intptr_t) e;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_OpusNativeEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray pcm, jint frameSize, jint maxBytes) {
    (void) clazz;
    Encoder *e = (Encoder *) (intptr_t) handle;
    if (e == NULL || e->enc == NULL || pcm == NULL || frameSize <= 0 || maxBytes <= 0) return NULL;
    jsize samples = (*env)->GetArrayLength(env, pcm);
    if (samples < frameSize) return NULL;
    jshort *buf = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (buf == NULL) return NULL;
    unsigned char *out = malloc((size_t) maxBytes);
    if (out == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
        return NULL;
    }
    opus_int32 n = opus_encode(e->enc, buf, frameSize, out, maxBytes);
    (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
    if (n < 0) {
        free(out);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, n);
    if (result == NULL) {
        free(out);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, n, (const jbyte *) out);
    free(out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_OpusNativeEncoder_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    Encoder *e = (Encoder *) (intptr_t) handle;
    if (e == NULL) return;
    if (e->enc != NULL) opus_encoder_destroy(e->enc);
    free(e);
}
