package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia

/** Rust aggregates diagnostics across short prompts and decides when a bounded summary is due. */
internal class MediaHealth(private val report: (String) -> Unit) {
    init { NativeMedia.requireAvailable() }
    private val token = NativeMedia.healthCreate()

    @Synchronized fun started() { NativeMedia.healthStep(token, null, 0, System.nanoTime() / 1_000_000, null) }
    @Synchronized fun audio(previous: LongArray, values: LongArray) {
        NativeMedia.healthStep(token, previous, 1, System.nanoTime() / 1_000_000, values)?.let { runCatching { report(it) } }
    }
    @Synchronized fun audioTrackChanged(previous: LongArray) {
        NativeMedia.healthStep(token, previous, 5, 0, null)
    }
    @Synchronized fun video(values: LongArray) {
        NativeMedia.healthStep(token, null, 2, System.nanoTime() / 1_000_000, values)?.let { runCatching { report(it) } }
    }
    @Synchronized fun finish(): Boolean {
        val line = NativeMedia.healthStep(token, null, 3, System.nanoTime() / 1_000_000, null) ?: return false
        runCatching { report(line) }
        return true
    }
    @Synchronized fun snapshot(): String = checkNotNull(NativeMedia.healthStep(token, null, 4, 0, null))
}
