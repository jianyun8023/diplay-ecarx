package com.shihab.diplay.network

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WifiScanPauseTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun controllerCloseInvalidatesQueuedPausesAndReleasesItsOwnLeaseOnce() {
        val acquisitions = mutableListOf<Pair<Any, () -> Boolean>>()
        val releases = mutableListOf<Any>()
        val control = object : WifiScanPauseControl {
            override fun acquire(app: Context, owner: Any, current: () -> Boolean, log: (String) -> Unit) {
                acquisitions.add(owner to current)
            }

            override fun release(app: Context, owner: Any, log: (String) -> Unit) {
                releases.add(owner)
            }
        }
        val pause = WifiScanPause(context, {}, control)
        pause.pause()
        pause.pause()
        assertEquals(2, acquisitions.size)
        assertEquals(acquisitions[0].first, acquisitions[1].first)
        assertTrue(acquisitions.all { it.second() })
        pause.close()
        pause.close()
        pause.pause()
        assertEquals(2, acquisitions.size)
        assertEquals(listOf(acquisitions[0].first), releases)
        assertTrue(acquisitions.none { it.second() })
    }

    @Test
    fun onlyAndroidNineWifiDirectPausesTheSearch() {
        for (backend in WirelessHotspotBackend.entries) {
            for (sdk in 24..36) {
                assertEquals("$backend on API $sdk",
                    backend == WirelessHotspotBackend.WIFI_P2P && sdk == 28, WifiScanPause.eligible(backend, sdk))
            }
        }
    }

}
