package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import com.ecarx.xui.adaptapi.input.Input
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.EcarxSwKey
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.network.GeelyHeadUnit
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxSwKeyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = EcarxSwKey.prefs(context)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun eightActionsMirrorTheVendorBox() {
        assertEquals(8, EcarxSwKey.Action.entries.size)
        assertEquals(
            listOf("voice", "prev", "next", "playpause", "pause", "play", "answer", "hangup"),
            EcarxSwKey.Action.entries.map { it.prefKey },
        )
    }

    @Test
    fun learntKeyIsAssignedAndConflictingActionIsCleared() {
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.NEXT, 200087)
        assertEquals(200087, EcarxSwKey.learntKeyCode(prefs, EcarxSwKey.Action.NEXT))
        // Assign the same key to another action; the reference app removes it from the previous one.
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.PREV, 200087)
        assertEquals(-1, EcarxSwKey.learntKeyCode(prefs, EcarxSwKey.Action.NEXT))
        assertEquals(200087, EcarxSwKey.learntKeyCode(prefs, EcarxSwKey.Action.PREV))
    }

    @Test
    fun learntVoiceKeyOpensSiri() {
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.VOICE, 0x77)
        val press = EcarxSwKey.resolve(prefs, 0x77)!!
        assertTrue(press.isSiri)
        assertEquals(EcarxSwKey.Action.VOICE, press.action)
    }

    @Test
    fun learntAnswerAndHangupMapToTelephonyPresses() {
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.ANSWER, 200400)
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.HANGUP, 200401)
        val answer = EcarxSwKey.resolve(prefs, 200400)!!
        assertEquals(CarPlayMediaButton.ANSWER, answer.callIndex)
        val hangup = EcarxSwKey.resolve(prefs, 200401)!!
        assertEquals(CarPlayMediaButton.HANGUP, hangup.callIndex)
    }

    @Test
    fun standardCallKeysConvertOnlyWhenTheGateIsOn() {
        // Default: call keys are left to the system (swMediaKeyCallConvert is off).
        assertNull(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_CALL))
        EcarxSwKey.setCallKeyConvert(prefs, true)
        assertEquals(CarPlayMediaButton.ANSWER, EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_CALL)?.callIndex)
        assertEquals(CarPlayMediaButton.HANGUP, EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_ENDCALL)?.callIndex)
    }

    @Test
    fun mediaKeysRespectTheMediaButtonGate() {
        // swMediaButton defaults on.
        assertEquals(CarPlayMediaButton.NEXT, EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_MEDIA_NEXT)?.mediaIndex)
        EcarxSwKey.setMediaButtonEnabled(prefs, false)
        assertNull(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun unknownKeysResolveToNull() {
        assertNull(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_MEDIA_NEXT)!!.isCall)
    }
}

