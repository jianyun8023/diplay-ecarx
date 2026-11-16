package com.shihab.diplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/** Real engine/listener/native queue; only close timing and a post-cleanup failure are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [BufferedCloseHooks::class])
class BufferedEngineOwnershipTest {
    @After fun clearHooks() { BufferedCloseHooks.before = null; BufferedCloseHooks.after = null }
    private fun setup() = mapOf("shk" to ByteArray(32), "audioFormat" to 0x800000L, "ct" to 4, "spf" to 1024)
    private fun session(engine: CarPlayMediaEngine) = AirPlaySession(
        object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        }, AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), mainBufferedAudio = true),
        AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine,
    )
    private fun registered(engine: CarPlayMediaEngine, session: AirPlaySession): BufferedAudioStream {
        @Suppress("UNCHECKED_CAST")
        val streams = engine.javaClass.getDeclaredField("streams").apply { isAccessible = true }.get(engine) as Map<CarPlayMediaEngine.StreamKey, Any>
        return streams.getValue(CarPlayMediaEngine.StreamKey(session, 103, "media")) as BufferedAudioStream
    }

    @Test fun closingPendingReplacementKeepsOldSessionRetired() = cancelPending(closeSession = true)
    @Test fun pendingTeardownPreservesBarrierButAllowsSameSessionAfterCleanup() = cancelPending(closeSession = false)

    private fun cancelPending(closeSession: Boolean) {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val old = session(engine)
        val next = session(engine)
        val third = session(engine)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val held = AtomicBoolean(false)
        try {
            assertNotNull(engine.onBufferedAudio(old, setup()))
            val oldStream = registered(engine, old)
            BufferedCloseHooks.before = { stream ->
                if (stream === oldStream && held.compareAndSet(false, true)) {
                    entered.countDown()
                    assertTrue("test must release old cleanup", release.await(10, TimeUnit.SECONDS))
                }
            }
            val pending = executor.submit<Map<String, Any?>?> { engine.onBufferedAudio(next, setup()) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(oldStream.outputCleanupComplete)
            if (closeSession) next.close() else engine.onTeardown(next, 103)
            assertEquals(closeSession, next.isClosed)
            assertNull(engine.onBufferedAudio(old, setup()))
            assertNull(engine.onBufferedAudio(third, setup()))
            release.countDown()
            assertNull(pending.get(3, TimeUnit.SECONDS))
            assertTrue(oldStream.outputCleanupComplete)
            assertNull("replacement cancellation must not undo old retirement", engine.onBufferedAudio(old, setup()))
            if (closeSession) {
                assertNull(engine.onBufferedAudio(next, setup()))
                assertNotNull(engine.onBufferedAudio(third, setup()))
            } else {
                assertNotNull("TEARDOWN does not retire the still-open session", engine.onBufferedAudio(next, setup()))
            }
        } finally {
            release.countDown(); clearHooks()
            old.close(); next.close(); third.close(); executor.shutdownNow()
        }
    }

    @Test fun teardownBlocksReentrantSetupUntilRealCloseFinishesThenSameSessionRebuilds() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val owner = session(engine)
        val entered = AtomicBoolean(false)
        try {
            assertNotNull(engine.onBufferedAudio(owner, setup()))
            val stream = registered(engine, owner)
            BufferedCloseHooks.before = { closing ->
                if (closing === stream && entered.compareAndSet(false, true)) {
                    assertFalse(closing.outputCleanupComplete)
                    assertNull(engine.onBufferedAudio(owner, setup()))
                }
            }
            engine.onTeardown(owner, 103)
            assertTrue(entered.get())
            assertTrue(stream.outputCleanupComplete)
            assertFalse(owner.isClosed)
            assertNotNull(engine.onBufferedAudio(owner, setup()))
        } finally { clearHooks(); owner.close() }
    }

    @Test fun closeThrowAfterCleanupRejectsThisSetupAndNextPreflightReleasesBarrier() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val old = session(engine)
        val next = session(engine)
        val failed = AtomicBoolean(false)
        try {
            assertNotNull(engine.onBufferedAudio(old, setup()))
            val stream = registered(engine, old)
            val caller = Thread.currentThread()
            BufferedCloseHooks.after = { closing ->
                if (closing === stream && Thread.currentThread() === caller && failed.compareAndSet(false, true)) {
                    assertTrue(closing.outputCleanupComplete)
                    throw IllegalStateException("close returned exceptionally after output cleanup")
                }
            }
            assertNull(engine.onBufferedAudio(next, setup()))
            assertTrue(failed.get())
            assertTrue(stream.outputCleanupComplete)
            // Even a rejected old-session preflight releases a completed retirement barrier.
            assertNull(engine.onBufferedAudio(old, setup()))
            assertNotNull(engine.onBufferedAudio(next, setup()))
        } finally { clearHooks(); old.close(); next.close() }
    }
}

@Implements(BufferedAudioStream::class, isInAndroidSdk = false)
internal class BufferedCloseHooks {
    @RealObject private lateinit var stream: BufferedAudioStream
    @Implementation fun close() {
        before?.invoke(stream)
        Shadow.directlyOn<Unit, BufferedAudioStream>(stream, BufferedAudioStream::class.java, "close")
        after?.invoke(stream)
    }
    companion object {
        @Volatile var before: ((BufferedAudioStream) -> Unit)? = null
        @Volatile var after: ((BufferedAudioStream) -> Unit)? = null
    }
}
