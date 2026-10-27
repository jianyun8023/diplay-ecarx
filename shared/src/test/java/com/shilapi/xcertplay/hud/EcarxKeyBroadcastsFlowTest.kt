package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class EcarxKeyBroadcastsFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val registered = mutableListOf<BroadcastReceiver>()
    private val events = mutableListOf<String>()

    @Before fun reset() { events.clear() }

    @After fun cleanup() {
        registered.forEach { runCatching { EcarxKeyBroadcasts.unregister(context, it) } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun voiceAndCallBroadcastsReachOnlyTheirOwnCallbacksAndStopAfterUnregister() {
        val voice = EcarxKeyBroadcasts.registerVoice(context) { events += "voice" }.also { registered += it }
        val call = EcarxKeyBroadcasts.registerCall(context) { events += "call" }.also { registered += it }
        send(VOICE)
        send(CALL)
        send("ecarx.intent.action.UNRELATED")
        assertEquals(listOf("voice", "call"), events)
        EcarxKeyBroadcasts.unregister(context, voice)
        EcarxKeyBroadcasts.unregister(context, call)
        registered.clear()
        send(VOICE)
        send(CALL)
        assertEquals(listOf("voice", "call"), events)
    }

    @Test fun directMismatchedIntentsAreIgnoredEvenIfTheSystemDeliversThem() {
        val voice = EcarxKeyBroadcasts.registerVoice(context) { events += "voice" }.also { registered += it }
        val call = EcarxKeyBroadcasts.registerCall(context) { events += "call" }.also { registered += it }
        voice.onReceive(context, Intent(CALL))
        call.onReceive(context, Intent(VOICE))
        voice.onReceive(context, Intent())
        call.onReceive(context, Intent())
        assertTrue(events.isEmpty())
    }

    @Test fun orderedVoiceAndCallBroadcastsAbortBeforeTheLowerPriorityReceiver() {
        EcarxKeyBroadcasts.registerVoice(context) { events += "voice" }.also { registered += it }
        EcarxKeyBroadcasts.registerCall(context) { events += "call" }.also { registered += it }
        val factoryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { events += "factory" }
        }
        context.registerReceiver(factoryReceiver, IntentFilter().apply {
            addAction(VOICE)
            addAction(CALL)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        })
        registered += factoryReceiver
        context.sendOrderedBroadcast(Intent(VOICE), null)
        shadowOf(Looper.getMainLooper()).idle()
        context.sendOrderedBroadcast(Intent(CALL), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("voice", "call"), events)
    }

    @Test fun registrationUsesThePrivateActionsAndSystemHighPriority() {
        val filters = mutableListOf<IntentFilter>()
        val recording = object : ContextWrapper(context) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
                filters += requireNotNull(filter)
                return super.registerReceiver(receiver, filter)
            }
        }
        EcarxKeyBroadcasts.registerVoice(recording) {}.also { registered += it }
        EcarxKeyBroadcasts.registerCall(recording) {}.also { registered += it }
        assertEquals(listOf(VOICE, CALL), filters.map { it.getAction(0) })
        assertTrue(filters.all { it.countActions() == 1 && it.priority == IntentFilter.SYSTEM_HIGH_PRIORITY })
    }

    @Test fun duplicateRegistrationsCanBeUnregisteredIndependently() {
        val first = EcarxKeyBroadcasts.registerVoice(context) { events += "first" }.also { registered += it }
        val second = EcarxKeyBroadcasts.registerVoice(context) { events += "second" }.also { registered += it }
        context.sendOrderedBroadcast(Intent(VOICE), null)
        shadowOf(Looper.getMainLooper()).idle()
        // The first delivered receiver aborts ordered delivery; equal priorities do not promise an order.
        assertEquals(1, events.size)
        assertTrue(events.single() in listOf("first", "second"))
        EcarxKeyBroadcasts.unregister(context, first)
        registered.remove(first)
        events.clear()
        send(VOICE)
        assertEquals(listOf("second"), events)
        EcarxKeyBroadcasts.unregister(context, second)
        registered.remove(second)
        send(VOICE)
        assertEquals(listOf("second"), events)
    }

    @Test fun permissionDeniedAtRegistrationIsCurrentlyPropagatedToTheCaller() {
        val denied = object : ContextWrapper(context) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
                throw SecurityException("OEM broadcast registration denied")
            }
        }
        assertThrows(SecurityException::class.java) { EcarxKeyBroadcasts.registerVoice(denied) {} }
        assertThrows(SecurityException::class.java) { EcarxKeyBroadcasts.registerCall(denied) {} }
    }

    @Test fun lateVoiceAndCallReceiversCannotInvokeCallbacksAfterUnregister() {
        val receiver = EcarxKeyBroadcasts.registerVoice(context) { events += "voice" }.also { registered += it }
        val call = EcarxKeyBroadcasts.registerCall(context) { events += "call" }.also { registered += it }
        EcarxKeyBroadcasts.unregister(context, receiver)
        EcarxKeyBroadcasts.unregister(context, call)
        registered.clear()
        send(VOICE)
        assertTrue(events.isEmpty())
        receiver.onReceive(context, Intent(VOICE))
        call.onReceive(context, Intent(CALL))
        assertTrue(events.isEmpty())
    }

    @Test fun repeatedUnregisterDoesNotCallAndroidTwice() {
        val receiver = EcarxKeyBroadcasts.registerVoice(context) { events += "voice" }.also { registered += it }
        EcarxKeyBroadcasts.unregister(context, receiver)
        EcarxKeyBroadcasts.unregister(context, receiver)
        registered.clear()
        receiver.onReceive(context, Intent(VOICE))
        assertTrue(events.isEmpty())
    }

    @Test fun unregisterFailureStillInvalidatesLocalDelivery() {
        val failed = object : ContextWrapper(context) {
            override fun unregisterReceiver(receiver: BroadcastReceiver) {
                throw IllegalStateException("Android receiver service unavailable")
            }
        }
        val receiver = EcarxKeyBroadcasts.registerVoice(failed) { events += "voice" }
        assertThrows(IllegalStateException::class.java) { EcarxKeyBroadcasts.unregister(failed, receiver) }
        receiver.onReceive(context, Intent(VOICE))
        send(VOICE)
        assertTrue(events.isEmpty())
        // The failing context did not remove the Android registration, so clean that boundary directly.
        context.unregisterReceiver(receiver)
    }

    private fun send(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val VOICE = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
        const val CALL = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"
    }
}
