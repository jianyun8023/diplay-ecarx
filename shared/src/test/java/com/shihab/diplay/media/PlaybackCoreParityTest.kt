package com.shihab.diplay.media

import org.junit.Test

class PlaybackCoreParityTest {
    @Test fun nowPlayingMatchesTheKotlinContract() = PlaybackCoreContract.verifyNowPlaying()
    @Test fun glanceSongMatchesTheKotlinContract() = PlaybackCoreContract.verifyGlanceSong()
    @Test fun navigationMatchesTheKotlinContract() = PlaybackCoreContract.verifyNavigation()
}
