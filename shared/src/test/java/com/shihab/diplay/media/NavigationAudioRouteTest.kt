package com.shihab.diplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationAudioRouteTest {
    @Test fun theIphonesDuckPicksTheCarChannelAndItsAbsenceTheMusicStream() {
        assertEquals(NavigationAudioRoute.CAR_CHANNEL, NavigationAudioRoute.choose(true, configured = 3, iphoneDucking = true))
        assertEquals(AudioManager.STREAM_MUSIC, NavigationAudioRoute.choose(true, configured = 0, iphoneDucking = false))
        // Off: the configured channel, as before.
        assertEquals(5, NavigationAudioRoute.choose(false, configured = 5, iphoneDucking = true))
    }

    @Test fun promptsOnTheMusicStreamFollowTheCarsNavigationVolume() {
        // 1.5 dB per car volume step; the PCM can only be lowered.
        assertEquals(-6, CarNavigationVolume.relativeDb(mediaStep = 10, navigationStep = 6))
        assertEquals(0, CarNavigationVolume.relativeDb(mediaStep = 4, navigationStep = 17))
        assertEquals(NavigationAudioGain.MIN_DB, CarNavigationVolume.relativeDb(mediaStep = 39, navigationStep = 0))
    }
}
