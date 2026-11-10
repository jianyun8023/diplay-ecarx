#include <jni.h>
#include <stdint.h>
#include <opus.h>

enum { FRAME_SAMPLES = 960, FRAME_BYTES = FRAME_SAMPLES * 2, MAX_PACKET_BYTES = 1275 };

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeCreate(JNIEnv *env, jclass type, jint bitrate) {
    if (bitrate <= 0) return 0;
    int error;
    OpusEncoder *encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder) return 0;
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5)) != OPUS_OK ||
        opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeEncode(
        JNIEnv *env, jclass type, jlong handle, jbyteArray input, jbyteArray output) {
    if (!handle || (*env)->GetArrayLength(env, input) != FRAME_BYTES ||
        (*env)->GetArrayLength(env, output) < MAX_PACKET_BYTES) return OPUS_BAD_ARG;
    unsigned char bytes[FRAME_BYTES], packet[MAX_PACKET_BYTES];
    opus_int16 pcm[FRAME_SAMPLES];
    (*env)->GetByteArrayRegion(env, input, 0, FRAME_BYTES, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) return OPUS_INTERNAL_ERROR;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
        pcm[i] = (opus_int16)(bytes[2 * i] | ((unsigned)bytes[2 * i + 1] << 8));
    }
    OpusEncoder *encoder = (OpusEncoder *)(intptr_t)handle;
    int length = opus_encode(encoder, pcm, FRAME_SAMPLES, packet, MAX_PACKET_BYTES);
    if (length < 0) {
        opus_encoder_ctl(encoder, OPUS_RESET_STATE);
        return length;
    }
    (*env)->SetByteArrayRegion(env, output, 0, length, (jbyte *)packet);
    return length;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeDestroy(JNIEnv *env, jclass type, jlong handle) {
    opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}
