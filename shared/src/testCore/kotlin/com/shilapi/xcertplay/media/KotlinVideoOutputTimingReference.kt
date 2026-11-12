package com.shilapi.xcertplay.media

/** The old Java decoder's slot/pause/render calculations, independent of the production Rust owner. */
internal class KotlinVideoOutputTimingReference(initialDelay: Long) {
    private val presentation = LongArray(64)
    private val queuedAt = LongArray(64)
    private val paced = BooleanArray(64)
    private val beforePause = BooleanArray(64)
    private val recentPaced = IntArray(3) { -1 }
    private var next = 0
    private var lastQueued = 0L
    private var resumedAt = 0L
    private var lastLocal = 0L
    private val delay = if (initialDelay > 0) KotlinPacingDelay(initialDelay) else null
    fun queued(presentationMicros: Long, localNanos: Long, now: Long) {
        if (lastQueued != 0L && now - lastQueued > 500_000_000) resumedAt = now
        lastQueued = now
        presentation[next] = presentationMicros
        queuedAt[next] = now
        paced[next] = localNanos > 0
        beforePause[next] = false
        if (localNanos > 0) {
            if (lastLocal != 0L && localNanos - lastLocal > 120_000_000) {
                recentPaced.forEach { if (it >= 0) beforePause[it] = true }
            }
            lastLocal = localNanos
            recentPaced.copyInto(recentPaced, 1, 0, 2)
            recentPaced[0] = next
        }
        next = (next + 1) % presentation.size
    }
    fun plan(presentationMicros: Long, now: Long, shown: Boolean): VideoOutputTiming.Plan {
        val slot = presentation.indices.firstOrNull { presentation[it] == presentationMicros } ?: -1
        val queued = if (slot >= 0) queuedAt[slot] else 0L
        val held = queued in 1 until resumedAt
        val isPaced = shown && delay != null && slot >= 0 && paced[slot]
        val local = presentationMicros * 1_000
        val pauseHeld = isPaced && beforePause[slot]
        val target = if (isPaced && delay != null) {
            if (!held && !pauseHeld) delay.onFrame(now - local)
            local + delay.nanos
        } else 0L
        val scheduled = target - now in 1..1_000_000_000
        return VideoOutputTiming.Plan(if (queued > 0) now - queued else 0, queued > 0, held,
            if (isPaced) delay?.nanos else null, if (scheduled) target else null,
            !scheduled && shown && delay != null && !held && !pauseHeld)
    }
}
