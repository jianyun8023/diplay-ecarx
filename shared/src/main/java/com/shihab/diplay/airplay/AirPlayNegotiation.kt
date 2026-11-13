package com.shihab.diplay.airplay

/** The sole negotiation/stream state; callbacks and I/O execute after releasing this monitor. */
internal class AirPlayNegotiation(config: AirPlayConfig) {
    private val state = run {
        requireNative()
        NativeAirPlayNegotiation.create(capabilities(config))
    }

    val isClosed: Boolean get() = query(0) != 0L
    val hasRenderedMainFrame: Boolean get() = query(1) != 0L
    val clusterStream: Int get() = query(2).toInt()
    val timingPort: Int get() = query(3).toInt()
    val mainScreenEpoch: Long get() = query(6)
    val eventSequence: Int get() = query(8).toInt()
    val navigationTimeoutMillis: Long get() = query(11)
    @get:Synchronized val pendingNightMode: Boolean?
        get() = NativeAirPlayNegotiation.query(state, 7).let { if (it < 0) null else it != 0L }

    @Synchronized fun isMainScreenCurrent(epoch: Long): Boolean =
        NativeAirPlayNegotiation.query(state, 5) != 0L && NativeAirPlayNegotiation.query(state, 6) == epoch

    @Synchronized private fun query(field: Int): Long = NativeAirPlayNegotiation.query(state, field)
    @Synchronized private fun event(event: Int, value: Int = 0): Boolean = NativeAirPlayNegotiation.event(state, event, value)

    fun beginClose(): Boolean = event(1)
    fun notifyEnded(): Boolean = event(2)
    fun reportConnecting(): Boolean = event(3)
    fun frameRendered(): Boolean = event(4)
    fun clearCluster() { event(6) }
    fun timingOpened(port: Int) { event(7, port) }
    fun timingPeerStarted() { event(8) }
    fun timingClosed() { event(9) }
    fun removeStream(type: Int): Boolean = event(10, type)
    fun queueNightMode(night: Boolean) { event(12, if (night) 1 else 0) }
    fun advanceEventSequence() { event(13) }
    fun nightModeDelivered() { event(14) }

    @Synchronized fun trackNightMode(night: Boolean): Int {
        NativeAirPlayNegotiation.event(state, 15, if (night) 1 else 0)
        return NativeAirPlayNegotiation.query(state, 8).toInt()
    }
    @Synchronized fun nightModeResponse(sequence: Int): Boolean? =
        NativeAirPlayNegotiation.nightResponse(state, sequence).let { if (it < 0) null else it != 0 }
    @Synchronized fun beginNavigationExit(requestId: Long?): Int =
        NativeAirPlayNegotiation.navigation(state, 1, requestId ?: 0, requestId != null)
    @Synchronized fun releaseNavigationExit(): Int = NativeAirPlayNegotiation.navigation(state, 2, 0, false)
    @Synchronized fun navigationExitSent(sent: Boolean) { NativeAirPlayNegotiation.navigation(state, 3, 0, sent) }
    @Synchronized fun navigationEntity(entity: Int): Int = NativeAirPlayNegotiation.navigation(state, 4, entity.toLong(), true)
    @Synchronized fun navigationRequestId(): Long? = if (NativeAirPlayNegotiation.query(state, 9) != 0L)
        NativeAirPlayNegotiation.query(state, 10) else null

    @Synchronized fun route(method: String, path: String, bodyBytes: Int): RequestAction =
        RequestAction.entries[NativeAirPlayNegotiation.route(state, method, path, bodyBytes)]
    @Synchronized fun streamAction(type: Int): StreamAction =
        StreamAction.entries[NativeAirPlayNegotiation.streamAction(state, type)]
    @Synchronized fun accepted(type: Int): Int = NativeAirPlayNegotiation.accepted(state, type)
    @Synchronized fun streams(): List<Int> = NativeAirPlayNegotiation.streams(state).toList()
    @Synchronized fun timingAction(peerPort: Int, hasPeer: Boolean): Int = NativeAirPlayNegotiation.timingAction(state, peerPort, hasPeer)
    @Synchronized fun prepareTeardown(types: List<Int>?): Int = NativeAirPlayNegotiation.prepareTeardown(state, types?.toIntArray())
    @Synchronized fun finishTeardown(all: Boolean): Boolean = NativeAirPlayNegotiation.finishTeardown(state, all)

    enum class RequestAction { OK, SETUP, PARAMETER, RECORD, TEARDOWN, BUFFERED, GET_ANCHOR,
        PAIR_SETUP, PAIR_VERIFY, AUTH_SETUP, INFO, COMMAND, FEEDBACK }
    enum class StreamAction { UNSUPPORTED, SCREEN, AUDIO, BUFFERED, DATA, BUFFERED_DISABLED }

    companion object {
        private fun requireNative() { check(NativeMedia.available) { "Native AirPlay negotiation core is unavailable" } }

        private fun capabilities(config: AirPlayConfig): Int =
            (if (config.hevc) 1 else 0) or (if (config.cluster != null) 2 else 0) or
                (if (config.main.useIphoneCornerMask) 4 else 0) or (if (config.videoInCar) 8 else 0) or
                (if (config.bufferedAudioOutputEnabled) 16 else 0)

        fun enabledFeatures(config: AirPlayConfig, proposed: List<*>?): List<String> {
            requireNative()
            return NativeAirPlayNegotiation.features(capabilities(config), proposed.orEmpty().filterIsInstance<String>().toTypedArray()).toList()
        }

        fun teardownTypes(body: Any?): List<Int>? {
            requireNative()
            // JVM Number conversions belong at this Java-value boundary, before passing typed data.
            val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty().mapNotNull { entry ->
                val stream = entry as? Map<*, *> ?: return@mapNotNull null
                val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
                type to (stream["streamID"] as? Number)?.toLong()
            }
            return NativeAirPlayNegotiation.teardownTypes(entries.map { it.first }.toIntArray(),
                LongArray(entries.size) { entries[it].second ?: 0 }, BooleanArray(entries.size) { entries[it].second != null })?.toList()
        }
    }
}

internal object NativeAirPlayNegotiation {
    @JvmStatic external fun create(capabilities: Int): LongArray
    @JvmStatic external fun query(state: LongArray, field: Int): Long
    @JvmStatic external fun event(state: LongArray, event: Int, value: Int): Boolean
    @JvmStatic external fun route(state: LongArray, method: String, path: String, bodyBytes: Int): Int
    @JvmStatic external fun streamAction(state: LongArray, type: Int): Int
    @JvmStatic external fun accepted(state: LongArray, type: Int): Int
    @JvmStatic external fun streams(state: LongArray): IntArray
    @JvmStatic external fun timingAction(state: LongArray, peerPort: Int, hasPeer: Boolean): Int
    @JvmStatic external fun prepareTeardown(state: LongArray, types: IntArray?): Int
    @JvmStatic external fun finishTeardown(state: LongArray, all: Boolean): Boolean
    @JvmStatic external fun nightResponse(state: LongArray, sequence: Int): Int
    @JvmStatic external fun navigation(state: LongArray, event: Int, value: Long, present: Boolean): Int
    @JvmStatic external fun features(capabilities: Int, proposed: Array<String>): Array<String>
    @JvmStatic external fun teardownTypes(types: IntArray, ids: LongArray, present: BooleanArray): IntArray?
}
