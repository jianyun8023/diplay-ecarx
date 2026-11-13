package com.shihab.diplay

import com.shihab.diplay.media.CarPlayNowPlaying

/**
 * Artwork transfer ids are transport handles, not media identity. Title and artist are also
 * excluded because some iPhones/apps reuse those fields for rapidly changing lyrics.
 */
internal fun albumTrackChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean {
    fun changed(old: Any?, new: Any?) = old != null && old != new
    if (changed(previous.sourceApp, next.sourceApp) || changed(previous.album, next.album) ||
        changed(previous.durationMillis, next.durationMillis)) return true

    // A playback-position restart is the remaining signal for a new item within the same album.
    // It may also represent an explicit seek-to-start; that is safer than treating lyrics as a track.
    return previous.elapsedMillis?.let { it > 15_000 } == true &&
        next.elapsedMillis?.let { it < 3_000 } == true
}
