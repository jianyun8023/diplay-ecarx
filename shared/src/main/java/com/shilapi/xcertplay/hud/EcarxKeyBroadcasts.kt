package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

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
    // Same tag as EcarxSwKeyIntercept: one logcat grep shows the intercept path and the broadcast
    // path together, which is what tells a swallowed press (HY's receiver aborted it first) from a
    // broadcast that never reached Android at all.
    private const val TAG = "DiPlay-Ecarx-SwKey"
    private const val ACTION_VOICE = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
    private const val ACTION_CALL = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"
    private const val EXTRA_ACTION_TYPE = "ecarx.extra.ECARX_KEY_ACTION_TYPE"
    private const val EXTRA_HY = "ecarx.extra.HY"

    private fun pressLabel(type: Int) = when (type) {
        0 -> "short"
        1 -> "long"
        else -> "unknown($type)"
    }

    /**
     * Owns only the presses [takesPress] accepts while [shouldHandle] permits it; every other press
     * passes through to the factory assistant.
     *
     * The key service marks each press with `ecarx.extra.ECARX_KEY_ACTION_TYPE` — 0 for a short press,
     * 1 for a long one — so the caller picks which of the two DiPlay owns and leaves the other alone.
     */
    fun registerVoice(context: Context, takesPress: (Int) -> Boolean = { it == 0 },
        shouldHandle: () -> Boolean, onPress: (ordered: Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_VOICE) return
                val press = intent.getIntExtra(EXTRA_ACTION_TYPE, -1)
                val hy = intent.getIntExtra(EXTRA_HY, 0)
                // HY's own `a$e` receiver sits at the same MAX priority and aborts every press it
                // sees, so the pass lines here are what separates "we let it go on purpose" from
                // "someone higher in the chain ate it and we never ran".
                val pass = when {
                    hy == 1 -> "pass reason=hy-marker"
                    !takesPress(press) -> "pass reason=press-not-owned"
                    !shouldHandle() -> "pass reason=not-eligible"
                    else -> null
                }
                if (pass != null) {
                    Log.i(TAG, "voice broadcast press=${pressLabel(press)} hy=$hy $pass")
                    return
                }
                if (isOrderedBroadcast) abortBroadcast()
                Log.i(TAG, "voice broadcast press=${pressLabel(press)} hy=$hy take ordered=$isOrderedBroadcast")
                onPress(isOrderedBroadcast)
            }
        }
        val filter = IntentFilter(ACTION_VOICE).apply {
            priority = Int.MAX_VALUE
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        // Sent by the head unit's key service: Android 13+ needs the receiver marked exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        Log.i(TAG, "voice broadcast receiver registered priority=MAX")
        return receiver
    }

    /** Registers the call-key broadcast; [onPress] runs on the broadcast's thread. */
    fun registerCall(context: Context, onPress: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_CALL) return
                abortBroadcast()
                Log.i(TAG, "call key take ordered=$isOrderedBroadcast")
                onPress()
            }
        }
        val filter = IntentFilter(ACTION_CALL).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY }
        // Sent by the head unit's key service: Android 13+ needs the receiver marked exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        Log.i(TAG, "call key receiver registered priority=${filter.priority}")
        return receiver
    }
}
