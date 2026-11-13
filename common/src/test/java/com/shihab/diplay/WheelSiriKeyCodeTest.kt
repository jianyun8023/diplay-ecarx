package com.shihab.diplay

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A custom Siri key learnt through the ECARX interception: the ROM reports a bare key code, so the stored
 * [WheelKey] can only be matched by code. The full-key match that the accessibility service and the
 * CarPlay window use must stay untouched, or a standard press would no longer be recognised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelSiriKeyCodeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theSwitchAloneIsNotAnAssignment() {
        WheelZoomSettings.setSiriKey(context, true)
        assertFalse(WheelZoomSettings.isSiriKeyCode(context, 200164))
    }

    @Test
    fun anInterceptedCodeMatchesTheLearntSiriKey() {
        WheelZoomSettings.setSiriKey(context, true)
        WheelZoomSettings.assign(context, WheelZoomSettings.Role.SIRI, WheelKey(200164, 0, "ECARX interception"))
        assertTrue(WheelZoomSettings.isSiriKeyCode(context, 200164))
        assertFalse(WheelZoomSettings.isSiriKeyCode(context, 200087))
        // The full-key match is unchanged: a press whose scan code differs is still not the same key.
        assertFalse(WheelZoomSettings.isSiriKey(context, WheelKey(200164, 1, "ECARX interception")))
    }

    @Test
    fun switchedOffSiriKeyIgnoresTheAssignment() {
        WheelZoomSettings.assign(context, WheelZoomSettings.Role.SIRI, WheelKey(200164, 0, "ECARX interception"))
        assertFalse(WheelZoomSettings.isSiriKeyCode(context, 200164))
        WheelZoomSettings.setSiriKey(context, true)
        assertTrue(WheelZoomSettings.isSiriKeyCode(context, 200164))
    }
}
