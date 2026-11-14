package com.shihab.diplay.airplay

import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts one stream's microphone at most once: at the first downlink packet, or [SILENT_START_MILLIS]
 * after SETUP when the Rust microphone plan says the stream starts without downlink audio. An app
 * voice message (Douyin, #566) opens a speech-recognition stream without downlink audio, and capture
 * waited for a packet that never came. Calls still wait for their downlink: call capture uses the
 * far-end audio as its echo reference. The per-audioType rule travels with the plan
 * (`waitDownlink`); this class keeps only the platform start guard.
 */
internal class MicrophoneStart(private val start: () -> Unit) {
    private val claimed = AtomicBoolean()
    @Volatile private var silentStart: Future<*>? = null

    /** The first downlink packet arrived. */
    fun onDownlink() {
        silentStart?.cancel(false)
        if (claimed.compareAndSet(false, true)) start()
    }

    /**
     * Starts capture after [delayMillis] unless the downlink started it first. [whileCurrent] runs the
     * given start only while the stream still owns its microphone; the start returns true when it
     * started capture.
     */
    fun scheduleSilentStart(
        scheduler: ScheduledExecutorService,
        delayMillis: Long,
        whileCurrent: (startNow: () -> Boolean) -> Unit,
    ) {
        silentStart = scheduler.schedule({
            whileCurrent {
                if (claimed.compareAndSet(false, true)) {
                    start()
                    true
                } else {
                    false
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** The scheduling parameter for a stream whose plan allows a silent start. */
        const val SILENT_START_MILLIS = 500L
    }
}
