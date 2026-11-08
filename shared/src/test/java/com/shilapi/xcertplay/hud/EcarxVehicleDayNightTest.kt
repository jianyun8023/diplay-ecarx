package com.shilapi.xcertplay.hud

import android.os.Looper
import com.ecarx.xui.adaptapi.device.Device
import com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxVehicleDayNightTest {
    private class Mode : IDayNightMode {
        var current = 2
        var accepted = true
        var releases = 0
        var callback: IDayNightMode.IDayNightChangeCallBack? = null
        override fun getDayNight() = current
        override fun getDayNightMode() = 0
        override fun registerDayNightModeCallBack(value: IDayNightMode.IDayNightChangeCallBack): Boolean {
            if (accepted) callback = value
            return accepted
        }
        override fun unregisterDayNigntModeCallBack(value: IDayNightMode.IDayNightChangeCallBack): Boolean {
            assertSame(callback, value)
            releases++
            callback = null
            return true
        }
    }

    @After fun clearDevice() { Device.mode = null }

    @Test fun readsResolvedStateAndListensUntilTheExactCallbackIsReleased() {
        val mode = Mode().also { Device.mode = it }
        val logs = mutableListOf<String>()
        val source = EcarxVehicleDayNight(RuntimeEnvironment.getApplication()) { logs += it }
        val values = mutableListOf<Boolean>()
        assertTrue(source.start { values += it })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), values)
        val callback = checkNotNull(mode.callback)
        assertEquals(System.identityHashCode(callback), callback.hashCode())
        assertEquals(callback, callback)
        assertNotEquals(callback, Any())
        assertTrue(callback.toString().isNotBlank())
        assertTrue(logs.toString(), logs.contains("Vehicle day/night backend=adaptapi current=2 preference=0"))
        callback.onDayNightChanged(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), values)
        callback.onDayNightChanged(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), values)
        // A preference change re-reads the resolved state instead of waiting for another push.
        mode.current = 2
        callback.onDayNightModeChange(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false, true), values)
        assertTrue(logs.toString(), logs.contains("Vehicle day/night preference raw=2 current=2"))
        source.stop()
        source.stop()
        assertEquals(1, mode.releases)
        assertNull(mode.callback)
    }

    @Test fun unavailableOrRejectedBackendDoesNotPublishAState() {
        val source = EcarxVehicleDayNight(RuntimeEnvironment.getApplication())
        assertFalse(source.start { fail("Device has no day/night handle") })
        val mode = Mode().apply { accepted = false }.also { Device.mode = it }
        assertFalse(source.start { fail("Registration was refused") })
        source.stop()
        assertNull(mode.callback)
        assertEquals(0, mode.releases)
    }
}
