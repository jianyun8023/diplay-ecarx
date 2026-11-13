package com.shihab.diplay

import com.shihab.diplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class AlbumTrackIdentityTest {
    private val track = CarPlayNowPlaying(title = "song", artist = "artist", album = "album",
        sourceApp = "Music", durationMillis = 180_000, artworkTransferId = 1,
        elapsedMillis = 60_000, playing = true)

    @Test fun titleAndArtistLyricUpdatesDoNotChangeTrackIdentity() {
        repeat(200) {
            assertFalse(albumTrackChanged(track, track.copy(
                title = "title lyric $it", artist = "artist lyric $it",
                elapsedMillis = 60_000L + it * 500,
            )))
        }
        assertFalse(albumTrackChanged(track, track.copy(title = "lyric", artist = "lyric")))
    }
    @Test fun artworkTransferIdChangesDoNotInvalidateTheCurrentTrack() {
        repeat(200) { id ->
            assertFalse(albumTrackChanged(track, track.copy(artworkTransferId = id)))
        }
        assertFalse(albumTrackChanged(track, track.copy(artworkTransferId = null)))
    }
    @Test fun playbackRestartAndStableMetadataChangesIdentifyANewTrack() {
        assertTrue(albumTrackChanged(track, track.copy(elapsedMillis = 0)))
        assertTrue(albumTrackChanged(track, track.copy(album = "other")))
        assertTrue(albumTrackChanged(track, track.copy(sourceApp = "other")))
        assertTrue(albumTrackChanged(track, track.copy(durationMillis = 240_000)))
    }
    @Test fun titleAndArtistChangesDoNotBecomeIdentityEvenWithNoOtherChanges() {
        assertFalse(albumTrackChanged(track, track.copy(title = "another title")))
        assertFalse(albumTrackChanged(track, track.copy(artist = "another artist")))
    }
    @Test fun realTrackChangesInvalidateOldCover() {
        assertTrue(albumTrackChanged(track, track.copy(album = "other")))
        assertTrue(albumTrackChanged(track, track.copy(durationMillis = 240_000)))
        assertTrue(albumTrackChanged(track, track.copy(title = "next", elapsedMillis = 0)))
    }
    @Test fun delayedFirstReferenceAndSourceAreAccepted() {
        assertFalse(albumTrackChanged(CarPlayNowPlaying(), track.copy(artworkTransferId = null)))
        assertFalse(albumTrackChanged(track.copy(artworkTransferId = null), track))
        assertFalse(albumTrackChanged(track.copy(sourceApp = null), track.copy(sourceApp = "Music")))
    }
}
