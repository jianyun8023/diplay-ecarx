package com.shihab.diplay.media

import com.shihab.diplay.airplay.KotlinMicrophoneCounters
import com.shihab.diplay.airplay.KotlinMicrophonePacketizer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** The same real JNI + loopback wire contract on JVM and ART; no recorder or Java send path. */
open class MicrophoneCaptureContract {
    @Test fun fragmentedCaptureMatchesTheIndependentWireReferenceAndDropsOnlyTheFinalPartialFrame() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 5_000
            val key = ByteArray(32) { (it * 13).toByte() }
            val handle = NativeMicrophone.create(key, 100, receiver.localAddress.address, 0, receiver.localPort,
                0, 320, 640, 16_000, 1, 0)
            assertNotEquals(0L, handle)
            val stats = NativeCaptureStats.create(0)
            val host = Host()
            val pcm = ByteArray(1937) { (it * 73).toByte() }
            try {
                var offset = 0
                while (offset < pcm.size) {
                    val chunk = pcm.copyOfRange(offset, minOf(pcm.size, offset + 137))
                    assertEquals(0, NativeMicrophone.feed(handle, chunk, chunk.size, 1_000_000_000,
                        false, 0, 0, host, stats))
                    offset += chunk.size
                }
                val counters = KotlinMicrophoneCounters()
                repeat(3) { frame ->
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    receiver.receive(packet)
                    val expected = KotlinMicrophonePacketizer.sealPacket(key, 100, counters,
                        KotlinMicrophonePacketizer.toWirePcm(pcm.copyOfRange(frame * 640, (frame + 1) * 640)), 320)
                    assertArrayEquals(expected, packet.data.copyOf(packet.length))
                }
                val snapshot = NativeCaptureStats.snapshot(stats, 1_000_000_000, true)!!
                assertEquals(3L, snapshot[5])
                assertEquals(3L, snapshot[7])
                assertEquals(0, host.sourceReads)
            } finally { NativeMicrophone.close(handle) }
            receiver.soTimeout = 50
            assertThrows(java.net.SocketTimeoutException::class.java) {
                receiver.receive(DatagramPacket(ByteArray(2048), 2048))
            }
        }
    }

    @Test fun staticallyLinkedSpeexRunsAtEverySupportedCallRateAndStopsWithTheOwner() {
        for (rate in listOf(16_000, 24_000, 48_000)) {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
                receiver.soTimeout = 5_000
                val samples = rate / 50
                val handle = NativeMicrophone.create(ByteArray(32), 100, receiver.localAddress.address,
                    0, receiver.localPort, 0, samples, samples * 2, rate, 1, 0)
                assertNotEquals(0L, handle)
                EchoReference(rate).use { reference ->
                    val host = Host().apply { source = reference }
                    try {
                        assertTrue(NativeMicrophone.enableEcho(handle, reference))
                        val stats = NativeCaptureStats.create(0)
                        val pcm = ByteArray(samples * 2) { it.toByte() }
                        NativeMicrophone.feed(handle, pcm, pcm.size, 1_000_000_000, false, 0, 0, host, stats)
                        receiver.receive(DatagramPacket(ByteArray(4096), 4096))
                        assertEquals(1, host.sourceReads)
                        assertEquals(1L, NativeCaptureStats.snapshot(stats, 1_000_000_000, true)!![7])
                    } finally { NativeMicrophone.close(handle) }
                    assertNotNull(reference.retain()?.also { it.close() })
                }
            }
        }
    }

    internal class Host {
        var source: EchoReference? = null
        var sourceReads = 0
        fun captureRunning(): Boolean = true
        fun captureEchoSource(): EchoReference? { sourceReads++; return source }
        fun restorePlatformEchoCancellation() { error("Compatible reference must not disable Speex") }
    }
}
