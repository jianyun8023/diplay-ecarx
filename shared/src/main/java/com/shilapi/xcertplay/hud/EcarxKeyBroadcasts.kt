package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * The ECARX private steering-wheel key broadcasts, recovered from the `media$EcarXVoiceKey` /
 * `media$EcarXCalleKey` receivers in the head unit's own key service.
 *
 * On 领克 / 亿咖通 head units the steering wheel's voice key and call key are delivered as system
 * broadcasts rather than as `KeyEvent`s, so a normal activity never sees them. The reference app subscribes to
 * these two actions and calls `abortBroadcast()` on a match to take ownership away from the factory
 * HMI. DiPlay mirrors that: each [register] returns a receiver that forwards the press to [onPress].
 */
object EcarxKeyBroadcasts {
    private const val ACTION_VOICE = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
    private const val ACTION_CALL = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"

    /** Registers the voice-key broadcast; [onPress] runs on the broadcast's thread. */
    fun registerVoice(context: Context, onPress: () -> Unit): BroadcastReceiver =
        register(context, ACTION_VOICE, onPress)

    /** Registers the call-key broadcast; [onPress] runs on the broadcast's thread. */
    fun registerCall(context: Context, onPress: () -> Unit): BroadcastReceiver =
        register(context, ACTION_CALL, onPress)

    /** Invalidates local delivery before asking Android to unregister, including when that fails. */
    fun unregister(context: Context, receiver: BroadcastReceiver) {
        if (receiver is KeyReceiver && !receiver.stop()) return
        context.unregisterReceiver(receiver)
    }

    private fun register(context: Context, action: String, onPress: () -> Unit): BroadcastReceiver {
        val receiver = KeyReceiver(action, onPress)
        val filter = IntentFilter(action).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY }
        context.registerReceiver(receiver, filter)
        return receiver
    }

    private class KeyReceiver(private val action: String, private val onPress: () -> Unit) : BroadcastReceiver() {
        private var active = true

        @Synchronized
        fun stop(): Boolean {
            if (!active) return false
            active = false
            return true
        }

        @Synchronized
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!active || intent.action != action) return
            abortBroadcast()
            onPress()
        }
    }
}
