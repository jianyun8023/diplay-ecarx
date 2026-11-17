package com.shihab.diplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts the Rust video-URL playability rule (protocols::airplay::video op 10). */
class VideoInCarUrlPolicyContractTest {
    @Test
    fun remoteSchemesArePlayable() {
        assertTrue(VideoInCar.isPlayableUrl("http://host/x"))
        assertTrue(VideoInCar.isPlayableUrl("HTTPS://host/x"))
        assertTrue(VideoInCar.isPlayableUrl("https://host/\uFFFD"))
    }

    @Test
    fun localAndFairplaySchemesAreNeverPlayable() {
        for (url in listOf(
            "file:/x", "FILE://host", "content://x", "asset:", "rawresource:",
            "android.resource:", "skd:", "SKD://host",
        )) {
            assertFalse(url, VideoInCar.isPlayableUrl(url, true))
        }
    }

    @Test
    fun appSchemesAreAcceptedOnlyWhenTheIphoneLoadsThem() {
        assertFalse(VideoInCar.isPlayableUrl("myapp://x"))
        assertTrue(VideoInCar.isPlayableUrl("myapp://x", true))
        // A scheme must start with a letter and contain only letters, digits, +, . or -.
        assertFalse(VideoInCar.isPlayableUrl("1app://x", true))
        assertFalse(VideoInCar.isPlayableUrl("app_name://x", true))
        assertTrue(VideoInCar.isPlayableUrl("a+b.c-d://x", true))
    }
}
