package com.shihab.diplay.media

import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.HandlerThread
import android.graphics.SurfaceTexture
import android.view.Surface
import android.media.MediaCodecList
import com.shihab.diplay.airplay.AirPlayCrypto
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativeStreamCallbacks
import com.shihab.diplay.airplay.NativeVideoCallbacks
import com.shihab.diplay.airplay.ScreenStream
import com.shihab.diplay.airplay.currentScreenListener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeVideoRouteDeviceTest {
    @Test fun encryptedScreenStreamHardwareDecodesToSurfaceWithoutPacketCallbacks() {
        // API 28 emulator CCodec stalls with both direct and Kotlin forwarding; hardware smoke needs hardware.
        assumeTrue("AVC hardware decoder required", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            !it.isEncoder && "video/avc" in it.supportedTypes && !isSoftwareDecoderName(it.name)
        })
        // The same generated 320x240 baseline frame as NativeVideoStartupDeviceTest; exactly one IDR.
        val nals = listOf("6742c00dda0507ec0440000003004000000f23c50aa8", "68ce0fc8",
            "6588843a2628000902c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9c9d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
                "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
                "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
                "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d" +
                "75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75d75e0",
        ).map { hex ->
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val (sps, pps) = nals
        val config = byteArrayOf(1, sps[1], sps[2], sps[3], -1, -31, 0, sps.size.toByte()) +
            sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        val key = ByteArray(32)
        val imageReady = CountDownLatch(1)
        val packets = AtomicInteger()
        val images = HandlerThread("native-video-test-images").apply { start() }
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(320, 240) }
        texture.setOnFrameAvailableListener({ imageReady.countDown() }, Handler(images.looper))
        val surface = Surface(texture)
        val sink = AndroidMediaSink(surface = surface, videoWidth = 320, videoHeight = 240)
        val stream = ScreenStream(key, InetAddress.getLoopbackAddress())
        val delegate = currentScreenListener(110, sink) { true }
        val port = stream.listen(object : ScreenStream.Listener by delegate {
            override fun onFrame(frame: MediaPacket, senderNanos: Long, arrivalNanos: Long) {
                packets.incrementAndGet()
                delegate.onFrame(frame, senderNanos, arrivalNanos)
            }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { sender ->
                val output = sender.getOutputStream()
                val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(config.size).put(1).array()
                output.write(header); output.write(config)
                val idr = nals.last()
                val frame = ByteBuffer.allocate(idr.size + 4).putInt(idr.size).put(idr).array()
                val frameHeader = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).putInt(frame.size + 16).array()
                output.write(frameHeader)
                output.write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), frame, frameHeader))
                output.flush()
                val didRender = imageReady.await(10, TimeUnit.SECONDS)
                assertTrue("The only decrypted IDR must reach the platform Surface: ${sink.liveVideoCounters()}", didRender)
                assertEquals(0, packets.get())
                assertTrue(sink.mainVideoReceived)
                assertEquals(1L, requireNotNull(sink.liveVideoCounters()).received)
            }
        } finally {
            stream.close()
            sink.close()
            assertTrue(sink.awaitVideoReleased(5000))
            surface.release()
            texture.release()
            images.quitSafely()
        }
    }

    @Test fun receiverRoutesToMirrorsAndSurvivesTargetAndRouteDestruction() {
        assertTrue(NativeMedia.available)
        val events = object : NativeVideoCallbacks() { override fun onEvent(kind: Int, detail: String?) {} }
        var first = NativeMedia.pipeCreate(events, 0, true)
        val mirror = NativeMedia.pipeCreate(events, 0, false)
        val next = NativeMedia.pipeCreate(events, 0, true)
        assertTrue(first != 0L && mirror != 0L && next != 0L)
        NativeMedia.pipeSetAccepting(first, true, false)
        var route = NativeMedia.videoRouteCreate()
        val packets = AtomicInteger()
        val receiving = AtomicInteger()
        val ended = CountDownLatch(1)
        var report = ""
        val callbacks = object : NativeStreamCallbacks() {
            override fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long) {
                NativeMedia.packetRelease(handle)
                packets.incrementAndGet()
            }
            override fun onReceiving() { receiving.incrementAndGet() }
            override fun onLog(level: Int, line: String) {}
            override fun onReport(line: String) { report = line }
            override fun onEnded(error: String?) { assertNull(error); ended.countDown() }
        }
        val key = ByteArray(32) { it.toByte() }
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sender = Socket(server.inetAddress, server.localPort)
        val receiver = server.accept()
        val fd = ParcelFileDescriptor.fromSocket(receiver).detachFd()
        var handle = 0L
        try {
            NativeMedia.videoRouteSetTargets(route, longArrayOf(NativeMedia.pipeRetain(mirror), NativeMedia.pipeRetain(first)))
            handle = NativeMedia.startVideo(fd, key, callbacks, route)
            assertTrue(handle != 0L)
            val values = LongArray(4)
            fun awaitFrames(pipe: Long, frames: Long) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                do {
                    NativeMedia.pipeLive(pipe, values)
                    if (values[0] == frames) return
                    Thread.sleep(5)
                } while (System.nanoTime() < deadline)
                assertEquals(frames, values[0])
            }
            fun send(counter: Long) {
                val plain = byteArrayOf(0, 0, 0, 2, 0x65, 1)
                val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(plain.size + 16).array()
                sender.getOutputStream().apply {
                    write(header)
                    write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header))
                    flush()
                }
            }
            NativeMedia.videoTouchSent(System.nanoTime() - 1_000_000)
            send(0)
            awaitFrames(first, 1)
            awaitFrames(mirror, 1)
            val stats = LongArray(14)
            NativeMedia.pipeTakeStats(first, stats, IntArray(1024))
            assertEquals(1L, stats[6])
            assertEquals(6L, stats[7])
            assertEquals(1L, stats[11])
            assertTrue(stats[12] >= 1_000_000)

            // The route's Arc keeps memory valid, but the destroyed target must release new packets.
            NativeMedia.pipeDestroy(first)
            first = 0
            send(1)
            awaitFrames(mirror, 2)
            NativeMedia.videoRouteSetTargets(route, longArrayOf(NativeMedia.pipeRetain(next), NativeMedia.pipeRetain(mirror)))
            send(2)
            awaitFrames(next, 1)
            awaitFrames(mirror, 3)
            NativeMedia.videoRouteDestroy(route)
            route = 0
            send(3)
            sender.shutdownOutput()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertEquals(0, packets.get())
            assertEquals(1, receiving.get())
            NativeMedia.pipeLive(next, values)
            assertEquals(1L, values[0])
            assertTrue(report, report.contains("pooled=0"))
        } finally {
            if (handle != 0L) NativeMedia.stop(handle) else ParcelFileDescriptor.adoptFd(fd).close()
            if (route != 0L) NativeMedia.videoRouteDestroy(route)
            if (first != 0L) NativeMedia.pipeDestroy(first)
            NativeMedia.pipeDestroy(mirror)
            NativeMedia.pipeDestroy(next)
            receiver.close()
            sender.close()
            server.close()
        }
    }

    @Test fun unsupportedTargetKeepsDecryptedPacketCallbackAndConfigOrdering() {
        assertTrue(NativeMedia.available)
        val route = NativeMedia.videoRouteCreate()
        NativeMedia.videoRouteSetTargets(route, longArrayOf(0))
        val received = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var plaintext = byteArrayOf()
        var flags = 0
        val configs = AtomicInteger()
        val key = ByteArray(32)
        val callbacks = object : NativeStreamCallbacks() {
            override fun onPacket(handle: Long, size: Int, packetFlags: Int, a: Long, b: Long) {
                try {
                    plaintext = ByteArray(size)
                    assertTrue(NativeMedia.packetRead(handle, 0, size, plaintext, 0))
                    flags = packetFlags
                    assertEquals(1, configs.get())
                    received.countDown()
                } finally { NativeMedia.packetRelease(handle) }
            }
            override fun onConfig(bytes: ByteArray) { assertArrayEquals(byteArrayOf(1, 2), bytes); configs.incrementAndGet() }
            override fun onLog(level: Int, line: String) {}
            override fun onReport(line: String) {}
            override fun onEnded(error: String?) { assertNotNull(error); ended.countDown() }
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { sender ->
                server.accept().use { receiver ->
                    val fd = ParcelFileDescriptor.fromSocket(receiver).detachFd()
                    val handle = NativeMedia.startVideo(fd, key, callbacks, route)
                    assertTrue(handle != 0L)
                    try {
                        val configHeader = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).putInt(2).put(1).array()
                        val frame = byteArrayOf(0, 0, 0, 2, 0x65, 1)
                        val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).putInt(frame.size + 16).array()
                        sender.getOutputStream().apply {
                            write(configHeader); write(byteArrayOf(1, 2))
                            write(header); write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), frame, header)); flush()
                        }
                        assertTrue(received.await(5, TimeUnit.SECONDS))
                        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1), plaintext)
                        assertTrue(flags and MediaPacket.FLAG_RANDOM_ACCESS_H264 != 0)
                        // A failed authentication ends the stream instead of delivering corrupt input.
                        sender.getOutputStream().apply { write(header); write(ByteArray(frame.size + 16)); flush() }
                        assertTrue(ended.await(5, TimeUnit.SECONDS))
                    } finally { NativeMedia.stop(handle) }
                }
            }
        }
        NativeMedia.videoRouteDestroy(route)
    }
}
