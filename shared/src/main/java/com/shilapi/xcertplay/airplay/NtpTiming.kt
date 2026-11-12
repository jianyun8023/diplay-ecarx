package com.shilapi.xcertplay.airplay

import java.math.BigInteger

/** The caller serializes this sole clock owner under NtpClock's clock monitor. */
internal class NtpTiming(monoNanos: Long, wallMillis: Long) {
    private val fallback = if (NativeMedia.available) null else KotlinNtpTiming(monoNanos, wallMillis)
    private val token = if (fallback == null) NativeNtpTiming.create(monoNanos, wallMillis) else null

    fun synced(monoNanos: Long): BigInteger = fallback?.synced(monoNanos)
        ?: BigInteger(NativeNtpTiming.synced(requireNotNull(token), monoNanos))

    fun request(monoNanos: Long): ByteArray = fallback?.request(monoNanos)
        ?: NativeNtpTiming.request(requireNotNull(token), monoNanos)

    /** Ignore, reply to this address, or consume a response from the configured peer. */
    fun action(message: ByteArray, fromPeer: Boolean): Int = fallback?.action(message, fromPeer)
        ?: NativeNtpTiming.action(message, fromPeer)

    fun reply(message: ByteArray, receivedNanos: Long, sentNanos: Long): ByteArray =
        fallback?.reply(message, receivedNanos, sentNanos)
            ?: NativeNtpTiming.reply(requireNotNull(token), message, receivedNanos, sentNanos)

    fun sample(message: ByteArray, monoNanos: Long) {
        if (fallback != null) fallback.sample(message, monoNanos)
        else NativeNtpTiming.sample(requireNotNull(token), message, monoNanos)
    }
}

internal object NativeNtpTiming {
    @JvmStatic external fun create(monoNanos: Long, wallMillis: Long): LongArray
    @JvmStatic external fun synced(token: LongArray, monoNanos: Long): ByteArray
    @JvmStatic external fun request(token: LongArray, monoNanos: Long): ByteArray
    @JvmStatic external fun action(message: ByteArray, fromPeer: Boolean): Int
    @JvmStatic external fun reply(token: LongArray, message: ByteArray, receivedNanos: Long, sentNanos: Long): ByteArray
    @JvmStatic external fun sample(token: LongArray, message: ByteArray, monoNanos: Long)
}
