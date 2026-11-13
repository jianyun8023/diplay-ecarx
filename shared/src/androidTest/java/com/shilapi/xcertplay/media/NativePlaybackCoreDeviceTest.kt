package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackCoreDeviceTest {
    @Test fun nowPlayingIncrementalFieldsAndPlatformTextMatchTheKotlinContract() {
        assertTrue(NativeMedia.available)
        PlaybackCoreContract.verifyNowPlaying()
    }
    @Test fun navigationRendererLeasesAndQuietTailMatchTheKotlinContract() {
        assertTrue(NativeMedia.available)
        PlaybackCoreContract.verifyNavigation()
    }
}
