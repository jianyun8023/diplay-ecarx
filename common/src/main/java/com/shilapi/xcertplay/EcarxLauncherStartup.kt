package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ECARX launcher starts the head unit's services in batches, beginning when its home screen
 * first resumes: it then sends `EAS_LAUNCH_BAT_0ST`, and the later batches follow even with another
 * app in front. A CarPlay screen opened at user unlock covered the home screen before it ever
 * resumed, so no batch started; among them ecarx.xsf.inputservice, which sends the steering-wheel
 * voice-key broadcast, and Siri stopped answering that key (car test 2026-11-07: launcher created
 * 1.3 s after LOCKED_BOOT_COMPLETED, never resumed; on 11-06 it resumed at 1.7 s and sent batch 0).
 */
internal object EcarxLauncherStartup {
    private const val TAG = "xcertplay-boot"
    private const val ACTION_FIRST_BATCH = "com.ecarx.intent.action.EAS_LAUNCH_BAT_0ST"
    /** Covers the launcher's 1.7 s; past it the launch goes ahead as before. */
    const val WAIT_MILLIS = 5_000L

    /** Runs [action] once on the main thread, after the launcher's first batch or [WAIT_MILLIS]. */
    fun afterFirstBatch(context: Context, action: (waitedMs: Long, seen: Boolean) -> Unit) {
        val app = context.applicationContext
        val started = SystemClock.elapsedRealtime()
        val handler = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver
        fun finish(seen: Boolean) {
            if (!done.compareAndSet(false, true)) return
            runCatching { app.unregisterReceiver(receiver) }
            val waited = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "launcher first batch seen=$seen waitedMs=$waited")
            action(waited, seen)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action == ACTION_FIRST_BATCH) handler.post { finish(true) }
            }
        }
        val registered = runCatching { app.registerReceiver(receiver, IntentFilter(ACTION_FIRST_BATCH)) }.isSuccess
        if (!registered) {
            finish(false)
            return
        }
        handler.postDelayed({ finish(false) }, WAIT_MILLIS)
    }
}
