package com.shilapi.xcertplay.airplay

/** Screen wire conversions share the receiver's Rust implementation. */
object ScreenCodec {
    fun senderNanos(header: ByteArray): Long = NativeScreenCodec.senderNanos(header)

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray, bodySize: Int = body.size): ByteArray =
        AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header, length = bodySize)

    /** Mutates valid complete NAL chains; malformed payloads retain their bytes and identity. */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        NativeScreenCodec.annexB(payload)
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        val plan = NativeScreenCodec.config(payload)
        val data = if (plan[1] == 0) payload else payload.copyOfRange(plan[1], payload.size)
        return VideoCodec.entries[plan[0]] to data
    }

    const val TAG_SIZE = 16
}

internal object NativeScreenCodec {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun senderNanos(header: ByteArray): Long
    @JvmStatic external fun annexB(payload: ByteArray)
    @JvmStatic external fun config(payload: ByteArray): IntArray
    @JvmStatic external fun avcParameterSets(payload: ByteArray): Array<ByteArray>
    @JvmStatic external fun hevcCsd(payload: ByteArray): ByteArray
    @JvmStatic external fun annexBCopy(payload: ByteArray): ByteArray
    @JvmStatic external fun randomAccess(payload: ByteArray, hevc: Boolean): Boolean
}
