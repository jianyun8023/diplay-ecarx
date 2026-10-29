package com.shilapi.xcertplay.hud

import com.ecarx.xui.adaptapi.input.Input
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxSwKeyInterceptTest {
    @Test fun registersMediaAndVoiceAndReleasesExactlyItsOwnKeys() {
        Input.instance = Input()
        val handle = checkNotNull(EcarxSwKeyIntercept.register(RuntimeEnvironment.getApplication(),
            object : EcarxSwKeyIntercept.Listener {
                override fun onKeyPressed(keyCode: Int) = false
                override fun onKeyReleased(keyCode: Int) = false
            }))
        val requested = Input.instance.requested
        for (key in listOf(200085, 200087, 200088)) assertTrue(key in requested)
        assertTrue("voice key is preserved", 200231 in requested)
        assertTrue(handle.describe().contains("keys=${requested.size}"))
        assertTrue(handle.describe().contains("registered="))
        assertFalse(handle.describe().contains("refused"))
        handle.release()
        assertArrayEquals(requested, Input.instance.released)
        assertNull(Input.instance.callback)
    }

    @Test fun aReleasedCallbackCannotTriggerSiriAgain() {
        Input.instance = Input()
        var presses = 0
        val handle = checkNotNull(EcarxSwKeyIntercept.register(RuntimeEnvironment.getApplication(),
            object : EcarxSwKeyIntercept.Listener {
                override fun onKeyPressed(keyCode: Int): Boolean { presses++; return true }
                override fun onKeyReleased(keyCode: Int) = true
            }))
        val callback = checkNotNull(Input.instance.callback)
        assertTrue(callback.onKeyPressed(200231))
        handle.release()
        assertFalse(callback.onKeyPressed(200231))
        assertEquals(1, presses)
        assertTrue(handle.describe().contains("voice"))
        assertFalse(handle.describe().contains("k200400"))
    }
}
