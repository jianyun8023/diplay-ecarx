package com.shilapi.xcertplay.hud

import android.view.KeyEvent
import com.ecarx.xui.adaptapi.input.Input
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.EcarxSwKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises production reflection and callback dispatch; the fixture models only the ROM boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class EcarxSwKeyInterceptFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val presses = mutableListOf<EcarxSwKey.Press?>()
    private val releases = mutableListOf<Int>()
    private val handles = mutableListOf<EcarxSwKeyIntercept.Handle>()
    private val listener = object : EcarxSwKeyIntercept.Listener {
        override fun onKeyPressed(keyCode: Int): Boolean {
            val press = EcarxSwKey.resolve(EcarxSwKey.prefs(context), keyCode)
            presses += press
            return press != null
        }
        override fun onKeyReleased(keyCode: Int): Boolean {
            releases += keyCode
            return false
        }
    }

    @Before fun reset() {
        Input.reset()
        EcarxSwKey.prefs(context).edit().clear().commit()
    }

    @After fun cleanup() {
        Input.releaseFailure = null
        handles.forEach { it.release() }
        Input.reset()
        EcarxSwKey.prefs(context).edit().clear().commit()
    }

    @Test fun registerPressReleaseAndStopUseTheRealReflectionAndMapping() {
        val handle = register()
        assertSame(context, Input.createdWith)
        assertEquals("fixture", handle.label)
        assertArrayEquals(
            intArrayOf(200088, 200087, 200085, 200231, 200164, 200400, 200024, 200025, 119),
            Input.registrations.single().keys,
        )
        assertEquals("keys=9 accepted=all", handle.describe())
        assertEquals(listOf(true), Input.press(200087))
        assertEquals(CarPlayMediaButton.NEXT, presses.single()?.mediaIndex)
        assertEquals(listOf(false), Input.release(200087))
        assertEquals(listOf(200087), releases)
        handle.release()
        assertSame(Input.registrations.single(), Input.releases.single())
        assertTrue(Input.press(200087).isEmpty())
        assertTrue(Input.release(200087).isEmpty())
        assertEquals(1, presses.size)
    }

    @Test fun learntVoiceAndTelephoneKeysTravelThroughTheProxy() {
        EcarxSwKey.assign(EcarxSwKey.prefs(context), EcarxSwKey.Action.VOICE, 119)
        EcarxSwKey.assign(EcarxSwKey.prefs(context), EcarxSwKey.Action.ANSWER, 200400)
        register()
        assertEquals(listOf(true), Input.press(119))
        assertEquals(listOf(true), Input.press(200400))
        assertTrue(presses[0]!!.isSiri)
        assertEquals(CarPlayMediaButton.ANSWER, presses[1]?.callIndex)
    }

    @Test fun unmappedRegisteredKeyReturnsTheConsumersFalseResult() {
        register()
        assertEquals(listOf(false), Input.press(200164))
        assertNull(presses.single())
    }

    @Test fun registeredVoiceKeyUsesAndroidVoiceAssistWithoutLearning() {
        register()
        // Android SDK semantics only; the physical key emitted by a head unit still needs a device check.
        val voice = CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_VOICE_ASSIST
        assertEquals(200231, voice)
        assertEquals(listOf(true), Input.press(voice))
        assertTrue(presses.single()!!.isSiri)
    }

    @Test fun longPressAndUnknownCallbacksHaveNoInventedBusinessMeaning() {
        register()
        val callback = Input.registrations.single().callback
        assertFalse(callback.onKeyLongPressed(200231))
        assertEquals(0, callback.callbackVersion())
        callback.onServiceChanged()
        assertTrue(presses.isEmpty())
        assertTrue(releases.isEmpty())
    }

    @Test fun callbackObjectMethodsKeepIdentityWithoutCallingTheListener() {
        register()
        val callback = Input.registrations.single().callback
        assertEquals("EcarxSwKeyCallback", callback.toString())
        assertEquals(System.identityHashCode(callback), callback.hashCode())
        assertTrue(callback.equals(callback))
        assertFalse(callback.equals(Any()))
        assertFalse(callback.equals(null))
        assertTrue(presses.isEmpty())
    }

    @Test fun refusedKeysAreNamedAndDoNotArriveFromTheService() {
        Input.refused = intArrayOf(200088, 200087, 200085, 200231, 200164, 200400, 200024, 200025, 119, 777)
        val handle = register()
        assertArrayEquals(Input.refused, handle.refused)
        assertEquals("keys=9 refused=prev|next|play|voice|k200164|k200400|vol+|vol-|k119|777", handle.describe())
        assertTrue(Input.press(200231).isEmpty())
        assertTrue(presses.isEmpty())
    }

    @Test fun partialRefusalStillAllowsAcceptedMediaKeys() {
        Input.refused = intArrayOf(200231)
        register()
        assertTrue(Input.press(200231).isEmpty())
        assertEquals(listOf(true), Input.press(200088))
        assertEquals(CarPlayMediaButton.PREVIOUS, presses.single()?.mediaIndex)
    }

    @Test fun emptyRefusalMeansAllKeysAccepted() {
        Input.refused = intArrayOf()
        assertEquals("keys=9 accepted=all", register().describe())
    }

    @Test fun unavailableInputDoesNotRegister() {
        Input.unavailable = true
        assertNull(EcarxSwKeyIntercept.register(context, listener))
        assertTrue(Input.registrations.isEmpty())
    }

    @Test fun createPermissionFailureDoesNotEscape() {
        Input.createFailure = SecurityException("OEM permission denied")
        assertNull(EcarxSwKeyIntercept.register(context, listener))
        assertTrue(Input.registrations.isEmpty())
    }

    @Test fun requestPermissionFailureDoesNotLeaveAHandle() {
        Input.requestFailure = SecurityException("interception denied")
        assertNull(EcarxSwKeyIntercept.register(context, listener))
        assertTrue(Input.registrations.isEmpty())
    }

    @Test fun disconnectedServiceDuringRequestDoesNotLeaveAHandle() {
        Input.requestFailure = IllegalStateException("input service unavailable")
        assertNull(EcarxSwKeyIntercept.register(context, listener))
        assertTrue(Input.registrations.isEmpty())
    }

    @Test fun missingRequestMethodDoesNotReturnAnAcceptedHandle() {
        Input.service = Any()
        assertNull(EcarxSwKeyIntercept.register(context, listener))
        assertTrue(Input.registrations.isEmpty())
        assertTrue(Input.press(200087).isEmpty())
    }

    @Test fun registrationsAreIndependentAndCallersMustAvoidDuplicates() {
        val first = register("settings")
        register("carplay")
        assertEquals(listOf(true, true), Input.press(200087))
        assertEquals(2, presses.size)
        first.release()
        assertEquals(listOf(true), Input.press(200087))
        assertEquals(3, presses.size)
    }

    @Test fun releaseFailureStillInvalidatesTheCallback() {
        val handle = register()
        Input.releaseFailure = IllegalStateException("service disconnected")
        handle.release()
        assertTrue(Input.registrations.single().active)
        assertEquals(listOf(false), Input.press(200087))
        assertEquals(listOf(false), Input.release(200087))
        assertTrue(presses.isEmpty())
        assertTrue(releases.isEmpty())
    }

    @Test fun repeatedReleaseUnregistersOnlyOnce() {
        val handle = register()
        handle.release()
        handle.release()
        assertEquals(1, Input.releases.size)
    }

    @Test fun inFlightCallbackAfterReleaseDoesNotReachTheListener() {
        val handle = register()
        val inFlight = Input.registrations.single().callback
        handle.release()
        assertTrue(Input.press(200087).isEmpty())
        assertFalse(inFlight.onKeyPressed(200087))
        assertFalse(inFlight.onKeyReleased(200087))
        assertTrue(presses.isEmpty())
        assertTrue(releases.isEmpty())
    }

    private fun register(label: String = "fixture"): EcarxSwKeyIntercept.Handle =
        requireNotNull(EcarxSwKeyIntercept.register(context, listener, label)).also { handles += it }
}
