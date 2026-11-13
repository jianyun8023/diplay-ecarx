package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia
import java.io.Closeable

/** Opaque TX state survives USB close so diagnostics keep their original read-after-close contract. */
internal class NcmTransmitCore {
    private val state = run { NativeMedia.requireAvailable(); NativeNcm.valueCreate(0) }
    @Synchronized fun nextSequence(): Int = NativeNcm.txSequence(state)
    @Synchronized fun timeout(requested: Int): Int = NativeNcm.txTimeout(state, requested)
    @Synchronized fun begin(transferred: Int): LongArray = NativeNcm.txBegin(state, transferred)
    @Synchronized fun opened(now: Long) = NativeNcm.txOpened(state, now)
    @Synchronized fun finish(transferred: Int, expected: Int): Int = NativeNcm.txFinish(state, transferred, expected)
    val openedAt: Long? @Synchronized get() = NativeNcm.txOpenedAt(state).firstOrNull()
}

/** The only allocated owner: bounded pending packets and reassembly; no callback executes under its monitor. */
internal class NcmReceiveCore(packetSize: Int) : Closeable {
    private var handle = run {
        NativeMedia.requireAvailable()
        NativeNcm.receiveCreate(packetSize).also { check(it != 0L) }
    }
    private fun live(): Long = handle.also {
        if (it == 0L) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }
    @Synchronized fun append(chunk: ByteArray, length: Int) = NativeNcm.receiveAppend(live(), chunk, length)
    @Synchronized fun poll(): ByteArray? = NativeNcm.receivePoll(live())
    @Synchronized fun step(now: Long?, detail: Array<String?>): Int =
        NativeNcm.receiveStep(live(), now != null, now ?: 0L, detail)
    @Synchronized override fun close() {
        if (handle != 0L) NativeNcm.receiveClose(handle)
        handle = 0L
    }
}

/** Sole learned peer MAC and multicast/unicast selection; Android still owns the TUN writes. */
internal class NcmIpv6Route {
    private val state = run { NativeMedia.requireAvailable(); NativeNcm.valueCreate(2) }
    @Synchronized fun inbound(frame: ByteArray): EthernetIpv6Codec.Ipv6FrameView? =
        NativeNcm.routeInbound(state, frame)?.let(EthernetIpv6Codec::view)
    @Synchronized fun destination(ipv6: ByteArray, detail: LongArray): ByteArray? =
        NativeNcm.routeDestination(state, ipv6, detail)
}

internal object NativeNcm {
    @JvmStatic external fun valueCreate(kind: Int): LongArray
    @JvmStatic external fun ntbBuild(frame: ByteArray, sequence: Int): ByteArray
    @JvmStatic external fun ntbParse(block: ByteArray, offset: Int, length: Int): Array<ByteArray>
    @JvmStatic external fun ethernetParse(frame: ByteArray): Array<ByteArray>?
    @JvmStatic external fun ethernetView(frame: ByteArray): IntArray?
    @JvmStatic external fun ethernetBuild(source: ByteArray, destination: ByteArray, ipv6: ByteArray): ByteArray
    @JvmStatic external fun ethernetMulticast(ipv6: ByteArray): ByteArray?
    @JvmStatic external fun ethernetFixNa(ipv6: ByteArray, hostMac: ByteArray): ByteArray?
    @JvmStatic external fun trafficHeader(state: LongArray, frame: ByteArray): Int
    @JvmStatic external fun trafficTime(state: LongArray, port: Int, now: Long)
    @JvmStatic external fun trafficSent(state: LongArray)
    @JvmStatic external fun trafficSummary(state: LongArray, port: Int): String
    @JvmStatic external fun trafficAttempt(state: LongArray, port: Int, since: Long): Boolean
    @JvmStatic external fun txSequence(state: LongArray): Int
    @JvmStatic external fun txTimeout(state: LongArray, requested: Int): Int
    @JvmStatic external fun txBegin(state: LongArray, transferred: Int): LongArray
    @JvmStatic external fun txOpened(state: LongArray, now: Long)
    @JvmStatic external fun txOpenedAt(state: LongArray): LongArray
    @JvmStatic external fun txFinish(state: LongArray, transferred: Int, expected: Int): Int
    @JvmStatic external fun receiveCreate(packetSize: Int): Long
    @JvmStatic external fun receiveAppend(handle: Long, chunk: ByteArray, length: Int)
    /** 0 idle, 1 sample clock, 2 report, -1 terminal framing failure. */
    @JvmStatic external fun receiveStep(handle: Long, hasTime: Boolean, now: Long, detail: Array<String?>): Int
    @JvmStatic external fun receivePoll(handle: Long): ByteArray?
    @JvmStatic external fun receiveClose(handle: Long)
    @JvmStatic external fun routeInbound(state: LongArray, frame: ByteArray): IntArray?
    @JvmStatic external fun routeDestination(state: LongArray, ipv6: ByteArray, detail: LongArray): ByteArray?
}
