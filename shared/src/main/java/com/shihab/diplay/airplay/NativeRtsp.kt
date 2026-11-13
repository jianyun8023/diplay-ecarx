package com.shihab.diplay.airplay

/** The native framing codec uses the existing public DTOs and shared library. */
internal object NativeRtsp {
    @JvmStatic external fun parse(buffer: ByteArray): RtspMessage.Parsed
    @JvmStatic external fun response(protocol: String, status: Int, reason: String?, cseq: String?,
        headers: Array<String>, body: ByteArray): ByteArray
}
