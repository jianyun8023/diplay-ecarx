package com.shilapi.xcertplay.hud

import android.content.Intent
import android.os.Looper
import com.ecarx.xui.adaptapi.input.Input
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxSwKeyInterceptTest {
    private val ready = "com.ecarx.input.action.ACTION_INPUT_KEY_SERVICE_READY"
    @Before fun resetFixture() { Input.createFails = false; Input.creates = 0 }

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

    @Test @Config(sdk = [33]) fun initialCreateFailureWaitsForReadyAndRegistersFreshInput() {
        val app = RuntimeEnvironment.getApplication()
        Input.instance = Input(); Input.createFails = true
        val handle = checkNotNull(EcarxSwKeyIntercept.register(app, listener(), "carplay"))
        assertTrue(handle.describe().contains("create-failed"))
        Input.createFails = false; Input.instance = Input()
        app.sendBroadcast(Intent(ready)); shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(Input.instance.callback)
        assertEquals(1, Input.instance.requests)
        assertTrue(EcarxSwKeyIntercept.diagnosticReport().contains("READYResult=registered"))
        handle.release()
    }

    @Test fun repeatedReadyReplacesOldCallbackWithoutAccumulatingRegistrations() {
        val app = RuntimeEnvironment.getApplication()
        val first = Input().also { Input.instance = it }
        val handle = checkNotNull(EcarxSwKeyIntercept.register(app, listener(), "carplay"))
        val old = checkNotNull(first.callback)
        val second = Input().also { Input.instance = it }
        app.sendBroadcast(Intent(ready)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, first.abandons)
        assertNull(first.callback)
        assertFalse(old.onKeyPressed(200231))
        val replaced = checkNotNull(second.callback)
        app.sendBroadcast(Intent(ready)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, second.abandons)
        assertEquals(2, second.requests)
        assertFalse(replaced.onKeyPressed(200231))
        assertNotNull(second.callback)
        handle.release()
    }

    @Test fun releaseUnregistersReadyReceiverAndOldCallbackCannotRecordKeys() {
        val app = RuntimeEnvironment.getApplication()
        Input.instance = Input()
        val handle = checkNotNull(EcarxSwKeyIntercept.register(app, listener(), "settings"))
        val old = checkNotNull(Input.instance.callback)
        val before = EcarxSwKeyIntercept.diagnosticReport()
        handle.release()
        val creates = Input.creates
        app.sendBroadcast(Intent(ready)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(creates, Input.creates)
        assertFalse(old.onKeyReleased(200231))
        assertEquals(before.substringAfter("received=").substringBefore(' '),
            EcarxSwKeyIntercept.diagnosticReport().substringAfter("received=").substringBefore(' '))
        assertTrue(EcarxSwKeyIntercept.diagnosticReport().contains("activeHandles=none"))
    }

    @Test fun downAndUpDiagnosticsCountActualCallbackDelivery() {
        Input.instance = Input()
        val handle = checkNotNull(EcarxSwKeyIntercept.register(RuntimeEnvironment.getApplication(), listener(), "carplay"))
        val before = EcarxSwKeyIntercept.diagnosticReport().substringAfter("received=").substringBefore(' ').toLong()
        val callback = checkNotNull(Input.instance.callback)
        callback.onKeyPressed(200231); callback.onKeyReleased(200231)
        val report = EcarxSwKeyIntercept.diagnosticReport()
        assertTrue(report.contains("received=${before + 2}"))
        assertTrue(report.contains("lastKey=voice lastAction=UP"))
        handle.release()
    }

    private fun listener() = object : EcarxSwKeyIntercept.Listener {
        override fun onKeyPressed(keyCode: Int) = true
        override fun onKeyReleased(keyCode: Int) = true
    }
}
