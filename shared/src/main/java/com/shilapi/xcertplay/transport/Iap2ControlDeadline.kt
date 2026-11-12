package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia

/** An unlimited drive still has a bounded authentication handshake and bounded I/O polls. */
internal class Iap2ControlDeadline(
    timeoutMillis: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val fallback = if (NativeMedia.available) null else KotlinIap2ControlDeadline(timeoutMillis, clockNanos)
    // Immutable values, not native allocations. Only Rust interprets the token, including its shape.
    private var token = if (fallback == null) NativeControlDeadline.create(timeoutMillis, clockNanos()) else null

    fun authenticated() {
        if (fallback != null) fallback.authenticated() else token = NativeControlDeadline.authenticated(requireNotNull(token))
    }

    fun remainingMillis(): Long {
        if (fallback != null) return fallback.remainingMillis()
        // The supplied clock may reenter authenticated(); this query keeps its original mode.
        val snapshot = requireNotNull(token)
        val now = if (NativeControlDeadline.needsClock(snapshot)) clockNanos() else 0L
        return NativeControlDeadline.remainingMillis(snapshot, now)
    }

    companion object {
        const val HANDSHAKE_MILLIS = 60_000L
        const val MAX_POLL_MILLIS = 30_000L
    }
}

internal object NativeControlDeadline {
    @JvmStatic external fun create(timeoutMillis: Long, startedNanos: Long): LongArray
    @JvmStatic external fun authenticated(token: LongArray): LongArray
    @JvmStatic external fun needsClock(token: LongArray): Boolean
    @JvmStatic external fun remainingMillis(token: LongArray, nowNanos: Long): Long
}

/** Used only when libdiplay_media cannot load. */
internal class KotlinIap2ControlDeadline(
    private val timeoutMillis: Long,
    private val clockNanos: () -> Long,
) {
    private val unlimited = timeoutMillis == Long.MAX_VALUE
    private val startedNanos = clockNanos()
    private var authenticated = false
    fun authenticated() { authenticated = true }
    fun remainingMillis(): Long {
        if (unlimited && authenticated) return Iap2ControlDeadline.MAX_POLL_MILLIS
        val budget = if (unlimited) Iap2ControlDeadline.HANDSHAKE_MILLIS else timeoutMillis
        val elapsedNanos = (clockNanos() - startedNanos).coerceAtLeast(0)
        val remainingNanos = budget * 1_000_000 - elapsedNanos
        if (remainingNanos <= 0) return 0
        return ((remainingNanos + 999_999) / 1_000_000).coerceAtMost(Iap2ControlDeadline.MAX_POLL_MILLIS)
    }
}
