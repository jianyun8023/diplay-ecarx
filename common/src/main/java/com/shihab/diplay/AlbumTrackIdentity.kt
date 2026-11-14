package com.shihab.diplay

import com.shihab.diplay.media.CarPlayNowPlaying
import com.shihab.diplay.media.PlaybackMetadataCore

/** Transfer IDs and frequently changing lyric fields do not identify a track. */
internal fun albumTrackChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
    PlaybackMetadataCore.trackChanged(previous, next)
