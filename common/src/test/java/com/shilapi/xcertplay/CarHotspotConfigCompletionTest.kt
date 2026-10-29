package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.HotspotCredentials
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.util.ReflectionHelpers

/** Exercise the actual API-28 read/write workers, without ADB or a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi", shadows = [CarHotspotConfigCompletionTest.WifiApi::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarHotspotConfigCompletionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var owner: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private var destroyed = false
    private val desired = HotspotCredentials("Updated car", "updated-password", ManualHotspotSecurity.WPA2)

    @Before fun setup() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        WifiApi.reset()
        owner = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        activity = owner.get()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Saved car")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "saved-password")
        AirPlayPersistence.saveManualHotspotSecurity(app, ManualHotspotSecurity.WPA2)
        CarHotspotSettings.setEnabled(app, false)
    }

    @After fun cleanup() {
        WifiApi.release.countDown()
        awaitCompletion()
        if (!destroyed) owner.pause().stop().destroy()
    }

    @Test fun pendingReadCannotUndoANewerWifiDirectSelection() {
        startBlocked(write = false)
        select(WirelessHotspotMode.WIFI_P2P)
        finishBlocked()
        assertUnchanged(WirelessHotspotMode.WIFI_P2P)
    }

    @Test fun pendingWriteCannotUndoANewerExistingWifiSelection() {
        startBlocked(write = true)
        select(WirelessHotspotMode.EXISTING_WIFI)
        finishBlocked()
        assertUnchanged(WirelessHotspotMode.EXISTING_WIFI)
        // The already-authorized platform write may finish; a stale UI completion is not a rollback.
        assertEquals(desired.ssid, WifiApi.saved.SSID)
    }

    @Test fun switchingAwayAndBackDoesNotReviveAnObsoleteRead() {
        startBlocked(write = false)
        select(WirelessHotspotMode.WIFI_P2P)
        select(WirelessHotspotMode.MANUAL)
        finishBlocked()
        assertUnchanged(WirelessHotspotMode.MANUAL)
    }

    @Test fun duplicateReadAndWriteRequestsStaySerialized() {
        for (write in listOf(false, true)) {
            WifiApi.reset()
            startBlocked(write)
            read()
            write()
            finishBlocked()
            assertEquals(if (write) 2 else 1, WifiApi.reads.get())
            assertEquals(if (write) 1 else 0, WifiApi.writes.get())
        }
    }

    @Test fun validReadStillRefreshesCredentialsAndCommitsManualMode() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        ReflectionHelpers.setField(activity, "pendingCarHotspotSetup", true)
        read()
        awaitCompletion()
        assertEquals("Native car", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("native-password", AirPlayPersistence.loadManualHotspotPassphrase(app))
        assertEquals(ManualHotspotSecurity.WPA2, AirPlayPersistence.loadManualHotspotSecurity(app))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertFalse(ReflectionHelpers.getField(activity, "pendingCarHotspotSetup"))
    }

    @Test fun verifiedWriteStillRefreshesCredentials() {
        write()
        awaitCompletion()
        assertEquals(desired.ssid, AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals(desired.passphrase, AirPlayPersistence.loadManualHotspotPassphrase(app))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(app))
    }

    @Test fun failedWriteDoesNotUpdatePreferences() {
        WifiApi.acceptWrite = false
        write()
        awaitCompletion()
        assertUnchanged(WirelessHotspotMode.MANUAL)
    }

    @Test fun destroyedActivityCannotCommitReadCompletion() = destroyWhilePending(write = false)
    @Test fun destroyedActivityCannotCommitWriteCompletion() = destroyWhilePending(write = true)

    private fun destroyWhilePending(write: Boolean) {
        startBlocked(write)
        owner.pause().stop().destroy()
        destroyed = true
        finishBlocked()
        assertUnchanged(WirelessHotspotMode.MANUAL)
    }

    private fun startBlocked(write: Boolean) {
        WifiApi.blockRead = !write
        WifiApi.blockWrite = write
        if (write) write() else read()
        assertTrue("worker did not reach platform call", WifiApi.entered.await(5, TimeUnit.SECONDS))
    }

    private fun finishBlocked() { WifiApi.release.countDown(); awaitCompletion() }

    private fun awaitCompletion() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ReflectionHelpers.getField<Boolean>(activity, "carHotspotConfigBusy") && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("worker did not finish", ReflectionHelpers.getField(activity, "carHotspotConfigBusy"))
    }

    private fun read() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "importCarHotspotConfig",
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
    private fun write() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "writeCarHotspotConfigNow",
        ReflectionHelpers.ClassParameter.from(HotspotCredentials::class.java, desired))
    private fun select(mode: WirelessHotspotMode) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "applyWirelessLink",
        ReflectionHelpers.ClassParameter.from(WirelessHotspotMode::class.java, mode))

    private fun assertUnchanged(mode: WirelessHotspotMode) {
        assertEquals(mode, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals("Saved car", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals("saved-password", AirPlayPersistence.loadManualHotspotPassphrase(app))
    }

    @Implements(WifiManager::class)
    class WifiApi : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = 11
        @Implementation fun isWifiApEnabled(): Boolean = false
        @Implementation override fun getWifiApConfiguration(): WifiConfiguration {
            reads.incrementAndGet()
            if (blockRead) { blockRead = false; awaitRelease() }
            return WifiConfiguration(saved)
        }
        @Implementation override fun setWifiApConfiguration(config: WifiConfiguration): Boolean {
            writes.incrementAndGet()
            if (blockWrite) { blockWrite = false; awaitRelease() }
            if (acceptWrite) saved = WifiConfiguration(config)
            return acceptWrite
        }
        private fun awaitRelease() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        companion object {
            @Volatile var blockRead = false
            @Volatile var blockWrite = false
            @Volatile var acceptWrite = true
            @Volatile lateinit var saved: WifiConfiguration
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
            val reads = AtomicInteger()
            val writes = AtomicInteger()
            fun reset() {
                blockRead = false; blockWrite = false; acceptWrite = true
                reads.set(0); writes.set(0)
                entered = CountDownLatch(1); release = CountDownLatch(1)
                saved = WifiConfiguration().apply {
                    SSID = "Native car"
                    preSharedKey = "native-password"
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
                }
            }
        }
    }

}
