package com.shihab.diplay.airplay

/** The responder's sole state owner; Android retains the live pairing-store lookup. */
class PairVerify(identity: AirPlayIdentity, private val pairings: PairingStore) : java.io.Closeable {
    data class ControlKeys(val readKey: ByteArray, val writeKey: ByteArray)

    init { NativeMedia.requireAvailable() }

    private var handle = NativePairVerify.create(
        identity.privateKey, identity.pairingId.toByteArray(Charsets.UTF_8),
    ).also { check(it != 0L) }
    private var closed = false

    val isVerified: Boolean
        @Synchronized get() = !closed && NativePairVerify.verified(handle)
    val verifiedControllerId: String?
        @Synchronized get() = if (closed) null else NativePairVerify.controllerId(handle)?.toString(Charsets.UTF_8)
    val controlKeys: ControlKeys?
        @Synchronized get() = if (closed) null else NativePairVerify.keys(handle)
    val shared: ByteArray?
        @Synchronized get() = if (closed) null else NativePairVerify.shared(handle)

    @Synchronized fun handle(body: ByteArray): ByteArray {
        check(!closed) { "PairVerify is closed" }
        return NativePairVerify.handle(handle, body, pairings)
    }

    @Synchronized override fun close() {
        if (closed) return
        NativePairVerify.destroy(handle)
        handle = 0L
        closed = true
    }
}
