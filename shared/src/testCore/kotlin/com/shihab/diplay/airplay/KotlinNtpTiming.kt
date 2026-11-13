package com.shihab.diplay.airplay

import java.math.BigInteger

/** Independent original NtpClock policy, compiled into test source sets only. */
internal class KotlinNtpTiming(monoNanos: Long, wallMillis: Long) {
    private var clockOffsetNs = wallClockNtpOffsetNs(monoNanos, wallMillis)
    private val delays = DoubleArray(8) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var pickCount = 2
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pendingT1: BigInteger? = null
    private var synced = false

    fun synced(monoNanos: Long): BigInteger = ntpFromNanos(monoNanos + clockOffsetNs)

    fun action(message: ByteArray, fromPeer: Boolean): Int = if (message.size < 32) 0 else {
        when (message[1].toInt() and 0xff) { 210 -> 1; 211 -> if (fromPeer) 2 else 0; else -> 0 }
    }

    fun request(monoNanos: Long): ByteArray {
        val packet = header(210)
        val t1 = synced(monoNanos)
        pendingT1 = t1
        ntpBytes(t1).copyInto(packet, 24)
        return packet
    }

    fun reply(request: ByteArray, receivedNanos: Long, sentNanos: Long): ByteArray {
        val response = header(211)
        request.copyInto(response, 8, 24, 32)
        ntpBytes(synced(receivedNanos)).copyInto(response, 16)
        ntpBytes(synced(sentNanos)).copyInto(response, 24)
        return response
    }

    fun sample(response: ByteArray, monoNanos: Long) {
        val t4 = synced(monoNanos)
        val t1 = readNtp(response, 8)
        val t2 = readNtp(response, 16)
        val t3 = readNtp(response, 24)
        if (pendingT1 == null || pendingT1 != t1) return
        pendingT1 = null
        val offset = t2.subtract(t1).add(t3.subtract(t4)).toDouble() * 0.5 / 0x1_0000_0000L.toDouble()
        val rtt = t4.subtract(t1).subtract(t3.subtract(t2)).toDouble() / 0x1_0000_0000L.toDouble()
        if (rtt < 0.0) return
        if (rtt < pickRtt) { pickRtt = rtt; pickOffset = offset }
        if (--pickCount > 0) return
        val selectedRtt = pickRtt
        val selectedOffset = pickOffset
        pickCount = 2
        pickRtt = Double.POSITIVE_INFINITY
        val useSample = delays.all { selectedRtt <= it }
        delays[delayIndex] = selectedRtt
        delayIndex = (delayIndex + 1) % delays.size
        if (useSample) {
            val stepping = !synced || Math.abs(selectedOffset) > 0.128
            val applied = if (stepping) selectedOffset else selectedOffset * (1.0 / 8.0)
            clockOffsetNs += Math.round(applied * 1e9)
            if (stepping) {
                delays.fill(Double.POSITIVE_INFINITY)
                delayIndex = 0
                pendingT1 = null
                synced = true
            }
        }
    }

    private fun header(type: Int) = ByteArray(32).also {
        it[0] = 0x80.toByte(); it[1] = type.toByte(); it[3] = 7
    }
}

private val TWO32 = BigInteger.ONE.shiftLeft(32)
private val NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L)

private fun wallClockNtpOffsetNs(monoNs: Long, wallMillis: Long): Long {
    val seconds = wallMillis / 1000.0 + 2_208_988_800L
    val whole = Math.floor(seconds).toLong()
    val fraction = Math.floor((seconds - whole) * TWO32.toDouble()).toLong()
    val ntp = BigInteger.valueOf(whole).shiftLeft(32).or(BigInteger.valueOf(fraction))
    return ntp.and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
        .multiply(NANOS_PER_SECOND).shiftRight(32).subtract(BigInteger.valueOf(monoNs)).toLong()
}

private fun ntpFromNanos(ns: Long): BigInteger {
    val seconds = BigInteger.valueOf(Math.floorDiv(ns, 1_000_000_000L))
    val nanos = BigInteger.valueOf(Math.floorMod(ns, 1_000_000_000L))
    return seconds.shiftLeft(32).or(nanos.shiftLeft(32).divide(NANOS_PER_SECOND))
}

private fun ntpBytes(value: BigInteger): ByteArray {
    val bytes = value.and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).toByteArray()
    return ByteArray(8).also { target ->
        val source = if (bytes.size > 8) bytes.copyOfRange(bytes.size - 8, bytes.size) else bytes
        source.copyInto(target, target.size - source.size)
    }
}

private fun readNtp(value: ByteArray, offset: Int): BigInteger = BigInteger(1, value.copyOfRange(offset, offset + 8))
