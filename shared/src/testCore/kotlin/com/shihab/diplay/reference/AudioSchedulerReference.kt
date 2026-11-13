@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.reference

/** Test-only reference from the Kotlin owner at6cd98d70; never compiled into an app variant. */
class AudioSchedulerReference(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun reset() {
        writtenBytes = 0
        playedFrames = 0
        lastHead = 0
    }
}
