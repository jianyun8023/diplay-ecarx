package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessBootstrapLifecycleTest {
    private lateinit var controller: CarPlayController
    private val statuses = mutableListOf<CarPlayStatus>()
    private val airPlayConfig = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")

    @Before fun setUp() {
        controller = CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        }, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            airPlayConfig, identity, PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, statuses::add)
        controller.wirelessRunForTest()
    }

    @After fun tearDown() {
        controller.close()
        controller.awaitClosed(2_000)
    }

    @Test fun bootstrapCloseInvalidatesAttemptAndReleasesOnlyBluetoothResources() {
        assertBluetoothOnlyCleanup(throwOnClose = false)
    }

    @Test fun bootstrapCloseContinuesAfterCsmAndStreamCloseFailures() {
        assertBluetoothOnlyCleanup(throwOnClose = true)
    }

    private fun assertBluetoothOnlyCleanup(throwOnClose: Boolean) {
        val attempt = newAttempt()
        val csm = mock(Iap2Session::class.java)
        val stream = mock(BluetoothRfcommDuplexStream::class.java)
        val socket = mock(BluetoothSocket::class.java)
        val hotspot = mock(WirelessHotspotManager::class.java)
        val bonjour = mock(CarPlayBonjour::class.java)
        val service = mock(CarPlayVpnService::class.java)
        val linkType = controller.javaClass.declaredClasses.single { it.simpleName == "InstantBluetoothLink" }
        val link = linkType.getDeclaredConstructor(Int::class.javaPrimitiveType, BluetoothDevice::class.java,
            BluetoothRfcommDuplexStream::class.java, Long::class.javaPrimitiveType, attempt.javaClass)
            .apply { isAccessible = true }.newInstance(0, mock(BluetoothDevice::class.java), stream, 0L, attempt)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", attempt)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "instantBluetoothLink", link)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "csm", csm)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bluetoothStream", stream)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bluetoothSocket", socket)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "hotspot", hotspot)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bonjour", bonjour)
        ReflectionHelpers.setField(controller, "vpnService", service)
        doAnswer {
            assertFalse("Attempt must be invalidated before resource cleanup", attemptIsCurrent(attempt))
            assertNull(ReflectionHelpers.getField<Any?>(controller.wirelessRunForTest(), "bootstrapAttempt"))
            if (throwOnClose) throw IOException("CSM close failed")
            null
        }.`when`(csm).close()
        if (throwOnClose) doAnswer { throw IOException("stream close failed") }.`when`(stream).close()

        assertTrue(attemptIsCurrent(attempt))
        closeBootstrap()
        closeBootstrap() // Already-cleared fields must not be closed twice.

        assertFalse(attemptIsCurrent(attempt))
        for (name in listOf("bootstrapAttempt", "instantBluetoothLink", "csm", "bluetoothStream", "bluetoothSocket")) {
            assertNull("$name was retained", ReflectionHelpers.getField<Any?>(controller.wirelessRunForTest(), name))
        }
        val order = inOrder(csm, stream, socket)
        order.verify(csm).close()
        order.verify(stream).close()
        order.verify(socket).close()
        verifyNoMoreInteractions(csm, stream, socket)
        assertSame(hotspot, ReflectionHelpers.getField(controller.wirelessRunForTest(), "hotspot"))
        assertSame(bonjour, ReflectionHelpers.getField(controller.wirelessRunForTest(), "bonjour"))
        assertSame(service, ReflectionHelpers.getField(controller, "vpnService"))
        verifyNoInteractions(hotspot, bonjour, service)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    @Test fun queuedOldBootstrapStatusCannotEscapeAnInvalidatedAttempt() {
        val oldAttempt = newAttempt()
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", oldAttempt)
        bootstrapStatus(CarPlayStatus.ConnectingBluetooth, 0, oldAttempt)
        assertTrue("Bootstrap status must be queued until the main looper runs", statuses.isEmpty())

        oldAttempt.javaClass.getDeclaredMethod("invalidate").apply { isAccessible = true }.invoke(oldAttempt)
        val replacement = newAttempt()
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", replacement)
        assertFalse(attemptIsCurrent(oldAttempt))
        assertTrue(attemptIsCurrent(replacement))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("An old attempt's queued status reached the UI", statuses.isEmpty())

        bootstrapStatus(CarPlayStatus.RunningWireless, 0, replacement)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(CarPlayStatus.RunningWireless), statuses)
    }

    @Test fun queuedCurrentBootstrapStatusIsDeliveredOnTheMainLooper() {
        val attempt = newAttempt()
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", attempt)
        bootstrapStatus(CarPlayStatus.ConnectingBluetooth, 0, attempt)
        assertTrue(statuses.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(CarPlayStatus.ConnectingBluetooth), statuses)
    }

    @Test fun queuedBootstrapStatusCannotEscapeAnOldWirelessGeneration() {
        val attempt = newAttempt()
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", attempt)
        bootstrapStatus(CarPlayStatus.ConnectingBluetooth, 0, attempt)
        controller.replaceWirelessRunForTest(1)
        // The attempt token stays valid; retiring its run must still reject queued progress.
        assertTrue(attemptIsCurrent(attempt))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("An old generation's queued bootstrap status reached the UI", statuses.isEmpty())

        bootstrapStatus(CarPlayStatus.RunningWireless, 1, attempt)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(CarPlayStatus.RunningWireless), statuses)
    }

    @Test fun cancellingConnectClosesSocketAndRejectsLateSuccessfulConnect() {
        val socket = mock(BluetoothSocket::class.java)
        val current = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connectWorker = AtomicReference<Thread?>()
        val outcome = AtomicReference<Throwable?>()
        val returnedSuccessfully = AtomicBoolean(false)
        doAnswer {
            connectWorker.set(Thread.currentThread())
            entered.countDown()
            // Model a vendor connect() that ignores socket.close() and returns success later.
            if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Test did not release connect")
            null
        }.`when`(socket).connect()
        val caller = Thread({
            try {
                connect(socket) { current.get() }
                returnedSuccessfully.set(true)
            } catch (error: InvocationTargetException) {
                outcome.set(error.cause)
            } catch (error: Throwable) {
                outcome.set(error)
            }
        }, "test-bootstrap-connect-caller").apply { isDaemon = true }
        try {
            caller.start()
            assertTrue("connect() did not start", entered.await(2, TimeUnit.SECONDS))
            current.set(false)
            caller.join(2_000)
            assertFalse("Cancellation waited for the 8s connect timeout", caller.isAlive)
            assertTrue("Expected cancellation, got ${outcome.get()}", outcome.get() is CancellationException)
            assertFalse(returnedSuccessfully.get())
            verify(socket, times(1)).close()

            release.countDown()
            val worker = connectWorker.get()!!
            worker.join(2_000)
            assertFalse("Late connect worker did not finish", worker.isAlive)
            // A stale successful return must close again instead of resurrecting the socket.
            verify(socket, times(2)).close()
            assertFalse(returnedSuccessfully.get())
            assertNull(ReflectionHelpers.getField<Any?>(controller.wirelessRunForTest(), "bluetoothSocket"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally {
            current.set(false)
            release.countDown()
            caller.interrupt()
            caller.join(2_000)
            connectWorker.get()?.join(2_000)
        }
    }

    @Test fun currentSuccessfulConnectDoesNotCloseSocket() {
        val socket = mock(BluetoothSocket::class.java)
        connect(socket) { true }
        verify(socket).connect()
        verify(socket, never()).close()
    }

    @Test fun targetValidationStopsWhenBluetoothIsOffEvenIfPhoneRemainsBonded() {
        val adapter = mock(BluetoothAdapter::class.java)
        val phone = mock(BluetoothDevice::class.java)
        `when`(adapter.state).thenReturn(BluetoothAdapter.STATE_OFF)
        `when`(adapter.bondedDevices).thenReturn(setOf(phone))
        val failure = validationFailure(adapter, phone)
        assertTrue(failure is IOException)
        assertEquals(BluetoothRadioCheck.RADIO_OFF, failure.message)
        verify(adapter, never()).getBondedDevices()
        verifyNoInteractions(phone)
    }

    @Test fun targetValidationStopsWhenSelectedPhoneIsNoLongerBonded() {
        val adapter = mock(BluetoothAdapter::class.java)
        val phone = mock(BluetoothDevice::class.java)
        val other = mock(BluetoothDevice::class.java)
        `when`(adapter.state).thenReturn(BluetoothAdapter.STATE_ON)
        `when`(phone.address).thenReturn("00:11:22:33:44:01")
        `when`(other.address).thenReturn("00:11:22:33:44:02")
        for (bonded in listOf(emptySet(), setOf(other))) {
            `when`(adapter.bondedDevices).thenReturn(bonded)
            val failure = validationFailure(adapter, phone)
            assertTrue(failure is ConnectionFailureException)
            assertEquals(ConnectionFailureCause.NO_PAIRED_IPHONE, (failure as ConnectionFailureException).reason)
            assertEquals("Selected Bluetooth target is no longer paired", failure.message)
        }
        verify(phone, never()).createRfcommSocketToServiceRecord(any())
    }

    @Test fun targetValidationAcceptsAnEnabledAdapterWithTheSelectedBond() {
        val adapter = mock(BluetoothAdapter::class.java)
        val phone = mock(BluetoothDevice::class.java)
        `when`(adapter.state).thenReturn(BluetoothAdapter.STATE_ON)
        `when`(adapter.bondedDevices).thenReturn(setOf(phone))
        `when`(phone.address).thenReturn("00:11:22:33:44:01")
        validateTarget(adapter, phone)
        verify(phone, never()).createRfcommSocketToServiceRecord(any())
    }

    @Test fun permissionFailureDisablesWholeStackAutomaticRetry() {
        assertFalse(reportedFailure(IOException("Bluetooth bootstrap failed",
            SecurityException("Bluetooth connect permission denied"))).automaticRetryAllowed)
    }

    @Test fun radioOffFailureDisablesWholeStackAutomaticRetry() {
        assertFalse(reportedFailure(IOException("Bluetooth bootstrap failed",
            BluetoothRadioCheck.failure(BluetoothRadioCheck.RADIO_OFF))).automaticRetryAllowed)
    }

    @Test fun missingSelectedBondDisablesWholeStackAutomaticRetry() {
        val failure = reportedFailure(ConnectionFailureException(ConnectionFailureCause.NO_PAIRED_IPHONE,
            "Selected Bluetooth target is no longer paired"))
        assertFalse(failure.automaticRetryAllowed)
        assertEquals(ConnectionFailureCause.NO_PAIRED_IPHONE, failure.knownCause)
    }

    @Test fun ordinaryTransportFailureStillAllowsWholeStackAutomaticRetry() {
        assertTrue(reportedFailure(IOException("Bluetooth connection reset")).automaticRetryAllowed)
    }

    @Test fun retryableMfiTimeoutStillAllowsWholeStackAutomaticRetry() {
        assertTrue(reportedFailure(Iap2MfiAuthenticationException(
            "Timed out waiting for iAP2 MFi authentication", retryable = true)).automaticRetryAllowed)
    }

    @Test fun explicitMfiRejectionDisablesWholeStackAutomaticRetry() {
        assertFalse(reportedFailure(IOException("Bluetooth bootstrap failed",
            Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed"))).automaticRetryAllowed)
    }

    @Test fun watchdogTimeoutClosesOnlyItsAttemptAndPreservesTypedRetryCause() {
        setWirelessPhase()
        val attempt = newAttempt()
        val channel = mock(Iap2Session::class.java)
        val stream = mock(BluetoothRfcommDuplexStream::class.java)
        val hotspot = mock(WirelessHotspotManager::class.java)
        val bonjour = mock(CarPlayBonjour::class.java)
        val service = mock(CarPlayVpnService::class.java)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", attempt)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "csm", channel)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bluetoothStream", stream)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "hotspot", hotspot)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bonjour", bonjour)
        ReflectionHelpers.setField(controller, "vpnService", service)
        expireAttempt(attempt)
        assertFalse(attemptIsCurrent(attempt))
        assertNull(ReflectionHelpers.getField<Any?>(controller.wirelessRunForTest(), "bootstrapAttempt"))
        verify(channel).close()
        verify(stream).close()
        verifyNoInteractions(hotspot, bonjour, service)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("A local timeout must not fail the entire wireless run", statuses.isEmpty())
        val failure = attempt.javaClass.getDeclaredMethod("failure", Throwable::class.java)
            .invoke(attempt, CancellationException("watchdog closed channel")) as ConnectionFailureException
        assertEquals(ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, failure.reason)
        val reported = reportedFailure(failure)
        assertEquals(failure.reason, reported.knownCause)
        assertTrue(reported.automaticRetryAllowed)
    }

    @Test fun staleWatchdogCannotCloseAReplacementAttemptInTheSameWirelessRun() {
        setWirelessPhase()
        val old = newAttempt()
        val replacement = newAttempt()
        val channel = mock(Iap2Session::class.java)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", replacement)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "csm", channel)
        expireAttempt(old)
        assertSame(replacement, ReflectionHelpers.getField(controller.wirelessRunForTest(), "bootstrapAttempt"))
        assertTrue(attemptIsCurrent(replacement))
        verifyNoInteractions(channel)
    }

    @Test fun startSessionDispatchGuardsAgainstTimeoutBeforeSendCallback() {
        setWirelessPhase()
        val attempt = newAttempt()
        val stream = mock(BluetoothRfcommDuplexStream::class.java)
        val dispatched = AtomicBoolean(false)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", attempt)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bluetoothStream", stream)

        // This hook runs before 0x4301 is written; onStartSessionSent has not run yet.
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("beginStartSessionDispatch",
            Int::class.javaPrimitiveType, attempt.javaClass, AtomicBoolean::class.java,
            Function0::class.java).apply { isAccessible = true }
            .invoke(controller.wirelessRunForTest(), 0, attempt, dispatched, { Unit })
        assertTrue(dispatched.get())
        assertTrue(attemptIsCurrent(attempt))

        expireAttempt(attempt, dispatched = dispatched)
        assertSame(attempt, ReflectionHelpers.getField(controller.wirelessRunForTest(), "bootstrapAttempt"))
        assertTrue(attemptIsCurrent(attempt))
        verifyNoInteractions(stream)
    }

    @Test fun staleAttemptCannotBeginStartSessionDispatch() {
        setWirelessPhase()
        val old = newAttempt()
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", newAttempt())
        val dispatched = AtomicBoolean(false)
        val error = assertThrows(InvocationTargetException::class.java) {
            controller.wirelessRunForTest().javaClass.getDeclaredMethod("beginStartSessionDispatch",
                Int::class.javaPrimitiveType, old.javaClass, AtomicBoolean::class.java,
                Function0::class.java).apply { isAccessible = true }
                .invoke(controller.wirelessRunForTest(), 0, old, dispatched, { Unit })
        }
        assertTrue(error.cause is CancellationException)
        assertFalse(dispatched.get())
    }

    @Test fun watchdogCannotCloseAfterStartSessionOrHandoffOrGenerationReplacement() {
        setWirelessPhase()
        val attempt = newAttempt()
        val channel = mock(Iap2Session::class.java)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bootstrapAttempt", attempt)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "csm", channel)
        expireAttempt(attempt, sent = true)
        controller.wirelessStateCallForTest("requestHandoff")
        expireAttempt(attempt)
        controller.resetWirelessStateForTest()
        controller.wirelessStateCallForTest("retire")
        expireAttempt(attempt)
        assertSame(attempt, ReflectionHelpers.getField(controller.wirelessRunForTest(), "bootstrapAttempt"))
        assertTrue(attemptIsCurrent(attempt))
        verifyNoInteractions(channel)
    }

    private fun setWirelessPhase() {
        val phase = ReflectionHelpers.getField<Any>(controller, "phase")
        ReflectionHelpers.setField(controller, "phase", phase.javaClass.enumConstants.single {
            (it as Enum<*>).name == "WIRELESS"
        })
    }

    private fun expireAttempt(attempt: Any, sent: Boolean = false,
        dispatched: AtomicBoolean = AtomicBoolean(sent)) {
        val phaseType = com.shilapi.xcertplay.transport.Iap2BootstrapPhase::class.java
        val stallType = Class.forName("com.shilapi.xcertplay.orchestration.Iap2BootstrapStall")
        val stall = stallType.getDeclaredConstructor(phaseType, phaseType, Long::class.javaPrimitiveType)
            .newInstance(com.shilapi.xcertplay.transport.Iap2BootstrapPhase.MFi_AUTHENTICATED,
                com.shilapi.xcertplay.transport.Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE, 65_000L)
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("expireBootstrapAttempt", Int::class.javaPrimitiveType,
            attempt.javaClass, stallType, AtomicBoolean::class.java).apply { isAccessible = true }
            .invoke(controller.wirelessRunForTest(), 0, attempt, stall, dispatched)
    }

    private fun reportedFailure(error: Throwable): CarPlayStatus.Failed {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, 0)
        shadowOf(Looper.getMainLooper()).idle()
        return statuses.single() as CarPlayStatus.Failed
    }

    @Test fun retainedWirelessAttachmentRequiresExactOwnerAndAnOpenListenerSocket() {
        val serviceController = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = serviceController.get()
        val owner = AirPlayListenerIdentity(0)
        val anotherOwnerInSameGeneration = AirPlayListenerIdentity(0)
        try {
            assertFalse(service.isWirelessAttached(owner))
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                InetAddress.getByName("127.0.0.1"), airPlayConfig.copy(port = 0), identity, PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, listenerIdentity = owner))
            assertTrue(service.isWirelessAttached(owner))
            assertFalse(service.isWirelessAttached(anotherOwnerInSameGeneration))
            service.detachWireless(anotherOwnerInSameGeneration)
            assertTrue(service.isWirelessAttached(owner))
            // Hold the service monitor so the accept-error cleanup cannot clear active/attachment
            // first: this specifically exercises the closed-socket guard, not only active == false.
            synchronized(service) {
                ReflectionHelpers.getField<ServerSocket>(service, "serverSocket").close()
                assertTrue(service.isAttached())
                assertFalse(service.isWirelessAttached(owner))
            }
            service.detachWireless(owner)
            assertFalse(service.isWirelessAttached(owner))
        } finally {
            serviceController.destroy()
        }
    }

    // Attempt is internal to :shared; using its JVM constructor avoids a cross-module Kotlin dependency.
    private fun newAttempt(): Any = Class.forName(
        "com.shilapi.xcertplay.orchestration.WirelessBootstrapRecovery\$Attempt")
        .getDeclaredConstructor(Function0::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance({ true }, 1)

    private fun attemptIsCurrent(attempt: Any): Boolean = attempt.javaClass.getDeclaredMethod("isCurrent")
        .apply { isAccessible = true }.invoke(attempt) as Boolean

    private fun bootstrapStatus(status: CarPlayStatus, generation: Int, attempt: Any) {
        controller.javaClass.getDeclaredMethod("onBootstrapStatus", CarPlayStatus::class.java,
            Int::class.javaPrimitiveType, attempt.javaClass).apply { isAccessible = true }
            .invoke(controller, status, generation, attempt)
    }

    private fun closeBootstrap() {
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("closeBluetoothBootstrapTransport")
            .apply { isAccessible = true }.invoke(controller.wirelessRunForTest())
    }

    private val bluetooth: Any get() = controller.wirelessRunForTest().javaClass.getDeclaredField("wirelessBluetooth")
        .apply { isAccessible = true }.get(controller.wirelessRunForTest())

    private fun connect(socket: BluetoothSocket, isCurrent: () -> Boolean) {
        bluetooth.javaClass.getDeclaredMethod("connectBluetoothSocket", BluetoothSocket::class.java,
            String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(bluetooth, socket, "00:11:22:33:44:01", isCurrent)
    }

    private fun validateTarget(adapter: BluetoothAdapter, phone: BluetoothDevice) {
        bluetooth.javaClass.getDeclaredMethod("validateBluetoothTarget", BluetoothAdapter::class.java,
            BluetoothDevice::class.java).apply { isAccessible = true }.invoke(bluetooth, adapter, phone)
    }

    private fun validationFailure(adapter: BluetoothAdapter, phone: BluetoothDevice): Throwable =
        assertThrows(InvocationTargetException::class.java) { validateTarget(adapter, phone) }.cause!!
}
