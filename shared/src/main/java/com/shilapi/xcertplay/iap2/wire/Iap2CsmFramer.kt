package com.shilapi.xcertplay.iap2.wire

import com.shilapi.xcertplay.airplay.NativeMedia

/** One bounded Rust receive owner; the adapter serializes parsing against close. */
class Iap2CsmFramer : AutoCloseable {
    private var handle = NativeIap2Csm.create().also { check(it != 0L) }

    @Synchronized
    fun offer(chunk: ByteArray): List<Iap2Frame> {
        check(handle != 0L) { "CSM framer is closed" }
        return NativeIap2Csm.offer(handle, chunk).toList()
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeIap2Csm.destroy(handle)
        handle = 0L
    }

    companion object {
        const val START = 0x4040
        const val HEADER_BYTES = 6
        const val MIN_FRAME_BYTES = HEADER_BYTES
        const val MAX_FRAME_BYTES = 0xffff
        const val MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES
        const val MAX_PARAM_BYTES = 0xffff
        const val MAX_LINK_CHUNK_BYTES = 65_525

        fun encodeFrame(messageId: Int, payload: ByteArray): ByteArray =
            NativeIap2Csm.encode(false, messageId, payload)

        fun encodeParam(parameterId: Int, payload: ByteArray): ByteArray =
            NativeIap2Csm.encode(true, parameterId, payload)

        fun splitForLink(frame: Iap2Frame, linkChunkSize: Int): List<ByteArray> =
            splitForLink(frame.encodedFrame(), linkChunkSize)

        fun splitForLink(completeFrame: ByteArray, linkChunkSize: Int): List<ByteArray> =
            NativeIap2Csm.split(completeFrame, linkChunkSize).toList()
    }
}

internal object NativeIap2Csm {
    init { check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" } }
    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun offer(handle: Long, chunk: ByteArray): Array<Iap2Frame>
    @JvmStatic external fun encode(parameter: Boolean, id: Int, payload: ByteArray): ByteArray
    @JvmStatic external fun split(frame: ByteArray, chunkSize: Int): Array<ByteArray>
}

/**
 * Pure CSM framing for iAP2 control-session bytes.
 *
 * CSM is `0x4040 | u16 length | u16 messageId | body`, big-endian and without a checksum.
 * The retained receive data is bounded to one largest-valid frame. This class deliberately has no
 * threading, I/O, link-engine, or message-specific knowledge.
 */
