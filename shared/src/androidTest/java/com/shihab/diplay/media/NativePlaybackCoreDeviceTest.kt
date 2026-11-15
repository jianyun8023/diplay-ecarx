package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackCoreDeviceTest {
    @Test fun nowPlayingIncrementalFieldsAndPlatformTextMatchTheKotlinContract() {
        assertTrue(NativeMedia.available)
        PlaybackCoreContract.verifyNowPlaying()
    }
    @Test fun glanceSongBlankTitleRulesMatchTheKotlinContract() {
        assertTrue(NativeMedia.available)
        PlaybackCoreContract.verifyGlanceSong()
    }
    @Test fun navigationRendererLeasesAndQuietTailMatchTheKotlinContract() {
        assertTrue(NativeMedia.available)
        PlaybackCoreContract.verifyNavigation()
    }
}
