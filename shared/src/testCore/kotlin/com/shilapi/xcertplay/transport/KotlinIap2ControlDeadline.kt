@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shilapi.xcertplay.transport

/** Original deadline policy, compiled only into tests. */
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