/** The real CarPlay consumer, separate from the shared adapter's OEM reflection fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxSwKeyHostFlowTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private val context get() = RuntimeEnvironment.getApplication()
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before fun prepare() {
        Input.reset()
        EcarxSwKey.prefs(context).edit().clear().commit()
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.ecarx.xui.service"
        })
        audio.mode = AudioManager.MODE_NORMAL
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controller = mock(CarPlayController::class.java)
        `when`(controller.requestSiri()).thenReturn(true)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        `when`(controller.sendTelephonyPress(anyInt())).thenReturn(true)
        field("controller", controller)
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun cleanup() {
        invoke("unregisterEcarxSteeringWheel")
        Input.reset()
        field("activeAirPlaySession", null)
        field("controller", null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(activity) as ExecutorService).shutdownNow()
        }
        EcarxSwKey.prefs(context).edit().clear().commit()
        audio.mode = AudioManager.MODE_NORMAL
    }

    @Test fun foregroundDownRepeatAndReleaseSendOneRealBusinessCommand() {
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, 200087, 0)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 2, KeyEvent.ACTION_DOWN, 200087, 1)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 3, KeyEvent.ACTION_UP, 200087, 0)))
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        verifyNoMoreInteractions(controller)
    }

    @Test fun oemRegistrationPressReleaseAndStopReachTheRealCarPlayConsumer() {
        invoke("registerEcarxSteeringWheel")
        assertEquals(1, Input.registrations.size)
        assertEquals(listOf(true), Input.press(200087))
        assertEquals(listOf(false), Input.release(200087))
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        invoke("unregisterEcarxSteeringWheel")
        assertEquals(1, Input.releases)
        assertTrue(Input.press(200087).isEmpty())
        send(VOICE)
        verifyNoMoreInteractions(controller)
    }

    @Test fun repeatedRegistrationReplacesTheHandleAndBothBroadcastReceivers() {
        invoke("registerEcarxSteeringWheel")
        invoke("registerEcarxSteeringWheel")
        assertEquals(2, Input.registrations.size)
        assertEquals(1, Input.releases)
        assertEquals(listOf(true), Input.press(200087))
        send(VOICE)
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller, times(1)).requestSiri()
        verifyNoMoreInteractions(controller)
    }

    @Test fun endedSessionRejectsAnOemCallbackAlreadyInFlightAtStop() {
        invoke("registerEcarxSteeringWheel")
        val inFlight = Input.registrations.single().callback
        invoke("unregisterEcarxSteeringWheel")
        field("activeAirPlaySession", null)
        assertFalse(inFlight.onKeyPressed(200087))
        shadowOf(Looper.getMainLooper()).idle()
        verifyNoInteractions(controller)
    }

    @Test fun oemCallbackAlreadyInFlightAtStopCannotDispatchEvenIfTheSessionRemainsLive() {
        invoke("registerEcarxSteeringWheel")
        val inFlight = Input.registrations.single().callback
        invoke("unregisterEcarxSteeringWheel")
        assertFalse(inFlight.onKeyPressed(200087))
        shadowOf(Looper.getMainLooper()).idle()
        verifyNoInteractions(controller)
    }

    @Test fun wheelConsumerRoutesMediaSiriAndLearntTelephonyToTheController() {
        EcarxSwKey.assign(EcarxSwKey.prefs(context), EcarxSwKey.Action.ANSWER, 200400)
        EcarxSwKey.assign(EcarxSwKey.prefs(context), EcarxSwKey.Action.HANGUP, 200401)
        assertTrue(wheel(200088))
        assertTrue(wheel(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_VOICE_ASSIST))
        assertTrue(wheel(200400))
        assertTrue(wheel(200401))
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        verify(controller).requestSiri()
        verify(controller).sendTelephonyPress(CarPlayMediaButton.ANSWER)
        verify(controller).sendTelephonyPress(CarPlayMediaButton.HANGUP)
        verifyNoMoreInteractions(controller)
    }

    @Test fun endedSessionAndNativeCallSuppressTheConsumer() {
        field("activeAirPlaySession", null)
        assertFalse(wheel(200087))
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
        audio.mode = AudioManager.MODE_IN_CALL
        assertFalse(wheel(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(wheel(200087))
        verifyNoInteractions(controller)
    }

    @Test fun controllerRefusalAndUnknownKeyReturnFalse() {
        `when`(controller.sendMediaButton(CarPlayMediaButton.NEXT)).thenReturn(false)
        assertFalse(wheel(200087))
        assertFalse(wheel(200164))
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verifyNoMoreInteractions(controller)
    }

    @Test fun broadcastsReachRealSiriAndCallConsumerAndUnregisterStopsDelivery() {
        invoke("registerEcarxSteeringWheel")
        send(VOICE)
        send(CALL) // call conversion defaults off
        verify(controller).requestSiri()
        verify(controller, never()).sendTelephonyPress(anyInt())
        EcarxSwKey.setCallKeyConvert(EcarxSwKey.prefs(context), true)
        send(CALL)
        verify(controller).sendTelephonyPress(CarPlayMediaButton.ANSWER)
        invoke("unregisterEcarxSteeringWheel")
        invoke("unregisterEcarxSteeringWheel")
        send(VOICE)
        send(CALL)
        verifyNoMoreInteractions(controller)
    }

    @Test fun broadcastsAfterSessionEndDoNotReachTheController() {
        invoke("registerEcarxSteeringWheel")
        field("activeAirPlaySession", null)
        send(VOICE)
        send(CALL)
        verifyNoInteractions(controller)
    }

    @Test fun lateBroadcastAfterUnregisterCannotRunEvenWhenSessionIsLive() {
        EcarxSwKey.setCallKeyConvert(EcarxSwKey.prefs(context), true)
        invoke("registerEcarxSteeringWheel")
        val inFlight = CarPlayHostActivity::class.java.getDeclaredField("ecarxVoiceKeyReceiver")
            .apply { isAccessible = true }.get(activity) as BroadcastReceiver
        val inFlightCall = CarPlayHostActivity::class.java.getDeclaredField("ecarxCallKeyReceiver")
            .apply { isAccessible = true }.get(activity) as BroadcastReceiver
        invoke("unregisterEcarxSteeringWheel")
        inFlight.onReceive(activity, Intent(VOICE))
        inFlightCall.onReceive(activity, Intent(CALL))
        shadowOf(Looper.getMainLooper()).idle()
        verifyNoInteractions(controller)
    }

    @Test fun unresolvedDeviceContract_bluetoothPreferenceDoesNotGateTheEcarxConsumerItself() {
        // Device evidence must decide whether this ECARX route should share the Geely audio gate.
        val original = AirPlayPersistence.loadCarBluetoothAudio(context)
        try {
            AirPlayPersistence.saveCarBluetoothAudio(context, true)
            assertTrue(wheel(200087))
            verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        } finally {
            AirPlayPersistence.saveCarBluetoothAudio(context, original)
        }
    }

    private fun field(name: String, value: Any?) {
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun invoke(name: String) {
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    private fun wheel(code: Int): Boolean = CarPlayHostActivity::class.java
        .getDeclaredMethod("handleEcarxWheelKey", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(activity, code) as Boolean

    private fun send(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val VOICE = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
        const val CALL = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"
    }
}

/** Completes the actual Bluetooth double-fire boundary through the production AIDL consumer. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], manifest = Config.NONE,
    instrumentedPackages = ["com.shilapi.xcertplay.network.GeelyHeadUnit"],
)
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxSwKeyBluetoothFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var serviceContext: WheelServiceContext
    private lateinit var controller: CarPlayController
    private var originalBluetoothAudio = false
    private lateinit var originalProduct: String

    @Before fun prepare() {
        originalProduct = android.os.Build.PRODUCT
        ShadowBuild.setProduct("geely_fx11")
        // Separate sandbox and early Build setup keep the cached detection independent of other suites.
        assertTrue("Geely fixture must enter the real device detection path", GeelyHeadUnit.detected)
        GeelyWheelKeys.stop()
        originalBluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(context)
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        `when`(controller.requestSiri()).thenReturn(true)
        serviceContext = WheelServiceContext(context)
    }

    @After fun cleanup() {
        CarPlayMediaKeys.detach(controller)
        GeelyWheelKeys.stop()
        AirPlayPersistence.saveCarBluetoothAudio(context, originalBluetoothAudio)
        ShadowBuild.setProduct(originalProduct)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun bluetoothRegistersOnlyVoiceAndLongPressReachesSiriOnce() {
        AirPlayPersistence.saveCarBluetoothAudio(context, true)
        CarPlayMediaKeys.attach(serviceContext, controller)
        assertEquals(listOf(200231), serviceContext.input.keys.toList())
        serviceContext.input.shortPress(200087)
        serviceContext.input.shortPress(200085)
        val callback = requireNotNull(serviceContext.input.listener)
        callback.onKeyCodeEvent(200231, 0, 0)
        callback.onHoldingPressStarted(200231, 0)
        callback.onShortClick(200231, 0)
        callback.onDoubleClick(200231, 0)
        callback.onHoldingPressStopped(200231, 0)
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller, never()).requestSiri()
        callback.onLongPressTriggered(200231, 0)
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller, times(1)).requestSiri()
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun withoutBluetoothShortPressesReachRealMediaCallbackAndDetachUnregisters() {
        AirPlayPersistence.saveCarBluetoothAudio(context, false)
        CarPlayMediaKeys.attach(serviceContext, controller)
        assertEquals(listOf(200085, 200087, 200088, 200231), serviceContext.input.keys.toList())
        serviceContext.input.shortPress(200085)
        serviceContext.input.shortPress(200087)
        serviceContext.input.shortPress(200088)
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        val late = requireNotNull(serviceContext.input.listener)
        CarPlayMediaKeys.detach(controller)
        assertEquals(1, serviceContext.input.unregisters)
        assertEquals(1, serviceContext.unbinds)
        serviceContext.input.shortPress(200087)
        late.onShortClick(200087, 0)
        late.onLongPressTriggered(200231, 0)
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller, never()).requestSiri()
    }

    @Test fun attachingSameControllerTwiceDoesNotRegisterAnotherListener() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        CarPlayMediaKeys.attach(serviceContext, controller)
        assertEquals(1, serviceContext.binds)
        assertEquals(1, serviceContext.input.registers)
    }

    @Test fun serviceUnavailabilityAndPermissionFailureDoNotEscapeAttach() {
        serviceContext.bindFailure = SecurityException("OEM service permission denied")
        CarPlayMediaKeys.attach(serviceContext, controller)
        assertEquals(0, serviceContext.input.registers)
        serviceContext.bindFailure = null
        serviceContext.bindAvailable = false
        GeelyWheelKeys.start(serviceContext)
        assertEquals(0, serviceContext.input.registers)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun listenerRegistrationFailureStillAllowsCleanup() {
        serviceContext.input.registrationFailure = SecurityException("OEM register denied")
        CarPlayMediaKeys.attach(serviceContext, controller)
        assertEquals(1, serviceContext.input.registers)
        CarPlayMediaKeys.detach(controller)
        assertEquals(1, serviceContext.input.unregisters)
        assertEquals(1, serviceContext.unbinds)
        verify(controller, never()).sendMediaButton(anyInt())
    }

    @Test fun unexpectedDisconnectReconnectsAfterTheProductionRetryDelay() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        serviceContext.connection!!.onServiceDisconnected(serviceContext.component)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999))
        assertEquals(1, serviceContext.binds)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(2, serviceContext.binds)
        assertEquals(2, serviceContext.input.registers)
    }

    @Test fun disconnectArrivingAfterStopCannotScheduleAnotherBind() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        val lateConnection = requireNotNull(serviceContext.connection)
        CarPlayMediaKeys.detach(controller)
        lateConnection.onServiceDisconnected(serviceContext.component)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, serviceContext.binds)
        assertEquals(1, serviceContext.input.registers)
    }

    @Test fun stopCancelsAPendingDisconnectRetry() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        serviceContext.connection!!.onServiceDisconnected(serviceContext.component)
        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, serviceContext.binds)
    }

    @Test fun stoppedConnectionAndListenerCannotAffectARestartedSession() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        val oldConnection = requireNotNull(serviceContext.connection)
        val oldListener = requireNotNull(serviceContext.input.listener)
        CarPlayMediaKeys.detach(controller)
        CarPlayMediaKeys.attach(serviceContext, controller)
        oldConnection.onServiceDisconnected(serviceContext.component)
        oldConnection.onServiceConnected(serviceContext.component, serviceContext.input)
        oldListener.onShortClick(200087, 0)
        oldListener.onLongPressTriggered(200231, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(2, serviceContext.binds)
        assertEquals(2, serviceContext.input.registers)
        verify(controller, never()).sendMediaButton(anyInt())
        verify(controller, never()).requestSiri()
    }

    @Test fun queuedKeysCannotCrossStopAndRestart() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        val callback = requireNotNull(serviceContext.input.listener)
        callback.onShortClick(200087, 0)
        callback.onLongPressTriggered(200231, 0)
        CarPlayMediaKeys.detach(controller)
        CarPlayMediaKeys.attach(serviceContext, controller)
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller, never()).sendMediaButton(anyInt())
        verify(controller, never()).requestSiri()
    }

    @Test fun directStopRejectsOldKeysAndConnectionsAfterANewControllerAttaches() {
        AirPlayPersistence.saveCarBluetoothAudio(context, false)
        val previous = controller
        CarPlayMediaKeys.attach(serviceContext, previous)
        val oldConnection = requireNotNull(serviceContext.connection)
        val oldListener = requireNotNull(serviceContext.input.listener)
        oldListener.onShortClick(200087, 0)
        GeelyWheelKeys.stop()
        controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(serviceContext, controller)
        oldConnection.onServiceDisconnected(serviceContext.component)
        oldConnection.onServiceConnected(serviceContext.component, serviceContext.input)
        oldListener.onShortClick(200087, 0)
        oldListener.onLongPressTriggered(200231, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(2, serviceContext.binds)
        assertEquals(2, serviceContext.input.registers)
        verify(previous, never()).sendMediaButton(anyInt())
        verify(controller, never()).sendMediaButton(anyInt())
        verify(controller, never()).requestSiri()
        serviceContext.input.shortPress(200087)
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun binderCallbacksDoNotWaitForTheMediaKeysMonitor() {
        CarPlayMediaKeys.attach(serviceContext, controller)
        val callback = requireNotNull(serviceContext.input.listener)
        val returned = CountDownLatch(1)
        val binder = thread(start = false, name = "wheel-fixture-binder") {
            callback.onShortClick(200087, 0)
            callback.onLongPressTriggered(200231, 0)
            returned.countDown()
        }
        try {
            synchronized(CarPlayMediaKeys) {
                binder.start()
                assertTrue("Binder callback waited for the media consumer lock", returned.await(1, TimeUnit.SECONDS))
            }
        } finally {
            binder.join(2_000)
        }
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(controller).requestSiri()
    }

    private class WheelServiceContext(base: Context) : ContextWrapper(base) {
        val input = WheelInputService()
        val component = ComponentName("com.geely.inputservice", "com.geely.inputservice.InputService")
        var connection: ServiceConnection? = null
        var binds = 0
        var unbinds = 0
        var bindAvailable = true
        var bindFailure: RuntimeException? = null

        override fun getApplicationContext(): Context = this

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds += 1
            check(intent.component == component)
            bindFailure?.let { throw it }
            if (!bindAvailable) return false
            this.connection = connection
            connection.onServiceConnected(component, input)
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            check(connection === this.connection)
            unbinds += 1
        }
    }

    private class WheelInputService : IInputManager.Stub() {
        var keys = intArrayOf()
        var listener: IInputListener? = null
        var registers = 0
        var unregisters = 0
        var registrationFailure: RuntimeException? = null

        override fun interceptKeyCode(keyCode: Int, packageName: String) = false
        override fun releaseKeyCode(keyCode: Int, packageName: String) = false
        override fun getControlIndex() = 0

        override fun registerListener(listener: IInputListener, packageName: String, keyCodes: IntArray) {
            registers += 1
            registrationFailure?.let { throw it }
            this.listener = listener
            keys = keyCodes.clone()
        }

        override fun unregisterListener(listener: IInputListener, packageName: String) {
            unregisters += 1
            if (this.listener === listener) this.listener = null
        }

        fun shortPress(code: Int) {
            if (code in keys) listener?.onShortClick(code, 0)
        }
    }
}
