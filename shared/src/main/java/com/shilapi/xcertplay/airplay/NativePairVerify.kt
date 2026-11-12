package com.shilapi.xcertplay.airplay

/** PairVerify serializes each handle access, including destruction. */
internal object NativePairVerify {
    @JvmStatic external fun create(privateSeed: ByteArray, accessoryId: ByteArray): Long
    @JvmStatic external fun handle(handle: Long, body: ByteArray, pairings: PairingStore): ByteArray
    @JvmStatic external fun shared(handle: Long): ByteArray?
    @JvmStatic external fun keys(handle: Long): PairVerify.ControlKeys?
    @JvmStatic external fun controllerId(handle: Long): ByteArray?
    @JvmStatic external fun verified(handle: Long): Boolean
    @JvmStatic external fun destroy(handle: Long)

    // Charset replacement belongs to Android/JDK, while signature transcripts retain the raw bytes.
    @JvmStatic fun lookup(pairings: PairingStore, identifier: ByteArray): ByteArray? =
        pairings.get(identifier.toString(Charsets.UTF_8))
}
