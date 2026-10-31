package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.media.session.MediaSession
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
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
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
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
        // This title/position update used to start a new artwork session, dropping in-flight bytes.
        `when`(controller.nowPlayingSnapshot()).thenReturn(song.copy(title = "B", elapsedMillis = 0))
        call("onNowPlayingChanged", controller); idle()
        assertSame(owner, field<Any>("artworkOwner"))
        assertSame(art, field<MutableMap<Int, Bitmap?>>("artworkCache")[7])
        var shown: Bitmap? = null
        val stop = ClusterMusicState.observe { shown = it.artwork }
        assertSame(art, shown)
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

    @Test fun missingCoverRecoveryWorksForEcarxEvenWithTheOverlayDisabled() {
        seed(CarPlayNowPlaying(title = "A", artworkTransferId = 7))
        ClusterMusicSettings.saveEnabled(context, true)
        assertEquals(true, call("albumRecoveryEnabled"))
        ClusterMusicSettings.saveEnabled(context, false)
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
}
