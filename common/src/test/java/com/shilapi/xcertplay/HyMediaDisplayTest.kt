package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HyMediaDisplayTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val artwork = Uri.parse("http://127.0.0.1:12345/current.png")

    @Test fun dashboardContractIsTargetedAndContainsOnlyDisplayFields() {
        HyMediaDisplay.publish(app, CarPlayNowPlaying(
            title = "Track", artist = "Artist", album = "Album", durationMillis = 123_000, playing = true,
        ), artwork)

        val intent = shadowOf(app).broadcastIntents.single()
        assertEquals("com.hyphp.hywebview.HyMediaReceiver", intent.action)
        assertEquals("com.hyphp.hywebview", intent.`package`)
        val expected = mapOf(
            "method" to "dashboard", "getTrackName" to "Track", "getArtistName" to "Artist",
            "getAlbumName" to "Album", "getDuration" to "123000", "getArtwork" to artwork.toString(),
            "appName" to app.applicationInfo.loadLabel(app.packageManager).toString(),
            "pkgName" to app.packageName, "playState" to "1",
        )
        assertEquals(expected.keys, intent.extras!!.keySet())
        expected.forEach { (key, value) -> assertEquals(value, intent.getStringExtra(key)) }
    }

    @Test fun missingMetadataAndPausedStateKeepHyWireDefaults() {
        HyMediaDisplay.publish(app, CarPlayNowPlaying(playing = false), artwork)
        val intent = shadowOf(app).broadcastIntents.single()
        for (key in listOf("getTrackName", "getArtistName", "getAlbumName")) {
            assertEquals("", intent.getStringExtra(key))
        }
        assertEquals("0", intent.getStringExtra("getDuration"))
        assertEquals("0", intent.getStringExtra("playState"))
    }

    @Test fun missingCoverExplicitlyClearsTheOldUriWithoutDelayingTextOrState() {
        HyMediaDisplay.publish(app, CarPlayNowPlaying(title = "New track", playing = false), null)
        val intent = shadowOf(app).broadcastIntents.single()
        assertEquals("New track", intent.getStringExtra("getTrackName"))
        assertEquals("0", intent.getStringExtra("playState"))
        assertTrue(intent.hasExtra("getArtwork"))
        assertEquals("", intent.getStringExtra("getArtwork"))
    }

    @Test fun rejectedBroadcastDoesNotEscapeIntoMediaSessionUpdates() {
        val failure = SecurityException("broadcast rejected")
        val context = object : ContextWrapper(app) {
            override fun sendBroadcast(intent: Intent) { throw failure }
        }
        HyMediaDisplay.publish(context, CarPlayNowPlaying(), artwork)
        assertTrue(ShadowLog.getLogsForTag("DiPlay-MediaKeys").any { it.throwable === failure })
        assertTrue(shadowOf(app).broadcastIntents.isEmpty())
    }
}
