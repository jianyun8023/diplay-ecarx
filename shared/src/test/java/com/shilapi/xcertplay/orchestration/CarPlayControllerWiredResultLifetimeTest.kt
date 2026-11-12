package com.shilapi.xcertplay.orchestration

import android.hardware.usb.*
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Runs the actual USB open callback, NCM open and USBMUX handshake; only Android USB is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [
    WiredLifetimeUsbManagerShadow::class, WiredLifetimeUsbConnectionShadow::class,
    WiredLifetimeUsbRequestShadow::class,
])
class CarPlayControllerWiredResultLifetimeTest {
    @Before fun reset() = WiredLifetimeUsb.reset()

    @Test fun connectedAfterCloseReleasesUsbWithoutOpeningNcm() {
        atBoundary({ WiredLifetimeUsb.onUsbClaim = it }) { controller ->
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
        }
        assertEquals(1, WiredLifetimeUsb.opens.get())
        assertEquals(listOf("usb"), WiredLifetimeUsb.closes)
    }

    @Test fun connectedFromAnOlderRunCannotEnterTheReplacementDataPaths() {
        atBoundary({ WiredLifetimeUsb.onUsbClaim = it }) { controller ->
            restartDiscovery(controller)
        }
        assertEquals(1, WiredLifetimeUsb.opens.get())
        assertEquals(listOf("usb"), WiredLifetimeUsb.closes)
    }

    @Test fun closeDuringNcmOpenReleasesUnadoptedUsbBeforeNcm() {
        atBoundary({ WiredLifetimeUsb.onNcmSelected = it }) { controller ->
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
        }
        assertEquals(listOf("usb", "ncm"), WiredLifetimeUsb.closes)
    }

    @Test fun muxReturningAfterTeardownCannotBePublishedOrStartPairing() {
        atBoundary({ WiredLifetimeUsb.onMuxSetup = it }) { controller ->
            assertNull(ReflectionHelpers.getField<Any?>(controller, "mux"))
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
        }
        assertEquals(0, WiredLifetimeUsb.pairingCalls.get())
        assertEquals(listOf("usb", "ncm"), WiredLifetimeUsb.closes)
    }

    @Test fun muxReturningAfterRunReplacementCannotStartPairing() {
        atBoundary({ WiredLifetimeUsb.onMuxSetup = it }) { controller ->
            restartDiscovery(controller)
        }
        assertEquals(0, WiredLifetimeUsb.pairingCalls.get())
        assertEquals(listOf("usb", "ncm"), WiredLifetimeUsb.closes)
    }

    @Test fun staleMuxReturnCannotTerminateTheReplacementWatchdog() {
        var cancelled = 0
        var timedOut = 0
        lateinit var timer: () -> Unit
        atBoundary({ WiredLifetimeUsb.onMuxSetup = it }, afterResult = {
            assertEquals("Old result must not cancel the new run's timer", 0, cancelled)
            timer()
            assertEquals(1, timedOut)
        }) { controller ->
            restartDiscovery(controller)
            val replacement = WiredStartWatchdog(
                schedule = { _, action -> timer = action; { cancelled++ } },
                onTimeout = { timedOut++ },
            )
            ReflectionHelpers.setField(controller, "wiredStartWatchdog", replacement)
            replacement.startSessionSent()
        }
    }

    @Test fun handshakeFailureFromARetiredRunCannotFailItsReplacement() {
        WiredLifetimeUsb.invalidVersion = true
        val statuses = mutableListOf<CarPlayStatus>()
        atBoundary({ WiredLifetimeUsb.onVersionRead = it }, statuses) { controller ->
            restartDiscovery(controller)
        }
        assertTrue(statuses.filterIsInstance<CarPlayStatus.Failed>().isEmpty())
        assertEquals(listOf("usb", "ncm"), WiredLifetimeUsb.closes)
    }

    @Test fun currentConnectedTransfersUsbOwnershipToTheController() {
        atBoundary({ WiredLifetimeUsb.onPairing = it }) { controller ->
            assertNotNull(ReflectionHelpers.getField<Any?>(controller, "mux"))
            assertTrue(WiredLifetimeUsb.closes.isEmpty())
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
            assertEquals(listOf("usb"), WiredLifetimeUsb.closes)
        }
        assertEquals(1, WiredLifetimeUsb.pairingCalls.get())
        assertEquals(listOf("usb", "ncm"), WiredLifetimeUsb.closes)
    }

