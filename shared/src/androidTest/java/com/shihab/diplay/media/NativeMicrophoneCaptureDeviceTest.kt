package com.shihab.diplay.media

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.shihab.diplay.airplay.AirPlayCrypto
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import org.concentus.OpusDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMicrophoneCaptureDeviceTest : MicrophoneCaptureContract() {
    /** Requires RECORD_AUDIO; captured bytes stay in this process and the loopback receiver. */
    @Test fun audioRecordFeedsNativePcmAndOpusAndCanRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Grant RECORD_AUDIO to the independent test package before this device smoke test",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        for (codec in listOf(AudioCodecKind.LPCM, AudioCodecKind.OPUS)) repeat(2) {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
                receiver.soTimeout = 5_000
                val rate = if (codec == AudioCodecKind.OPUS) 48_000 else 16_000
                val config = MicrophoneConfig("telephony", rate, 1, 100, 20,
                    receiver.localAddress, receiver.localPort, ByteArray(32) { it.toByte() },
                    codec, bitrate = if (codec == AudioCodecKind.OPUS) 24_000 else null)
                val reports = CopyOnWriteArrayList<String>()
                EchoReference(rate).use { reference ->
                    val uplink = MicrophoneUplink(config, reports::add, speakerphoneCall = true,
                        echoReferenceSource = { reference })
                    try {
                        assertTrue(reports.joinToString("\n"), uplink.start())
                        assertTrue(MicrophoneUplink::class.java.getDeclaredField("softwareEcho")
                            .apply { isAccessible = true }.getBoolean(uplink))
                        val decoder = if (codec == AudioCodecKind.OPUS) OpusDecoder(48_000, 1) else null
                        repeat(5) { sequence ->
                            val packet = DatagramPacket(ByteArray(4096), 4096)
                            receiver.receive(packet)
                            val wire = packet.data.copyOf(packet.length)
                            assertEquals(0x80, wire[0].toInt() and 0xff)
                            assertEquals(100, wire[1].toInt() and 0x7f)
                            assertEquals(sequence, ByteBuffer.wrap(wire).getShort(2).toInt() and 0xffff)
                            assertEquals(sequence * config.rtpSamplesPerPacket, ByteBuffer.wrap(wire).getInt(4))
                            val counter = wire.copyOfRange(wire.size - 8, wire.size)
                            assertEquals(sequence.toLong(), ByteBuffer.wrap(counter).order(ByteOrder.LITTLE_ENDIAN).long)
                            val nonce = ByteArray(12).also { counter.copyInto(it, 4) }
                            val body = AirPlayCrypto.chachaOpen(config.key, nonce,
                                wire.copyOfRange(12, wire.size - 8), wire.copyOfRange(4, 12))
                            if (decoder == null) assertEquals(config.frameBytes, body.size)
                            else assertEquals(960, decoder.decode(body, 0, body.size, ShortArray(960), 0, 960, false))
                        }
                    } finally { uplink.close() }
                    val worker = MicrophoneUplink::class.java.getDeclaredField("thread")
                        .apply { isAccessible = true }.get(uplink) as Thread
                    worker.join(5_000)
                    assertFalse(worker.isAlive)
                    for (field in listOf("nativeSender", "recorder")) {
                        assertNull(MicrophoneUplink::class.java.getDeclaredField(field)
                            .apply { isAccessible = true }.get(uplink))
                    }
                    assertTrue(reports.joinToString("\n"), reports.any {
                        it.startsWith("Microphone: stats") && it.contains("ended=true")
                    })
                    assertFalse(reports.joinToString("\n"), reports.any { it.startsWith("Microphone: failure") })
                }
            }
        }
    }
}
