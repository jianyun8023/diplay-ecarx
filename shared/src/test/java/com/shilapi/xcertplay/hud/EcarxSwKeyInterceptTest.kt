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
    @Test fun standardModeDoesNotInterceptMediaAndReleasesExactlyItsOwnKeys() {
        for (includeMedia in listOf(true, false)) {
            Input.instance = Input()
            val handle = checkNotNull(EcarxSwKeyIntercept.register(RuntimeEnvironment.getApplication(),
                object : EcarxSwKeyIntercept.Listener {
                    override fun onKeyPressed(keyCode: Int) = false
                    override fun onKeyReleased(keyCode: Int) = false
                }, includeMediaKeys = includeMedia))
            val requested = Input.instance.requested
            for (key in listOf(200085, 200087, 200088)) assertEquals(includeMedia, key in requested)
            assertTrue("voice key is preserved", 200231 in requested)
            assertTrue(handle.describe().contains("keys=${requested.size}"))
            handle.release()
            assertArrayEquals(requested, Input.instance.released)
        }
    }
}
