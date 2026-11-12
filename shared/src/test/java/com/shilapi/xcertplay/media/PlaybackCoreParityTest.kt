package com.shilapi.xcertplay.media

import org.junit.Test

class PlaybackCoreParityTest {
    @Test fun nowPlayingMatchesTheKotlinContract() = PlaybackCoreContract.verifyNowPlaying()
    @Test fun navigationMatchesTheKotlinContract() = PlaybackCoreContract.verifyNavigation()
}
