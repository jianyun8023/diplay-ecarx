package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a broadcast open until asynchronous follow-up work settles or [timeoutMillis] passes.
 *
 * A manifest receiver's process has no component alive once `onReceive` returns, so work it
 * posted to the main looper may never run when nothing else holds the process. `goAsync()` keeps
 * the receiver, and with it the process, at broadcast priority until [release]; the timeout keeps
 * a chain that never settles inside the platform's broadcast budget. [release] is idempotent and
 * safe from any thread.
 */
internal class BroadcastHold(
    timeoutMillis: Long,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val finish: () -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val timeout = Runnable { release() }

    init {
        handler.postDelayed(timeout, timeoutMillis)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        runCatching(finish).onFailure { Log.w(TAG, "Broadcast finish failed", it) }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
