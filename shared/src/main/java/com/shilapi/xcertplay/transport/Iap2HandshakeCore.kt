package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter

/** Sole protocol state. Frame I/O, clock and listener calls never execute under this monitor. */
internal class Iap2HandshakeCore(kind: Int) {
    private val owner = run { requireNative(); arrayOf(NativeIap2Handshake.create(kind)) }
    val stage: Int get() = query(0).toInt()
    val forwarded: Int get() = query(1).toInt()
    val starts: Int get() = query(2).toInt()
    val wifi: Int get() = query(3).toInt()
    val postWifi: Int get() = query(4).toInt()
    val transportSeen: Boolean get() = query(5) != 0L
    val availableSeen: Boolean get() = query(6) != 0L
    val unavailableSeen: Boolean get() = query(7) != 0L
    val setup: Setup get() = Setup.entries[query(8).toInt()]
    val needsStartAgain: Boolean get() = query(9) != 0L

    @Synchronized private fun query(field: Int): Long = NativeIap2Handshake.query(owner, field)
    @Synchronized private fun event(event: Int, value: Int = 0, condition: Boolean = false) {
        NativeIap2Handshake.event(owner, event, value, condition)
    }
    @Synchronized fun message(id: Int): Action = Action.entries[NativeIap2Handshake.message(owner, id)]
    @Synchronized fun omitted(): Set<Int> = NativeIap2Handshake.omitted(owner).toSet()
    fun rejected(rejection: Iap2IdentificationException.Rejected): Boolean = rejectFacts(rejectionFacts(rejection))
    @Synchronized private fun rejectFacts(facts: Array<IntArray>): Boolean =
        NativeIap2Handshake.rejected(owner, facts[0], facts[1], facts[2])
    @Synchronized fun wifiPlan(): Int = NativeIap2Handshake.wifiPlan(owner)
    @Synchronized fun startAgain(decision: Int): Boolean = NativeIap2Handshake.startAgain(owner, decision)
    @Synchronized fun boundPoll(requested: Long): Long = NativeIap2Handshake.boundPoll(owner, requested)

    fun linkReady() = event(0)
    fun authenticated() = event(1)
    fun subscribed() = event(2)
    fun startSent() = event(3)
    fun forwarded() = event(4)
    fun wifiSent(plan: Int) = event(6, plan)
    fun availability(available: Boolean) = event(7, condition = available)
    fun transportReceived() = event(8)
    fun transportParsed() = event(9)
    fun beginIteration() = event(10)
    fun powerSent() = event(11)

    enum class Setup { COMPLETE, AUTHENTICATE, POWER, SUBSCRIBE }
    enum class Action { UNUSED, IDENTIFICATION_INFO, IDENTIFICATION_ACCEPTED, IDENTIFICATION_REJECTED,
        IDENTIFICATION_UNEXPECTED, MFI_CERTIFICATE, MFI_CHALLENGE, MFI_ACCEPTED, MFI_REJECTED,
        MFI_UNEXPECTED, CARPLAY_AVAILABLE, WIFI_REQUEST, WIRELESS_AVAILABILITY, TRANSPORT,
        LOCATION, VEHICLE, FORWARD, CONTROL_NOT_READY }

    companion object {
        fun requireNative() { check(NativeMedia.available) { "Native iAP2 handshake core is unavailable" } }
        fun challengeValid(length: Int): Boolean { requireNative(); return NativeIap2Handshake.challengeValid(length) }
        fun rejection(parameters: List<Iap2Parameter>): Iap2IdentificationException.Rejected {
            requireNative()
            val rows = NativeIap2Handshake.rejectionMetadata(parameters.map { it.id }.toIntArray(),
                parameters.map { it.payload }.toTypedArray())
            fun messages(index: Int): Iap2RejectedMessageList? = rows[index]?.let { row ->
                Iap2RejectedMessageList(row.drop(4), row[0], row[1], row[2], row[3])
            }
            return Iap2IdentificationException.Rejected(requireNotNull(rows[0]).toSet(), messages(1), messages(2))
        }
        fun omittable(rejection: Iap2IdentificationException.Rejected): Set<Int>? {
            requireNative()
            val facts = rejectionFacts(rejection)
            return NativeIap2Handshake.omittable(facts[0], facts[1], facts[2])?.toSet()
        }
        private fun rejectionFacts(rejection: Iap2IdentificationException.Rejected): Array<IntArray> {
            val lists = listOfNotNull(rejection.unsupportedMessagesSent, rejection.unsupportedMessagesReceived)
            return arrayOf(rejection.parameterIds.toIntArray(), lists.flatMap { it.messageIds }.toIntArray(),
                lists.flatMap { listOf(it.malformedPayloadCount, it.omittedMessageCount) }.toIntArray())
        }
    }
}

