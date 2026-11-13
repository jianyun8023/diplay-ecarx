package com.shihab.diplay

import android.graphics.Bitmap
import android.media.session.MediaSession
import android.os.Looper
import com.shihab.diplay.media.CarPlayNowPlaying
import com.shihab.diplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterArtworkDeliveryTest {
    private val controller = mock(CarPlayController::class.java)
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun call(name: String, vararg args: Any?) = CarPlayMediaSession.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(CarPlayMediaSession, *args)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(CarPlayMediaSession, name, value)
    private fun <T> field(name: String): T = ReflectionHelpers.getField(CarPlayMediaSession, name)

    @After fun clean() {
        CarPlayMediaSession.detach(controller)
        context.getSharedPreferences("diplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences(MediaSessionSettings.PREFS, 0).edit().clear().commit()
        set("lastHyPublication", null)
        idle()
    }

    private fun seed(song: CarPlayNowPlaying): Bitmap {
        set("controller", controller)
        set("appContext", context)
        set("nowPlaying", song)
        set("artworkOwner", Any())
        val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        set("artwork", art)
        field<MutableMap<Int, Bitmap?>>("artworkCache")[song.artworkTransferId!!] = art
        ClusterMusicState.publish(song, art); idle()
        return art
    }

    @Test fun sameReferenceMetadataDoesNotCancelDecodeOrEraseCachedCover() {
        val song = CarPlayNowPlaying(title = "A", artist = "Artist", artworkTransferId = 7, elapsedMillis = 90_000)
        val art = seed(song)
        val owner = field<Any>("artworkOwner")
        // Title and artist may carry lyrics; their updates must not replace the current cover.
        `when`(controller.nowPlayingSnapshot()).thenReturn(song.copy(title = "lyric B", artist = "lyric artist"))
        call("onNowPlayingChanged", controller); idle()
        assertSame(owner, field<Any>("artworkOwner"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
        var shown: Bitmap? = null
        val stop = ClusterMusicState.observe { shown = it.artwork }
        assertSame(art, shown)
        stop()
    }

    @Test fun artworkArrivingBeforeMetadataIsAdoptedWhenTheReferenceArrives() {
        val song = CarPlayNowPlaying(title = "A", artist = "Artist", album = "Album",
            sourceApp = "Music", durationMillis = 180_000, artworkTransferId = 7,
            elapsedMillis = 60_000, playing = true)
        val art = seed(song)
        val owner = field<Any>("artworkOwner")
        val cached = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cachedUri = android.net.Uri.parse("http://127.0.0.1:12345/cover-8.png")
        field<MutableMap<Int, Bitmap?>>("artworkCache")[8] = cached
        field<MutableMap<Int, android.net.Uri>>("clusterArtworkUris")[8] = cachedUri
        `when`(controller.nowPlayingSnapshot()).thenReturn(song.copy(artworkTransferId = 8))
        call("onNowPlayingChanged", controller); idle()
        assertSame(owner, field<Any>("artworkOwner"))
        assertSame(cached, field<Bitmap>("artwork"))
        assertEquals(cachedUri, field<android.net.Uri>("artworkUri"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
    }

    @Test fun transferIdChangeForTheSameTrackKeepsTheDecodedCoverAndMetadataIdentity() {
        val song = CarPlayNowPlaying(title = "A", artist = "Artist", album = "Album",
            sourceApp = "Music", durationMillis = 180_000, artworkTransferId = 7,
            elapsedMillis = 60_000, playing = true)
        val art = seed(song)
        val next = song.copy(artworkTransferId = 8)
        assertFalse(albumTrackChanged(song, next))
        assertFalse(CarPlayMediaSession.metadataChanged(song, next))
        `when`(controller.nowPlayingSnapshot()).thenReturn(next)
        call("onNowPlayingChanged", controller); idle()
        assertSame(art, field<Bitmap>("artwork"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
    }

    @Test fun titleAndArtistLyricUpdatesDoNotAdvanceClusterTrackRevision() {
        val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
            sourceApp = "Music", durationMillis = 180_000, artworkTransferId = 7,
            elapsedMillis = 60_000, playing = true)
        val art = seed(song)
        var revision = -1L
        val stop = ClusterMusicState.observe { revision = it.revision }
        val initialRevision = revision
        ClusterMusicState.publish(song.copy(title = "lyric 1", artist = "lyric 1",
            elapsedMillis = 60_500), art)
        idle()
        assertEquals(initialRevision, revision)
        ClusterMusicState.publish(song.copy(title = "lyric 2", artist = "lyric 2",
            elapsedMillis = 61_000), art)
        idle()
        assertEquals(initialRevision, revision)
        stop()
    }

    @Test fun failedDecodeDoesNotOverwriteValidCoverAndLateOldOwnerCannotPublish() {
        val art = seed(CarPlayNowPlaying(title = "A", artworkTransferId = 7))
        call("onArtworkDecoded", field<Any>("artworkOwner"), 7, null)
        idle()
        assertSame(art, field<Bitmap>("artwork"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
        call("onArtworkDecoded", Any(), 7, null)
        assertSame(art, field<Bitmap>("artwork"))
    }

    @Test fun newReferenceKeepsPreviousCoverWhileItsTransferIsPending() {
        val song = CarPlayNowPlaying(title = "A", artworkTransferId = 7)
        val art = seed(song)
        `when`(controller.nowPlayingSnapshot()).thenReturn(song.copy(title = "B", artworkTransferId = 8))
        call("onNowPlayingChanged", controller); idle()
        var shown: Bitmap? = null
        val stop = ClusterMusicState.observe { shown = it.artwork }
        assertSame(art, shown)
        CarPlayMediaSession.detach(controller); idle()
        assertNull(shown)
        stop()
    }

    @Test fun artworkRecoveryStaysEnabledForEcarx() {
        seed(CarPlayNowPlaying(title = "A", artworkTransferId = 7))
        assertEquals(true, call("albumRecoveryEnabled"))
    }

    @Test fun hyDoesNotClearAnotherSourceBeforePhoneMetadataOrDuplicateDirectEcarx() {
        seed(CarPlayNowPlaying(artworkTransferId = 7))
        set("session", MediaSession(context, "test").apply { isActive = true })
        set("nowPlaying", CarPlayNowPlaying())
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        call("publishHyDisplayLocked")
        assertTrue(shadowOf(context).broadcastIntents.isEmpty())
        val song = CarPlayNowPlaying(title = "A", playing = true)
        set("nowPlaying", song)
        `when`(controller.nowPlayingSnapshot()).thenReturn(song)
        set("outputMode", MediaSessionMode.ECARX)
        call("publishHyDisplayLocked")
        assertTrue(shadowOf(context).broadcastIntents.isEmpty())
    }

    @Test fun hyArtworkSwitchOffLeavesOnlyTheStandardSessionAndOnResendsAtOnce() {
        val song = CarPlayNowPlaying(title = "A", playing = true, artworkTransferId = 7)
        seed(song)
        set("session", MediaSession(context, "test").apply { isActive = true })
        set("outputMode", MediaSessionMode.ANDROID)
        `when`(controller.nowPlayingSnapshot()).thenReturn(song)
        MediaSessionSettings.setHyArtworkBroadcast(context, false)
        call("publishHyDisplayLocked")
        assertTrue(shadowOf(context).broadcastIntents.isEmpty())
        MediaSessionSettings.setHyArtworkBroadcast(context, true)
        call("publishHyDisplayLocked")
        val sent = shadowOf(context).broadcastIntents.single()
        assertEquals("com.hyphp.hywebview.HyMediaReceiver", sent.action)
        assertEquals("A", sent.getStringExtra("getTrackName"))
    }
}
