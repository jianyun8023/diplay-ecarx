package com.shihab.diplay.airplay

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlCipherSessionCloseTest {
    @Test fun failedEventWriterCanCloseWhileGearUpdateWaitsForTheWriteLock() = exercise(readerEof = false)
    @Test fun eventReaderEofClosesSocketBeforeWaitingForVideoAvailability() = exercise(readerEof = true)

    private fun exercise(readerEof: Boolean) {
        val writing = CountDownLatch(1)
        val availabilityHeld = CountDownLatch(1)
        val failWrite = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val event = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    check((if (readerEof) socketClosed else failWrite).await(5, TimeUnit.SECONDS))
                    throw IOException("test event write failed")
                }
            }
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int { check(eof.await(5, TimeUnit.SECONDS)); return -1 }
            }
            override fun close() { super.close(); socketClosed.countDown() }
        }
        val session = AirPlaySession(Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), videoInCar = true),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val controlOwner = ControlCipher(ByteArray(32), ByteArray(32))
        val eventOwner = ControlCipher(ByteArray(32), ByteArray(32))
        fun field(name: String) = AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }
        session.cipher = controlOwner
        field("eventSocket").set(session, event); field("eventCipher").set(session, eventOwner)
        val availability = field("videoPlaybackAvailability").get(session) as VideoPlaybackAvailability
        availability.setFeatureEnabled(true); availability.setEventReady(true)
        fun worker(task: () -> Unit) = Thread {
            try { task() } catch (error: Throwable) { failures.add(error) }
        }.apply { isDaemon = true }
        val writer = worker { session.sendCommand(mapOf("type" to "blocked")) }
        val gear = worker {
            synchronized(availability) {
                availabilityHeld.countDown()
                session.setVideoPlaybackAllowed(true)
            }
        }
        val reader = if (readerEof) worker {
            AirPlaySession::class.java.getDeclaredMethod("runEventRead", Socket::class.java, ControlCipher::class.java)
                .apply { isAccessible = true }.invoke(session, event, eventOwner)
        } else null
        val workers = listOfNotNull(writer, gear, reader)
        try {
            writer.start(); assertTrue(writing.await(3, TimeUnit.SECONDS))
            gear.start(); assertTrue(availabilityHeld.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (gear.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("gear must hold availability while waiting for eventWriteLock", Thread.State.BLOCKED, gear.state)
            if (reader != null) { reader.start(); eof.countDown() } else failWrite.countDown()
            workers.forEach { it.join(1500) }
            assertTrue("stuck threads: ${workers.map { it.state }}", workers.none { it.isAlive })
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(session.isClosed)
            assertThrows(IllegalStateException::class.java) { controlOwner.encrypt(ByteArray(0)) }
            assertThrows(IllegalStateException::class.java) { eventOwner.decrypt(ByteArray(0)) }
            assertEquals(VideoPlaybackDelivery.QUEUED, session.setVideoPlaybackAllowed(false))
        } finally {
            failWrite.countDown(); eof.countDown(); event.close()
            workers.forEach { it.join(100) }
            // A baseline lock cycle cannot be interrupted; daemon workers let a failed test finish.
            if (workers.none { it.isAlive }) session.close()
        }
    }
}