    @Test fun ncmOpenFailureReleasesUsbAndKeepsTheOriginalFailure() {
        WiredLifetimeUsb.failNcmOpen = true
        val statuses = mutableListOf<CarPlayStatus>()
        withController(statuses) { controller ->
            open(controller)
            worker(controller).submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("usb"), WiredLifetimeUsb.closes)
            assertEquals("Could not open the iPhone NCM connection", statuses.filterIsInstance<CarPlayStatus.Failed>().single().message)
        }
    }

    @Test fun failedMuxFactoryReleasesUsbBeforeNcmWithoutPublishingAnOwner() {
        WiredLifetimeUsb.invalidVersion = true
        val statuses = mutableListOf<CarPlayStatus>()
        withController(statuses) { controller ->
            open(controller)
            worker(controller).submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(ReflectionHelpers.getField<Any?>(controller, "mux"))
            assertEquals(0, WiredLifetimeUsb.pairingCalls.get())
            assertEquals(listOf("usb", "ncm"), WiredLifetimeUsb.closes)
            assertTrue(statuses.filterIsInstance<CarPlayStatus.Failed>().single().message.startsWith("Invalid USBMUX version reply"))
        }
    }

    private fun atBoundary(install: (() -> Unit) -> Unit, statuses: MutableList<CarPlayStatus> = mutableListOf(),
        afterResult: (CarPlayController) -> Unit = {},
        whileBlocked: (CarPlayController) -> Unit) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        install {
            reached.countDown()
            // Android USB opens need not stop when shutdownNow interrupts the worker.
            var interrupted = false
            try {
                while (true) {
                    try {
                        check(release.await(10, TimeUnit.SECONDS)) { "USB boundary was not released" }
                        break
                    } catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        withController(statuses) { controller ->
            try {
                open(controller)
                assertTrue("Real USB callback did not reach the selected boundary", reached.await(5, TimeUnit.SECONDS))
                whileBlocked(controller)
            } finally {
                release.countDown()
                val worker = worker(controller)
                if (worker.isShutdown) assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
                else worker.submit {}.get(5, TimeUnit.SECONDS)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(ReflectionHelpers.getField<Any?>(controller, "mux"))
            afterResult(controller)
        }
    }

    private fun withController(statuses: MutableList<CarPlayStatus> = mutableListOf(), check: (CarPlayController) -> Unit) {
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add,
            loadPairRecord = {
                WiredLifetimeUsb.pairingCalls.incrementAndGet()
                WiredLifetimeUsb.onPairing()
                throw IOException("Stop after the USB ownership probe")
            },
        )
        try { check(controller) } finally {
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
            assertTrue(worker(controller).awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun worker(controller: CarPlayController): ExecutorService = ReflectionHelpers.getField(controller, "executor")

    private fun open(controller: CarPlayController) {
        controller.javaClass.getDeclaredMethod("openDataPaths", UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(controller, device())
    }

    private fun restartDiscovery(controller: CarPlayController) {
        controller.javaClass.getDeclaredMethod("startIphone").apply { isAccessible = true }.invoke(controller)
        controller.javaClass.getDeclaredField("phase").apply {
            isAccessible = true
            set(controller, type.enumConstants!!.single { (it as Enum<*>).name == "DATAPATHS" })
        }
    }

    private fun device(): UsbDevice {
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address), ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512), ClassParameter.from(Int::class.javaPrimitiveType, 0))
        fun usbInterface(id: Int, alternate: Int, clazz: Int, subclass: Int, protocol: Int, vararg addresses: Int) =
            ReflectionHelpers.newInstance(UsbInterface::class.java).apply {
                ReflectionHelpers.setField(this, "mId", id)
                ReflectionHelpers.setField(this, "mAlternateSetting", alternate)
                ReflectionHelpers.setField(this, "mClass", clazz)
                ReflectionHelpers.setField(this, "mSubclass", subclass)
                ReflectionHelpers.setField(this, "mProtocol", protocol)
                ReflectionHelpers.setField(this, "mEndpoints", addresses.map(::endpoint).toTypedArray())
            }
        val configuration = ReflectionHelpers.newInstance(UsbConfiguration::class.java).apply {
            ReflectionHelpers.setField(this, "mId", 6)
            ReflectionHelpers.setField(this, "mInterfaces", arrayOf(
                usbInterface(1, 0, 0xff, 0xfe, 2, 0x04, 0x85),
                usbInterface(2, 0, 2, 0x0d, 0), usbInterface(3, 1, 0x0a, 0, 0, 0x06, 0x87)))
        }
        return ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "iphone")
            ReflectionHelpers.setField(this, "mVendorId", 0x05ac)
            ReflectionHelpers.setField(this, "mProductId", 0x12a8)
            ReflectionHelpers.setField(this, "mConfigurations", arrayOf(configuration))
        }
    }
}

