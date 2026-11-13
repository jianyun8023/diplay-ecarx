package com.shihab.diplay.airplay

import java.math.BigInteger

/** The caller serializes this sole clock owner under NtpClock's clock monitor. */
internal class NtpTiming(monoNanos: Long, wallMillis: Long) {
    private val token = NativeNtpTiming.create(monoNanos, wallMillis)

    fun synced(monoNanos: Long): BigInteger = BigInteger(NativeNtpTiming.synced(token, monoNanos))

    fun request(monoNanos: Long): ByteArray = NativeNtpTiming.request(token, monoNanos)

    /** Ignore, reply to this address, or consume a response from the configured peer. */
    fun action(message: ByteArray, fromPeer: Boolean): Int = NativeNtpTiming.action(message, fromPeer)

    fun reply(message: ByteArray, receivedNanos: Long, sentNanos: Long): ByteArray =
        NativeNtpTiming.reply(token, message, receivedNanos, sentNanos)

    fun sample(message: ByteArray, monoNanos: Long) {
        NativeNtpTiming.sample(token, message, monoNanos)
    }
}

internal object NativeNtpTiming {
    init { check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" } }
    @JvmStatic external fun create(monoNanos: Long, wallMillis: Long): LongArray
    @JvmStatic external fun synced(token: LongArray, monoNanos: Long): ByteArray
    @JvmStatic external fun request(token: LongArray, monoNanos: Long): ByteArray
    @JvmStatic external fun action(message: ByteArray, fromPeer: Boolean): Int
    @JvmStatic external fun reply(token: LongArray, message: ByteArray, receivedNanos: Long, sentNanos: Long): ByteArray
    @JvmStatic external fun sample(token: LongArray, message: ByteArray, monoNanos: Long)
}
