package com.shihab.diplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.shihab.diplay.testcore.ShadowNativeMedia
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [NcmLifetimeMediaShadow::class, NcmWriteConnectionShadow::class])
class NcmNativeLifetimeTest {
    @Test fun trafficQueryFinishesBeforeConcurrentClosesRetireOnce() = race { it.trafficSummary(null) }
    @Test fun attemptQueryFinishesBeforeConcurrentClosesRetireOnce() = race { it.connectionAttemptSince(7000, 0) }
    @Test fun linkQueryFinishesBeforeConcurrentClosesRetireOnce() = race { it.linkOpenedAtNanos }

    private fun race(query: (NcmUsbBridge) -> Any?) {
        NcmLifetimeReplay.entered = CountDownLatch(1)
        NcmLifetimeReplay.release = CountDownLatch(1)
        NcmLifetimeReplay.stops.set(0)
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val bridge = NcmUsbBridge(connection, endpoint(6), endpoint(0x88), null, emptyList(), null)
        ReflectionHelpers.setField(bridge, "nativeEngine", 73L)
        val failure = AtomicReference<Throwable?>()
        fun worker(action: () -> Unit) = Thread {
            try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }.apply { start() }
        val reader = worker { query(bridge) }
        val closers = mutableListOf<Thread>()
        try {
            assertTrue(NcmLifetimeReplay.entered.await(5, TimeUnit.SECONDS))
            repeat(2) { closers += worker { bridge.close() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closers.any { it.state != Thread.State.BLOCKED } && System.nanoTime() < deadline) Thread.yield()
            assertTrue("close must wait for the complete JNI query", closers.all { it.state == Thread.State.BLOCKED })
            assertEquals(0, NcmLifetimeReplay.stops.get())
        } finally {
            NcmLifetimeReplay.release.countDown()
            reader.join(5_000)
            closers.forEach { it.join(5_000) }
            bridge.close()
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertTrue(!reader.isAlive && closers.none { it.isAlive })
        assertEquals(1, NcmLifetimeReplay.stops.get())
    }
}

object NcmLifetimeReplay {
    lateinit var entered: CountDownLatch
    lateinit var release: CountDownLatch
    val stops = AtomicInteger()
    fun query(handle: Long) {
        assertEquals(73L, handle)
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        assertEquals("handle was retired during JNI query", 0, stops.get())
    }
}

@Implements(className = "com.shihab.diplay.airplay.NativeMedia", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class NcmLifetimeMediaShadow : ShadowNativeMedia() {
    companion object {
        @JvmStatic @Implementation fun ncmTraffic(handle: Long, port: Int): String {
            NcmLifetimeReplay.query(handle)
            return "traffic"
        }
        @JvmStatic @Implementation fun ncmAttemptSince(handle: Long, port: Int, since: Long): Boolean {
            NcmLifetimeReplay.query(handle)
            return false
        }
        @JvmStatic @Implementation fun ncmLinkOpened(handle: Long): Long {
            NcmLifetimeReplay.query(handle)
            return 0L
        }
        @JvmStatic @Implementation fun ncmStop(handle: Long) {
            assertEquals(73L, handle)
            NcmLifetimeReplay.stops.incrementAndGet()
        }
    }
}
