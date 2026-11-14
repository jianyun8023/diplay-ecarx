package com.shihab.diplay.media

/** Both MediaCodec adapters share the Rust backlog recovery policy, including its startup grace. */
internal class VideoBacklogRecovery {
    private val owner = NativeVideoTiming.backlogCreate()
    fun reset() { NativeVideoTiming.backlogEvent(owner, 1, 0, 0, 0, 0, false) }
    fun decoderStarted(nowNs: Long) { NativeVideoTiming.backlogEvent(owner, 2, nowNs, 0, 0, 0, false) }
    fun observe(nowNs: Long, currentReceivedNs: Long, pendingFrames: Int, newestPendingReceivedNs: Long?): Boolean =
        NativeVideoTiming.backlogEvent(owner, 0, nowNs, currentReceivedNs, pendingFrames, newestPendingReceivedNs ?: 0, newestPendingReceivedNs != null)
}
