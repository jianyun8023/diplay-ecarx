package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia

/** Rust owns framing, negotiation, sequence/window state, queues and timers; the caller owns I/O. */
class Iap2LinkEngine(config: Iap2LinkConfig = Iap2LinkConfig()) : AutoCloseable {
    enum class State { IDLE, DETECTING, NEGOTIATING, NORMAL, DEAD }

    sealed class Event {
        data class Control(val bytes: ByteArray) : Event()
        data class Session(val sessionId: Int, val bytes: ByteArray) : Event()
        data class Writable(val value: Boolean) : Event()
        data class Dead(val reason: String?) : Event()
    }

    data class SessionDescriptor(val id: Int, val kind: Int, val version: Int) {
        init {
            require(id in 0..0xff)
            require(kind in 0..0xff)
            require(version in 0..0xff)
        }
    }

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        init {
            require(maxOutgoing in 0..0xff)
            require(maxLength in 0..MAX_WIRE_FRAME_BYTES)
            require(retransmissionTimeoutMillis in 0..0xffff)
            require(acknowledgementTimeoutMillis in 0..0xffff)
            require(maxRetransmissions in 0..0xff)
            require(maxAcknowledgements in 0..0xff)
        }

        fun encode(): ByteArray {
            val values = IntArray(6 + sessions.size * 3)
            values[0] = maxOutgoing
            values[1] = maxLength
            values[2] = retransmissionTimeoutMillis
            values[3] = acknowledgementTimeoutMillis
            values[4] = maxRetransmissions
            values[5] = maxAcknowledgements
            sessions.forEachIndexed { index, session ->
                values[6 + index * 3] = session.id
                values[7 + index * 3] = session.kind
                values[8 + index * 3] = session.version
            }
            return NativeIap2Link.encodeSynchronization(values)
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? =
                NativeIap2Link.decodeSynchronization(bytes)?.let(::fromNative)

            internal fun fromNative(values: IntArray) = SynchronizationPayload(
                values[0], values[1], values[2], values[3], values[4], values[5],
                (6 until values.size step 3).map { SessionDescriptor(values[it], values[it + 1], values[it + 2]) },
            )
        }
    }

    private var handle = NativeIap2Link.create(intArrayOf(
        config.maxOutgoing, config.maxOutgoingDelta, config.acknowledgementTimeoutMillis,
        if (config.zeroAcknowledgements) 1 else 0, config.controlSessionVersion,
        config.maximumQueuedPackets, config.maximumOutOfOrderPackets,
        config.maximumPendingOutputBytes, config.maximumPendingEvents,
    )).also { check(it != 0L) }

    private val openHandle: Long get() = handle.also { check(it != 0L) { "iAP2 link engine is closed" } }

    @Synchronized fun state(): State = State.entries[NativeIap2Link.state(openHandle)]
    @Synchronized fun writable(): Boolean = NativeIap2Link.writable(openHandle)
    @Synchronized fun peerSynchronization(): SynchronizationPayload =
        SynchronizationPayload.fromNative(NativeIap2Link.synchronization(openHandle))
    @Synchronized fun takeOutput(): ByteArray = NativeIap2Link.takeOutput(openHandle)

    @Synchronized fun pollEvent(): Event? {
        val event = NativeIap2Link.pollEvent(openHandle) ?: return null
        return when (event[0].toInt()) {
            0 -> Event.Control(event.copyOfRange(1, event.size))
            1 -> Event.Session(event[1].toInt() and 0xff, event.copyOfRange(2, event.size))
            2 -> Event.Writable(event[1].toInt() != 0)
            3 -> Event.Dead(null)
            else -> Event.Dead(event.decodeToString(1, event.size))
        }
    }

    @Synchronized fun nextDeadlineMillis(): Long? = NativeIap2Link.nextDeadlineMillis(openHandle)
    @Synchronized fun start(wiredInitiator: Boolean, nowMillis: Long) = NativeIap2Link.start(openHandle, wiredInitiator, nowMillis)
    @Synchronized fun feed(bytes: ByteArray, nowMillis: Long) = NativeIap2Link.feed(openHandle, bytes, nowMillis)
    @Synchronized fun feedEof() = NativeIap2Link.feedEof(openHandle)
    @Synchronized fun advanceTime(nowMillis: Long) = NativeIap2Link.advanceTime(openHandle, nowMillis)
    fun sendControl(bytes: ByteArray, nowMillis: Long) = sendSession(CONTROL_SESSION_ID, bytes, nowMillis)
    @Synchronized fun sendSession(sessionId: Int, bytes: ByteArray, nowMillis: Long) =
        NativeIap2Link.sendSession(openHandle, sessionId, bytes, nowMillis)

    @Synchronized override fun close() {
        if (handle != 0L) NativeIap2Link.destroy(handle)
        handle = 0L
    }

    companion object {
        const val CONTROL_SESSION_ID = 10
        const val EA_SESSION_ID = 11
        const val FILE_TRANSFER_SESSION_ID = 12
        val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)
        const val MAX_WIRE_FRAME_BYTES = 0xffff
        const val MAX_PAYLOAD_BYTES = MAX_WIRE_FRAME_BYTES - 10
    }
}

internal object NativeIap2Link {
    init { check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" } }
    @JvmStatic external fun create(config: IntArray): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun state(handle: Long): Int
    @JvmStatic external fun writable(handle: Long): Boolean
    @JvmStatic external fun synchronization(handle: Long): IntArray
    @JvmStatic external fun takeOutput(handle: Long): ByteArray
    @JvmStatic external fun pollEvent(handle: Long): ByteArray?
    @JvmStatic external fun nextDeadlineMillis(handle: Long): Long?
    @JvmStatic external fun start(handle: Long, wired: Boolean, nowMillis: Long)
    @JvmStatic external fun feed(handle: Long, bytes: ByteArray, nowMillis: Long)
    @JvmStatic external fun feedEof(handle: Long)
    @JvmStatic external fun advanceTime(handle: Long, nowMillis: Long)
    @JvmStatic external fun sendSession(handle: Long, sessionId: Int, bytes: ByteArray, nowMillis: Long)
    @JvmStatic external fun encodeSynchronization(values: IntArray): ByteArray
    @JvmStatic external fun decodeSynchronization(bytes: ByteArray): IntArray?
}

/** Configuration advertised by the local link endpoint. */
data class Iap2LinkConfig(
    val maxOutgoing: Int = 30,
    val maxOutgoingDelta: Int = 0,
    val acknowledgementTimeoutMillis: Int = 500,
    val zeroAcknowledgements: Boolean = false,
    val controlSessionVersion: Int = 1,
    val maximumQueuedPackets: Int = 64,
    val maximumOutOfOrderPackets: Int = 64,
    val maximumPendingOutputBytes: Int = 1_048_576,
    val maximumPendingEvents: Int = 256,
) {
    init {
        require(maxOutgoing in 0..0xff)
        require(maxOutgoingDelta in 0..0xff)
        require(acknowledgementTimeoutMillis in 0..0xffff)
        require(controlSessionVersion in 0..0xff)
        require(maximumQueuedPackets in 1..256)
        require(maximumOutOfOrderPackets in 1..256)
        require(maximumPendingOutputBytes >= 1)
        require(maximumPendingEvents >= 1)
    }
}
