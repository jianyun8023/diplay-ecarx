package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (NativeMedia.available && android.os.Build.VERSION.SDK_INT >= 28) NativeMedia.videoTouchSent(sentAtNs)
        else if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        if (NativeMedia.available && android.os.Build.VERSION.SDK_INT >= 28) return NativeMedia.videoTouchFrame(nowNs)
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }
}
