package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BluetoothProfileGuardRestoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay_bt_profile_guard", Context.MODE_PRIVATE)

    @Before fun setup() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        prefs.edit().clear().putBoolean("applied", true).putString("applied_mode", "adapter").commit()
    }

    @After fun cleanup() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        prefs.edit().clear().commit()
    }

    private fun restore(): String {
        val done = CountDownLatch(1)
        var result = ""
        CarBluetoothProfileGuard.restore(context) {
            result = it
            done.countDown()
        }
        assertTrue("restore worker must finish within bounded observation", done.await(8, TimeUnit.SECONDS))
        return result
    }

    @Test fun confirmedStateOnClearsRecoveryMarker() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        val result = restore()
        assertFalse(prefs.getBoolean("applied", false))
        assertTrue(result.contains("STATE_ON confirmed"))
    }

    @Test fun missingAdapterKeepsRecoveryMarker() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        val result = restore()
        assertTrue(prefs.getBoolean("applied", false))
        assertTrue(result.contains("keeping the state to retry"))
    }

    @Test fun turningOnIsNotConfirmationAndCanBeRetried() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_TURNING_ON)
        val pending = restore()
        assertTrue(pending.contains("TIMED_OUT"))
        assertTrue(prefs.getBoolean("applied", false))
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        assertTrue(restore().contains("STATE_ON confirmed"))
        assertFalse(prefs.getBoolean("applied", false))
    }
    private fun worker(): ScheduledExecutorService = CarBluetoothProfileGuard::class.java
        .getDeclaredField("worker").also { it.isAccessible = true }
        .get(CarBluetoothProfileGuard) as ScheduledExecutorService

    @Test fun duplicateRestoreCannotClearNewerRecoveryOwnership() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        val release = CountDownLatch(1)
        worker().execute { release.await(3, TimeUnit.SECONDS) }
        val oldLogs = AtomicInteger()
        val newLogs = AtomicInteger()
        try {
            CarBluetoothProfileGuard.restore(context) { oldLogs.incrementAndGet() }
            CarBluetoothProfileGuard.restore(context) { newLogs.incrementAndGet() }
        } finally { release.countDown() }
        worker().submit {}.get(3, TimeUnit.SECONDS)
        assertEquals(0, oldLogs.get())
        assertEquals(1, newLogs.get())
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun newerSessionCancelsQueuedRestoreWithoutClearingItsMarker() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        val release = CountDownLatch(1)
        worker().execute { release.await(3, TimeUnit.SECONDS) }
        val oldLogs = AtomicInteger()
        CarBluetoothProfileGuard.saveAdapterEnabled(context, true)
        CarBluetoothProfileGuard.saveAdapterDelaySeconds(context, 10)
        val restored = CountDownLatch(1)
        try {
            CarBluetoothProfileGuard.restore(context) { oldLogs.incrementAndGet() }
            CarBluetoothProfileGuard.yieldForSession(context, null) {
                if (it.contains("STATE_ON confirmed")) restored.countDown()
            }
            release.countDown()
            worker().submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(0, oldLogs.get())
            assertTrue(prefs.getBoolean("applied", false))
        } finally {
            release.countDown()
            // Cancel the delayed yield, then restore the state owned by this new session.
            CarBluetoothProfileGuard.restore(context)
            assertTrue(restored.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun userDisabledRadioIsNotEnabledAndBlocksConnectionWork() {
        prefs.edit().clear().commit()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        var connectionWork = false
        try {
            CarBluetoothProfileGuard.requireAdapterReadyForConnection(context, { true }) {}
            connectionWork = true
        } catch (_: java.io.IOException) { /* the production gate blocks hotspot/RFCOMM */ }
        assertFalse(connectionWork)
        assertEquals(BluetoothAdapter.STATE_OFF, adapter.state)
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun cancelledGenerationDoesNotRestoreOrStartConnectionWork() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        var connectionWork = false
        try {
            CarBluetoothProfileGuard.requireAdapterReadyForConnection(context, { false }) {}
            connectionWork = true
        } catch (_: java.io.InterruptedIOException) { }
        assertFalse(connectionWork)
        assertEquals(BluetoothAdapter.STATE_OFF, adapter.state)
        assertTrue(prefs.getBoolean("applied", false))
    }

    @Test fun unconfirmedEnableBlocksConnectionWorkAtDeadline() {
        // An in-flight platform enable must finish; merely TURNING_ON cannot start RFCOMM.
        prefs.edit().clear().commit()
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_TURNING_ON)
        var connectionWork = false
        try {
            CarBluetoothProfileGuard.requireAdapterReadyForConnection(context, { true }) {}
            connectionWork = true
        } catch (_: java.io.IOException) { }
        assertFalse(connectionWork)
    }

}
