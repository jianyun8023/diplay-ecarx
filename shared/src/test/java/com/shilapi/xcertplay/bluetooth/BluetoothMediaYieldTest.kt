package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
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
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], shadows = [BluetoothMediaYieldTest.SinkAdapter::class])
class BluetoothMediaYieldTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay_bt_profile_guard", Context.MODE_PRIVATE)
    private lateinit var adapter: BluetoothAdapter
    private lateinit var phone: BluetoothDevice
    private lateinit var other: BluetoothDevice
    private lateinit var sink: Sink
    private val logs = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        phone = adapter.getRemoteDevice("00:11:22:33:44:55")
        other = adapter.getRemoteDevice("00:11:22:33:44:66")
        shadowOf(adapter).setBondedDevices(setOf(phone, other))
        sink = Sink().apply { states[phone] = BluetoothProfile.STATE_CONNECTED }
        SinkAdapter.profile = sink
        SinkAdapter.requests.clear()
        SinkAdapter.closed.clear()
        SinkAdapter.acceptRequest = true
        SinkAdapter.callback = true
        SinkAdapter.listener = null
        SinkAdapter.powerChanges = 0
        prefs.edit().clear().commit()
        CarBluetoothProfileGuard.saveMediaOnlyEnabled(context, true)
    }

    @After fun cleanup() {
        SinkAdapter.acceptRequest = true
        SinkAdapter.callback = true
        sink.rejectPolicy = null
        restore()
        prefs.edit().clear().commit()
    }

    private fun awaitWorker() {
        val worker = CarBluetoothProfileGuard::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(CarBluetoothProfileGuard) as ScheduledExecutorService
        val end = worker.submit {}
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!end.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        end.get(1, TimeUnit.SECONDS)
    }

    private fun start(address: String? = phone.address, mediaAudioAllowed: Boolean = true) {
        CarBluetoothProfileGuard.yieldForSession(context, address, mediaAudioAllowed, logs::add)
        awaitWorker()
    }

    private fun restore() {
        CarBluetoothProfileGuard.restore(context, logs::add)
        awaitWorker()
    }

    @Test fun onlySelectedMediaProfileChangesAndExactOriginalPolicyReturns() {
        sink.states[other] = BluetoothProfile.STATE_DISCONNECTED
        sink.beforeWrite = {
            assertTrue("Journal must be durable before any policy mutation", prefs.getBoolean("applied", false))
            assertEquals(100, prefs.getInt("media_original_policy", -1))
            assertEquals(phone.address, prefs.getString("applied_targets", null))
        }
        start()
        assertEquals(listOf(phone to 0), sink.writes)
        assertEquals(listOf(phone), sink.disconnects)
        assertEquals(0, sink.getPriority(phone))
        assertEquals(100, sink.getPriority(other))
        assertTrue(prefs.getBoolean("applied", false))
        restore()
        assertEquals(listOf(phone to 0, phone to 100), sink.writes)
        assertEquals(listOf(phone), sink.connects)
        assertFalse(prefs.getBoolean("applied", false))
        assertEquals(listOf(11, 11), SinkAdapter.requests)
        assertEquals(SinkAdapter.requests, SinkAdapter.closed)
        assertEquals(0, SinkAdapter.powerChanges)
        assertTrue(adapter.isEnabled)
    }

    @Test fun modernConnectionPolicyApiIsUsed() {
        val modern = ModernSink().apply { states[phone] = BluetoothProfile.STATE_CONNECTED }
        sink = modern
        SinkAdapter.profile = modern
        start()
        restore()
        assertEquals(listOf(phone to 0, phone to 100), modern.writes)
        assertEquals(2, modern.modernWrites)
        assertTrue(modern.modernReads >= 4)
        assertEquals(listOf(phone), modern.disconnects)
        assertEquals(listOf(phone), modern.connects)
    }

    @Test fun unknownOrAutoConnectPolicyIsNeverOverwritten() {
        for (policy in listOf(-1, 1000, 37)) {
            sink.policies[phone] = policy
            start()
            restore()
            assertTrue(sink.writes.isEmpty())
            assertFalse(prefs.getBoolean("applied", false))
        }
    }

    @Test fun userDisabledMediaIsNotEnabledAtEnd() {
        sink.policies[phone] = 0
        start()
        restore()
        assertTrue(sink.writes.isEmpty())
        assertTrue(sink.disconnects.isEmpty())
        assertTrue(sink.connects.isEmpty())
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun initiallyDisconnectedPhoneIsNotExplicitlyReconnected() {
        sink.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        start()
        restore()
        assertEquals(listOf(phone to 0, phone to 100), sink.writes)
        assertTrue(sink.disconnects.isEmpty())
        assertTrue(sink.connects.isEmpty())
    }

    @Test fun transitionalConnectionIsLeftAlone() {
        sink.states[phone] = BluetoothProfile.STATE_CONNECTING
        start()
        assertTrue(sink.writes.isEmpty())
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun missingOrUnpairedSelectedPhoneDoesNotTouchOtherIphones() {
        start(null)
        restore()
        start("00:00:00:00:00:99")
        assertTrue(SinkAdapter.requests.isEmpty())
        assertTrue(sink.writes.isEmpty())
        assertEquals(0, SinkAdapter.powerChanges)
    }

    @Test fun unavailableProfileNeverFallsBackToRadioOrHfp() {
        SinkAdapter.acceptRequest = false
        start()
        assertEquals(listOf(11), SinkAdapter.requests)
        assertTrue(sink.writes.isEmpty())
        assertFalse(prefs.getBoolean("applied", false))
        assertEquals(0, SinkAdapter.powerChanges)
        assertTrue(logs.any { it.contains("request rejected") })
    }

    @Test fun lateProxyCallbackAfterTimeoutIsClosedWithoutPolicyWrites() {
        SinkAdapter.callback = false
        start()
        assertTrue(logs.any { it.contains("callback timed out") })
        assertFalse(prefs.getBoolean("applied", false))
        SinkAdapter.listener!!.onServiceConnected(11, sink)
        assertEquals(listOf(11), SinkAdapter.closed)
        assertTrue(sink.writes.isEmpty())
        assertEquals(0, SinkAdapter.powerChanges)
    }

    @Test fun profileWithoutPolicyMethodsIsClosedAndLeftUnchanged() {
        SinkAdapter.profile = object : BluetoothProfile {
            override fun getConnectedDevices() = emptyList<BluetoothDevice>()
            override fun getDevicesMatchingConnectionStates(states: IntArray?) = emptyList<BluetoothDevice>()
            override fun getConnectionState(device: BluetoothDevice?) = BluetoothProfile.STATE_DISCONNECTED
        }
        start()
        assertEquals(listOf(11), SinkAdapter.closed)
        assertFalse(prefs.getBoolean("applied", false))
        assertEquals(0, SinkAdapter.powerChanges)
    }

    @Test fun unreadablePolicyDoesNotMutateAnything() {
        sink.readable = false
        start()
        assertTrue(sink.writes.isEmpty())
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun rejectedDisableDoesNotDisconnectAndEndReleasesJournal() {
        sink.rejectPolicy = 0
        start()
        assertEquals(100, sink.getPriority(phone))
        assertTrue(sink.disconnects.isEmpty())
        restore()
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun acceptedWriteWithoutReadbackDoesNotPretendToDisable() {
        sink.applyWrites = false
        start()
        assertTrue(sink.disconnects.isEmpty())
        assertTrue(logs.any { it.contains("policyNow=100") && it.contains("disconnectRequested=false") })
        restore()
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun rejectedRestoreKeepsJournalAndStartupRecoveryRetries() {
        start()
        sink.rejectPolicy = 100
        restore()
        assertTrue(prefs.getBoolean("applied", false))
        assertEquals(0, sink.getPriority(phone))
        sink.rejectPolicy = null
        CarBluetoothProfileGuard.recoverIfStale(context, logs::add)
        awaitWorker()
        assertEquals(100, sink.getPriority(phone))
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun startupRecoversOriginalPhoneWithoutAnInMemorySession() {
        prefs.edit().putBoolean("applied", true).putString("applied_mode", "media")
            .putString("applied_targets", phone.address).putInt("media_original_policy", 100)
            .putBoolean("media_was_connected", true).commit()
        sink.policies[phone] = 0
        sink.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        CarBluetoothProfileGuard.recoverIfStale(context, logs::add)
        awaitWorker()
        assertEquals(listOf(phone to 100), sink.writes)
        assertEquals(listOf(phone), sink.connects)
        assertFalse(prefs.getBoolean("applied", false))
        assertEquals(0, SinkAdapter.powerChanges)
    }

    @Test fun userTurningRadioOffDoesNotAuthorizeTurningItBackOn() {
        start()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        assertFalse(CarBluetoothProfileGuard.pausedByUs(context))
        restore()
        assertTrue(prefs.getBoolean("applied", false))
        assertEquals(0, SinkAdapter.powerChanges)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        CarBluetoothProfileGuard.recoverIfStale(context, logs::add)
        awaitWorker()
        assertEquals(100, sink.getPriority(phone))
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun anotherActiveMediaPhoneDefersRestorationWithoutDisplacingIt() {
        start()
        sink.states[other] = BluetoothProfile.STATE_CONNECTED
        restore()
        assertEquals(listOf(phone to 0), sink.writes)
        assertTrue(sink.connects.isEmpty())
        assertTrue(prefs.getBoolean("applied", false))
        sink.states[other] = BluetoothProfile.STATE_DISCONNECTED
        CarBluetoothProfileGuard.recoverIfStale(context, logs::add)
        awaitWorker()
        assertEquals(100, sink.getPriority(phone))
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun explicitHandoffNotificationDoesNotApplyTwice() {
        start()
        CarBluetoothProfileGuard.yieldNow()
        awaitWorker()
        assertEquals(listOf(phone to 0), sink.writes)
        assertEquals(listOf(phone), sink.disconnects)
    }

    @Test fun replacementSessionDoesNotOverwriteOriginalPolicyWithForbidden() {
        start()
        start()
        assertEquals(100, prefs.getInt("media_original_policy", -1))
        restore()
        assertEquals(100, sink.getPriority(phone))
        assertEquals(listOf(phone), sink.connects)
    }

    @Test fun tenSessionCyclesRestoreEveryTimeWithoutTouchingHfpOrPower() {
        repeat(10) {
            start()
            assertEquals(0, sink.getPriority(phone))
            restore()
            assertEquals(100, sink.getPriority(phone))
            assertFalse(prefs.getBoolean("applied", false))
        }
        assertEquals(10, sink.disconnects.size)
        assertEquals(10, sink.connects.size)
        assertTrue(SinkAdapter.requests.all { it == 11 })
        assertEquals(0, SinkAdapter.powerChanges)
    }

    @Test fun bluetoothAudioRoutePreventsYieldAndDoesNotFallBackToAdapterMode() {
        start(mediaAudioAllowed = false)
        assertTrue(SinkAdapter.requests.isEmpty())
        assertTrue(sink.writes.isEmpty())
        assertEquals(0, SinkAdapter.powerChanges)
    }

    @Test fun settingsDefaultOffAndEnablingMediaDisablesOldAdapterOption() {
        prefs.edit().clear().commit()
        assertFalse(CarBluetoothProfileGuard.mediaOnlyEnabled(context))
        CarBluetoothProfileGuard.saveAdapterEnabled(context, true)
        CarBluetoothProfileGuard.saveMediaOnlyEnabled(context, true)
        assertFalse(CarBluetoothProfileGuard.adapterEnabled(context))
        assertTrue(CarBluetoothProfileGuard.mediaOnlyEnabled(context))
        assertEquals(0, SinkAdapter.powerChanges)
        CarBluetoothProfileGuard.saveAdapterEnabled(context, true)
        assertFalse(CarBluetoothProfileGuard.mediaOnlyEnabled(context))
    }

    @Test fun endingSessionWhileProxyIsPendingCannotDisableMediaAfterwards() {
        SinkAdapter.callback = false
        CarBluetoothProfileGuard.yieldForSession(context, phone.address, log = logs::add)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (SinkAdapter.listener == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertNotNull(SinkAdapter.listener)
        CarBluetoothProfileGuard.restore(context, logs::add)
        SinkAdapter.listener!!.onServiceConnected(11, sink)
        awaitWorker()
        assertTrue(sink.writes.isEmpty())
        assertFalse(prefs.getBoolean("applied", false))
        assertEquals(listOf(11), SinkAdapter.closed)
    }

    @Test fun unavailableServiceDuringRestoreKeepsJournalForRetry() {
        start()
        SinkAdapter.acceptRequest = false
        restore()
        assertTrue(prefs.getBoolean("applied", false))
        assertEquals(0, sink.getPriority(phone))
        SinkAdapter.acceptRequest = true
        CarBluetoothProfileGuard.recoverIfStale(context, logs::add)
        awaitWorker()
        assertEquals(100, sink.getPriority(phone))
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun savingSwitchOffDuringSessionStillRestoresItsOriginalPolicy() {
        start()
        CarBluetoothProfileGuard.saveMediaOnlyEnabled(context, false)
        assertEquals(0, sink.getPriority(phone))
        restore()
        assertEquals(100, sink.getPriority(phone))
        assertFalse(prefs.getBoolean("applied", false))
    }

    @Test fun replacementPhoneCannotInheritAnotherPhonesRecoveryJournal() {
        start()
        start(other.address)
        assertEquals(listOf(phone to 0), sink.writes)
        assertEquals(phone.address, prefs.getString("applied_targets", null))
        restore()
        assertEquals(listOf(phone to 0, phone to 100), sink.writes)
        assertEquals(listOf(phone), sink.connects)
    }

    @Test fun radioInitiallyOffIsLeftOffWithoutARecoveryJournal() {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        start()
        restore()
        assertFalse(prefs.getBoolean("applied", false))
        assertTrue(SinkAdapter.requests.isEmpty())
        assertEquals(0, SinkAdapter.powerChanges)
        assertFalse(adapter.isEnabled)
    }

    @Test fun failedDisconnectIsReportedWithoutClaimingMediaHasDisconnected() {
        sink.disconnectAccepted = false
        start()
        assertEquals(BluetoothProfile.STATE_CONNECTED, sink.getConnectionState(phone))
        assertTrue(logs.any { it.contains("disconnectRequested=false state=2") })
        assertTrue(prefs.getBoolean("applied", false))
        restore()
        assertEquals(100, sink.getPriority(phone))
        assertTrue(sink.connects.isEmpty())
    }

    @Test fun asynchronousReconnectIsLoggedSeparatelyFromConfirmedPolicyRestoration() {
        start()
        sink.connectState = BluetoothProfile.STATE_CONNECTING
        restore()
        assertTrue(logs.any { it.contains("policyRestored=true connectRequested=true state=1") })
        assertFalse(prefs.getBoolean("applied", false))
    }

    open class Sink : BluetoothProfile {
        val policies = mutableMapOf<BluetoothDevice, Int>()
        val states = mutableMapOf<BluetoothDevice, Int>()
        val writes = mutableListOf<Pair<BluetoothDevice, Int>>()
        val connects = mutableListOf<BluetoothDevice>()
        val disconnects = mutableListOf<BluetoothDevice>()
        var readable = true
        var applyWrites = true
        var rejectPolicy: Int? = null
        var disconnectAccepted = true
        var connectState = BluetoothProfile.STATE_CONNECTED
        var beforeWrite: (() -> Unit)? = null
        fun getPriority(device: BluetoothDevice): Int {
            check(readable) { "Simulated vendor policy access failure" }
            return policies[device] ?: 100
        }
        fun setPriority(device: BluetoothDevice, value: Int): Boolean {
            beforeWrite?.invoke()
            writes += device to value
            if (rejectPolicy == value) return false
            if (applyWrites) policies[device] = value
            return true
        }
        fun disconnect(device: BluetoothDevice): Boolean {
            disconnects += device
            if (!disconnectAccepted) return false
            states[device] = BluetoothProfile.STATE_DISCONNECTED
            return true
        }
        fun connect(device: BluetoothDevice): Boolean {
            connects += device
            states[device] = connectState
            return true
        }
        override fun getConnectionState(device: BluetoothDevice?) = states[device] ?: BluetoothProfile.STATE_DISCONNECTED
        override fun getConnectedDevices() = states.filterValues { it == BluetoothProfile.STATE_CONNECTED }.keys.toList()
        override fun getDevicesMatchingConnectionStates(states: IntArray?) = getConnectedDevices()
    }

    class ModernSink : Sink() {
        var modernReads = 0
        var modernWrites = 0
        fun getConnectionPolicy(device: BluetoothDevice): Int { modernReads++; return getPriority(device) }
        fun setConnectionPolicy(device: BluetoothDevice, value: Int): Boolean {
            modernWrites++
            val accepted = setPriority(device, value)
            // Modern AOSP performs connect/disconnect as part of setting the policy itself.
            if (accepted && applyWrites) {
                if (value == 0) disconnect(device) else connect(device)
            }
            return accepted
        }
    }

    @Implements(BluetoothAdapter::class)
    class SinkAdapter : ShadowBluetoothAdapter() {
        @Implementation override fun getProfileProxy(context: Context, serviceListener: BluetoothProfile.ServiceListener,
            id: Int): Boolean {
            requests += id
            listener = serviceListener
            if (!acceptRequest) return false
            if (callback) serviceListener.onServiceConnected(id, profile)
            return true
        }
        @Implementation override fun closeProfileProxy(id: Int, profile: BluetoothProfile) { closed += id }
        @Implementation override fun enable(): Boolean { powerChanges++; return super.enable() }
        @Implementation override fun disable(): Boolean { powerChanges++; return super.disable() }
        companion object {
            lateinit var profile: BluetoothProfile
            var listener: BluetoothProfile.ServiceListener? = null
            val requests = mutableListOf<Int>()
            val closed = mutableListOf<Int>()
            var acceptRequest = true
            var callback = true
            var powerChanges = 0
        }
    }
}
