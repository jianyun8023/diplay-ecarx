package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.airplay.NativeMedia

/** An unlimited drive still has a bounded authentication handshake and bounded I/O polls. */
internal class Iap2ControlDeadline(
    timeoutMillis: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init { NativeMedia.requireAvailable() }
    // Immutable values, not native allocations. Only Rust interprets the token, including its shape.
    private var token = NativeControlDeadline.create(timeoutMillis, clockNanos())

    fun authenticated() {
        token = NativeControlDeadline.authenticated(token)
    }

    fun remainingMillis(): Long {
        // The supplied clock may reenter authenticated(); this query keeps its original mode.
        val snapshot = token
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
