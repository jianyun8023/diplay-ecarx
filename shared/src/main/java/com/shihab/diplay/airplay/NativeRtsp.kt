package com.shihab.diplay.airplay

/** The native framing codec uses the existing public DTOs and shared library. */
internal object NativeRtsp {
    @JvmStatic external fun inputCreate(): Long
    @JvmStatic external fun inputDestroy(handle: Long)
    @JvmStatic external fun inputEncryptedBytes(handle: Long): Int
    @JvmStatic external fun inputFeed(handle: Long, buffer: ByteArray, count: Int, cipher: Long): RtspMessage.Parsed
    @JvmStatic external fun parse(buffer: ByteArray): RtspMessage.Parsed
    @JvmStatic external fun response(protocol: String, status: Int, reason: String?, cseq: String?,
        headers: Array<String>, body: ByteArray): ByteArray
}