/** Distinct historical deadline arithmetic is kept by kind, including signed Long wrap behavior. */
internal class Iap2HandshakeDeadline(kind: Int, timeout: Long, private val nowNanos: () -> Long = System::nanoTime) {
    private val state = run {
        Iap2HandshakeCore.requireNative()
        NativeIap2Handshake.deadlinePrepare(kind, timeout).also {
            if (NativeIap2Handshake.deadlineNeedsStart(it)) NativeIap2Handshake.deadlineStart(it, nowNanos())
        }
    }
    @Synchronized private fun plan(): LongArray = NativeIap2Handshake.deadlinePlan(state)
    @Synchronized private fun at(now: Long): LongArray = NativeIap2Handshake.deadlineAt(state, now)
    @Synchronized private fun keep(plan: Int, alive: Boolean): Long = NativeIap2Handshake.deadlineKeepAlive(state, plan, alive)

    fun remaining(keepAlive: () -> Boolean = { false }): Long {
        var next = plan()
        if (next[0] == 1L) next = at(nowNanos())
        return if (next[0] == 0L) next[1] else keep(next[0].toInt(), keepAlive())
    }
}

internal class Iap2TransportWaitCore(wait: Long, now: Long) {
    private val state = run { Iap2HandshakeCore.requireNative(); NativeIap2Handshake.waitCreate(wait, now) }
    fun needsClock(sent: Int, notified: Boolean): Boolean = NativeIap2Handshake.waitNeedsClock(state, sent, notified)
    fun expired(sent: Int, notified: Boolean, now: Long): Boolean = NativeIap2Handshake.waitExpired(state, sent, notified, now)
    fun bound(requested: Long, sent: Int, notified: Boolean, now: Long): Long = NativeIap2Handshake.waitBound(state, requested, sent, notified, now)
}

internal object NativeIap2Handshake {
    @JvmStatic external fun create(kind: Int): LongArray
    @JvmStatic external fun query(owner: Array<LongArray>, field: Int): Long
    @JvmStatic external fun event(owner: Array<LongArray>, event: Int, value: Int, condition: Boolean)
    @JvmStatic external fun message(owner: Array<LongArray>, id: Int): Int
    @JvmStatic external fun omitted(owner: Array<LongArray>): IntArray
    @JvmStatic external fun rejected(owner: Array<LongArray>, parameters: IntArray, ids: IntArray, counts: IntArray): Boolean
    @JvmStatic external fun wifiPlan(owner: Array<LongArray>): Int
    @JvmStatic external fun startAgain(owner: Array<LongArray>, decision: Int): Boolean
    @JvmStatic external fun boundPoll(owner: Array<LongArray>, requested: Long): Long
    @JvmStatic external fun challengeValid(length: Int): Boolean
    @JvmStatic external fun rejectionMetadata(ids: IntArray, payloads: Array<ByteArray>): Array<IntArray?>
    @JvmStatic external fun omittable(parameters: IntArray, ids: IntArray, counts: IntArray): IntArray?
    @JvmStatic external fun deadlinePrepare(kind: Int, timeout: Long): LongArray
    @JvmStatic external fun deadlineNeedsStart(state: LongArray): Boolean
    @JvmStatic external fun deadlineStart(state: LongArray, now: Long)
    @JvmStatic external fun deadlinePlan(state: LongArray): LongArray
    @JvmStatic external fun deadlineAt(state: LongArray, now: Long): LongArray
    @JvmStatic external fun deadlineKeepAlive(state: LongArray, plan: Int, alive: Boolean): Long
    @JvmStatic external fun waitCreate(wait: Long, now: Long): LongArray
    @JvmStatic external fun waitNeedsClock(state: LongArray, sent: Int, notified: Boolean): Boolean
    @JvmStatic external fun waitExpired(state: LongArray, sent: Int, notified: Boolean, now: Long): Boolean
    @JvmStatic external fun waitBound(state: LongArray, requested: Long, sent: Int, notified: Boolean, now: Long): Long
}