object WiredLifetimeUsb {
    val opens = AtomicInteger()
    val pairingCalls = AtomicInteger()
    val closes = java.util.concurrent.CopyOnWriteArrayList<String>()
    val connections = IdentityHashMap<UsbDeviceConnection, String>()
    val completions = LinkedBlockingQueue<ByteArray>()
    var failNcmOpen = false
    var invalidVersion = false
    var onUsbClaim: () -> Unit = {}
    var onNcmSelected: () -> Unit = {}
    var onMuxSetup: () -> Unit = {}
    var onVersionRead: () -> Unit = {}
    var onPairing: () -> Unit = {}
    @Volatile var request: UsbRequest? = null
    @Volatile var buffer: ByteBuffer? = null
    fun reset() {
        opens.set(0); pairingCalls.set(0); closes.clear(); connections.clear(); completions.clear()
        failNcmOpen = false; invalidVersion = false
        onUsbClaim = {}; onNcmSelected = {}; onMuxSetup = {}; onVersionRead = {}; onPairing = {}
        request = null; buffer = null
    }
}

@Implements(UsbManager::class)
class WiredLifetimeUsbManagerShadow : ShadowUsbManager() {
    @Implementation override fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        val number = WiredLifetimeUsb.opens.incrementAndGet()
        if (number == 2 && WiredLifetimeUsb.failNcmOpen) return null
        return ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, device)).also {
            WiredLifetimeUsb.connections[it] = if (number == 1) "usb" else "ncm"
        }
    }
    @Implementation override fun hasPermission(device: UsbDevice): Boolean = true
}

@Implements(UsbDeviceConnection::class)
class WiredLifetimeUsbConnectionShadow {
    @RealObject lateinit var connection: UsbDeviceConnection
    @Implementation fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray, length: Int, timeout: Int): Int {
        buffer[0] = 6
        return 1
    }
    @Implementation fun setConfiguration(configuration: UsbConfiguration) = true
    @Implementation fun claimInterface(usbInterface: UsbInterface, force: Boolean): Boolean {
        if (usbInterface.id == 1) WiredLifetimeUsb.onUsbClaim()
        return true
    }
    @Implementation fun getRawDescriptors(): ByteArray = byteArrayOf()
    @Implementation fun setInterface(usbInterface: UsbInterface): Boolean {
        WiredLifetimeUsb.onNcmSelected()
        return true
    }
    @Implementation fun releaseInterface(usbInterface: UsbInterface) = true
    @Implementation fun close() { WiredLifetimeUsb.closes += WiredLifetimeUsb.connections.getValue(connection) }
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        if (buffer[3].toInt() == 0) {
            WiredLifetimeUsb.completions.add(ByteArray(20).also {
                it[3] = if (WiredLifetimeUsb.invalidVersion) 1 else 0
                it[7] = 20; it[11] = 2
            })
        } else WiredLifetimeUsb.onMuxSetup()
        return length
    }
    @Implementation fun requestWait(timeout: Long): UsbRequest {
        val bytes = WiredLifetimeUsb.completions.poll(timeout, TimeUnit.MILLISECONDS) ?: throw TimeoutException()
        WiredLifetimeUsb.buffer!!.put(bytes)
        if (bytes.isNotEmpty()) WiredLifetimeUsb.onVersionRead()
        return WiredLifetimeUsb.request!!
    }
}

@Implements(UsbRequest::class)
class WiredLifetimeUsbRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        WiredLifetimeUsb.request = request
        WiredLifetimeUsb.buffer = buffer
        return true
    }
    @Implementation fun cancel(): Boolean { WiredLifetimeUsb.completions.offer(byteArrayOf()); return true }
    @Implementation fun close() = Unit
}
