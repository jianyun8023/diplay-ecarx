package com.shihab.diplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shihab.diplay.orchestration.CarPlayStatus
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessBootstrapHostRetryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private lateinit var wifiRecovery: Button
    private lateinit var stage: TextView
    private val terminalFailure = CarPlayStatus.Failed("Bluetooth permission denied", automaticRetryAllowed = false)

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        RuntimeEnvironment.getApplication().getSharedPreferences("diplay_connection_decisions", 0)
            .edit().clear().commit()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        retry = Button(activity).apply { visibility = View.GONE }
        wifiRecovery = Button(activity).apply { visibility = View.VISIBLE }
        stage = TextView(activity)
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
        ReflectionHelpers.setField(activity, "wifiRecoveryButton", wifiRecovery)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
    }

    @Test fun userActionFailureStopsAutomaticRetryWithoutConsumingRetryBudget() {
        // Neither startupFailure nor knownCause is set: the new flag alone must stop recovery.
        report(terminalFailure)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, budget().retries)
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(View.VISIBLE, retry.visibility)
        assertEquals(View.GONE, wifiRecovery.visibility)
    }

    @Test fun userActionFailurePreventsAnAlreadyQueuedRetryFromExecuting() {
        report(CarPlayStatus.Failed("Bluetooth connection reset"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))

        report(terminalFailure)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun cleanupFailureCannotRearmAutomaticRetryAfterUserActionIsRequired() {
        report(terminalFailure)
        val terminalStage = stage.text.toString()
        report(CarPlayStatus.Failed("Bluetooth socket closed"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(0, budget().retries)
        assertEquals(terminalStage, stage.text.toString())
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun defaultAutomaticRetryPermissionKeepsTransientFailureRecovery() {
        val transient = CarPlayStatus.Failed("Bluetooth connection reset")
        assertTrue(transient.automaticRetryAllowed)
        report(transient)
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        assertEquals(View.GONE, retry.visibility)
        assertEquals(View.GONE, wifiRecovery.visibility)
    }

    @Test fun terminalFailureWhileSettingsOpenSurvivesCleanupAndClosingWithoutReconnect() {
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report(terminalFailure)
        report(CarPlayStatus.Failed("cleanup socket closed"))
        assertSame(terminalFailure, ReflectionHelpers.getField(activity, "failurePendingAfterMenu"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        ReflectionHelpers.callInstanceMethod<Void>(activity, "finishSettingsMenu",
            from(String::class.java, "Settings closed"), from(Boolean::class.javaPrimitiveType, false))
        assertFalse(ReflectionHelpers.getField(activity, "menuOpen"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "failurePendingAfterMenu"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertEquals(View.VISIBLE, retry.visibility)
        assertTrue(ReflectionHelpers.getField<String>(activity, "latestStage").contains("Bluetooth permission denied"))
        assertEquals(activity.getString(com.shihab.diplay.host.R.string.allow_the_connection_permission_to_continue),
            stage.text.toString())
    }

    @Test fun terminalFailureInMenuStopsAPreviouslyQueuedRetryImmediately() {
        report(CarPlayStatus.Failed("connection reset"))
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report(terminalFailure)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertSame(terminalFailure, ReflectionHelpers.getField(activity, "failurePendingAfterMenu"))
    }

    private fun budget(): WirelessStartupRetryBudget = ReflectionHelpers.getField(activity, "startupRetryBudget")

    private fun report(status: CarPlayStatus) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, 0))(status)
    }
}
