package com.shilapi.xcertplay.airplay

internal object NativePairSetup {
    @JvmStatic external fun create(seed: ByteArray, publicKey: ByteArray, identifier: ByteArray): Long
    @JvmStatic external fun handle(handle: Long, body: ByteArray, pairings: PairingStore): ByteArray
    @JvmStatic external fun complete(handle: Long): Boolean
    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic fun save(pairings: PairingStore, identifier: ByteArray, publicKey: ByteArray) {
        pairings.save(identifier.toString(Charsets.UTF_8), publicKey)
    }
}
