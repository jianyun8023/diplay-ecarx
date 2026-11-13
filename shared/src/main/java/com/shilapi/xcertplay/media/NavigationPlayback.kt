package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia

/** A null stream means usage routing, fallback, or conflicting active legacy routes. */
data class NavigationPlaybackSnapshot(val active: Boolean, val legacyStreamType: Int?, val priorityVoiceActive: Boolean = false)

/** Foreground UI can poll this snapshot; it never requests focus or changes volume itself. */
object NavigationPlayback {
    private val tracker by lazy { NavigationPlaybackTracker() }
    fun snapshot(): NavigationPlaybackSnapshot = tracker.snapshot()
    internal fun open() = tracker.open()
    internal fun played(token: NavigationPlaybackTracker.Token, streamType: Int?, bufferedMillis: Long, priorityVoice: Boolean = false) =
        tracker.played(token, streamType, bufferedMillis, priorityVoice)
    internal fun close(token: NavigationPlaybackTracker.Token) = tracker.close(token)
}

/** Rust owns per-renderer leases, activity expiry and aggregate route/priority decisions. */
internal class NavigationPlaybackTracker(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val quietTailMillis: Long = 900L,
) {
    class Token internal constructor(internal val id: Long)
    private val owner = run { NativeMedia.requireAvailable(); arrayOf(NativePlaybackCore.navigationCreate()) }

    @Synchronized fun open() = Token(event(0))

    @Synchronized fun played(token: Token, streamType: Int?, bufferedMillis: Long, priorityVoice: Boolean = false) {
        if (event(3, token.id) == 0L) return
        event(1, token.id, streamType, nowMillis(), bufferedMillis, priorityVoice)
    }

    @Synchronized fun close(token: Token) { event(2, token.id) }

    @Synchronized fun snapshot(): NavigationPlaybackSnapshot {
        val values = NativePlaybackCore.navigationSnapshot(owner, nowMillis())
        return NavigationPlaybackSnapshot(values[0] != 0L, if (values[1] != 0L) values[2].toInt() else null, values[3] != 0L)
    }

    private fun event(operation: Int, id: Long = 0, stream: Int? = null, now: Long = 0,
        buffered: Long = 0, priority: Boolean = false): Long =
        NativePlaybackCore.navigationEvent(owner, operation, id, stream ?: 0, stream != null,
            now, buffered, quietTailMillis, priority)
}
