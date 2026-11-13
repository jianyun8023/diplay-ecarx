package com.shihab.diplay.airplay

/** One native SRP / long-term-key exchange owner; Android supplies only the live pairing store. */
class PairSetup(private val identity: AirPlayIdentity, private val pairings: PairingStore) : java.io.Closeable {
    private var handle = 0L
    private var closed = false

    val complete: Boolean
        @Synchronized get() = handle != 0L && NativePairSetup.complete(handle)

    @Synchronized fun handle(body: ByteArray): ByteArray {
        check(!closed) { "PairSetup is closed" }
        if (handle == 0L) {
            check(NativeMedia.available) { "Native protocol core is unavailable" }
            handle = NativePairSetup.create(identity.privateKey, identity.publicKey,
                identity.pairingId.toByteArray(Charsets.UTF_8)).also { check(it != 0L) }
        }
        return NativePairSetup.handle(handle, body, pairings)
    }

    @Synchronized override fun close() {
        if (closed) return
        if (handle != 0L) NativePairSetup.destroy(handle)
        handle = 0L
        closed = true
    }
}
