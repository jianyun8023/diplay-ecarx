package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.mediacenter.MediaCenterTransport
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.ArgumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual MediaKeys listeners → production router/engine → fake OEM endpoints. No system Binder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaOutputRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val engine get() = field(CarPlayMediaOutput, "engine").get(CarPlayMediaOutput)!!
    private val controllers = mutableListOf<CarPlayController>()
    private val ecarx = mutableListOf<Transport>()
    private lateinit var savedEngine: Map<String, Any?>
    private var savedTicking = false
    private var savedScheduled = false
    private var savedDirty = false
    private var savedMaster = true
    private var savedFocus = false
    private var claim = true
    private var accepted = true

    @Before fun setup() {
        savedMaster = EcarxOutputSettings.mediaEnabled(app)
        savedFocus = AirPlayPersistence.loadDisableMediaKeyAudioFocus(app)
        EcarxOutputSettings.setMediaEnabled(app, true)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        CarPlayBackgroundSession.clear()
        savedTicking = field(CarPlayMediaOutput, "ticking").getBoolean(CarPlayMediaOutput)
        field(CarPlayMediaOutput, "ticking").setBoolean(CarPlayMediaOutput, true)
        savedEngine = listOf("worker", "factory", "desired", "status", "registeredOwner", "callbackOwner",
            "operationAt", "operation", "operationOwner", "transport", "currentOwner", "failedOwner", "timedOutOwner",
            "uncertainOwner", "last", "lastArtwork", "selectionOwner").associateWith { field(engine, it).get(engine) }
        savedScheduled = (field(engine, "scheduled").get(engine) as AtomicBoolean).get()
        savedDirty = (field(engine, "dirty").get(engine) as AtomicBoolean).get()
        field(engine, "worker").set(engine, Executor { it.run() })
        val factory: (Any, () -> Boolean) -> MediaCenterTransport = { _, _ -> Transport().also(ecarx::add) }
        field(engine, "factory").set(engine, factory)
    }

    @After fun cleanup() {
        field(engine, "worker").set(engine, Executor { it.run() })
        try {
            controllers.forEach(CarPlayMediaKeys::detach)
            CarPlayMediaOutput.end()
            CarPlayBackgroundSession.clear()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            savedEngine.forEach { (name, value) -> field(engine, name).set(engine, value) }
            (field(engine, "scheduled").get(engine) as AtomicBoolean).set(savedScheduled)
            (field(engine, "dirty").get(engine) as AtomicBoolean).set(savedDirty)
            field(CarPlayMediaOutput, "ticking").setBoolean(CarPlayMediaOutput, savedTicking)
            EcarxOutputSettings.setMediaEnabled(app, savedMaster)
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, savedFocus)
        }
    }

    @Test fun disabledMasterPreventsActualRouterRegistrationDespiteActiveMedia() {
        EcarxOutputSettings.setMediaEnabled(app, false)
        val attached = attach("Do not register")
        attached.now(song("Do not register")); tickEngine()
        assertTrue(ecarx.isEmpty())
        assertFalse(CarPlayMediaOutput.isMediaCenterActive(attached.controller))
    }

    @Test fun androidSessionPublishesMetadataAndStandardControlsWithMediaCenterDisabled() {
        EcarxOutputSettings.setMediaEnabled(app, false)
        val attached = attach("Android track", 7)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's system session Binder does not retain controller metadata; observe the
        // actual publications at the Android MediaSession boundary instead.
        val session = spy(field(CarPlayMediaKeys, "session").get(CarPlayMediaKeys) as MediaSession)
        field(CarPlayMediaKeys, "session").set(CarPlayMediaKeys, session)
        attached.cover(7, png()); awaitArtwork(); tickEngine()
        assertTrue(session.isActive)
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("active=true"))
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("titlePresent=true"))
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("artworkDecoded=true"))
        assertFalse(CarPlayMediaKeys.diagnosticReport().contains("Android track"))
        val published = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session, atLeastOnce()).setMetadata(published.capture())
        val metadata = published.value
        assertEquals("Android track", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertTrue(ecarx.isEmpty())
        assertFalse(CarPlayMediaOutput.isMediaCenterActive())

        val callback = field(CarPlayMediaKeys, "callback").get(CarPlayMediaKeys) as MediaSession.Callback
        val dispatchCounts = Regex("attempts=(\\d+) sent=(\\d+)").find(CarPlayMediaKeys.diagnosticReport())!!.groupValues
        `when`(attached.controller.sendMediaButton(anyInt())).thenReturn(true)
        clearInvocations(attached.controller)
        callback.onPlay(); callback.onPause(); callback.onSkipToNext(); callback.onSkipToPrevious()
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PLAY)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains(
            "attempts=${dispatchCounts[1].toLong() + 4} sent=${dispatchCounts[2].toLong() + 4}"))
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("source=previous"))
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("sent=true atEpochMs="))
        `when`(attached.controller.sendMediaButton(CarPlayMediaButton.NEXT)).thenReturn(false)
        callback.onSkipToNext()
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains(
            "attempts=${dispatchCounts[1].toLong() + 5} sent=${dispatchCounts[2].toLong() + 4}"))
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("sent=false atEpochMs="))
        `when`(attached.controller.requestSiri()).thenReturn(true)
        assertTrue(CarPlayMediaKeys.onExternalSiriKey())

        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(song("Android track", 7).copy(playing = false, elapsedMillis = 12_000))
        attached.now(song("Android track", 7).copy(playing = false, elapsedMillis = 12_000))
        shadowOf(Looper.getMainLooper()).idle()
        val states = ArgumentCaptor.forClass(PlaybackState::class.java)
        verify(session, atLeastOnce()).setPlaybackState(states.capture())
        val playback = states.value
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertEquals(12_000L, playback.position)
        assertTrue("paused media remains controllable", session.isActive)
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("iphonePlaying=false elapsedMs=12000"))
        CarPlayMediaKeys.detach(attached.controller)
        assertFalse(session.isActive)
        assertTrue(CarPlayMediaKeys.diagnosticReport().contains("controllerAttached=false created=false active=false"))
    }

    @Test fun externalMediaKeysWaitForAcceptedClaimButSiriRemainsAvailable() {
        claim = false
        val attached = attach("Playing")
        attached.now(song("Playing"))
        assertFalse(CarPlayMediaOutput.isMediaCenterActive(attached.controller))
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        clearInvocations(attached.controller)

        attached.now(song("Playing").copy(playing = false))
        claim = true
        attached.now(song("Playing"))
        assertTrue(CarPlayMediaOutput.isMediaCenterActive(attached.controller))
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller, never()).sendMediaButton(anyInt())
        `when`(attached.controller.requestSiri()).thenReturn(true)
        assertTrue(CarPlayMediaKeys.onExternalSiriKey())
        verify(attached.controller).requestSiri()

        CarPlayMediaKeys.detach(attached.controller)
    }

    @Test fun claimedButRejectedPlaybackDoesNotSuppressExistingWheelPath() {
        accepted = false
        val attached = attach("Rejected card")
        attached.now(song("Rejected card"))
        assertTrue(ecarx.single().registered)
        assertFalse(CarPlayMediaOutput.isMediaCenterActive(attached.controller))
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        clearInvocations(attached.controller)
        accepted = true
        attached.now(song("Accepted card"))
        assertTrue(CarPlayMediaOutput.isMediaCenterActive(attached.controller))
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller, never()).sendMediaButton(anyInt())
    }

    @Test fun legacyBackendPreferencesAreIgnoredAndOldCallbacksCannotReplaceNewMedia() {
        val prefs = app.getSharedPreferences("diplay_media_output", Context.MODE_PRIVATE)
        for (legacy in listOf("DIM", "STANDARD")) {
            prefs.edit().putString("backend", legacy).commit()
            val old = attach("ECARX old", 7)
            old.cover(7, png()); awaitArtwork()
            assertTrue(ecarx.last().registered)
            val artwork = checkNotNull(ecarx.last().frames.last { it.second != null }.second)
            val fetched = java.net.URL(artwork.toString()).openStream().use { it.readBytes() }
            assertEquals(0x89.toByte(), fetched[0])
            assertEquals("PNG", fetched.copyOfRange(1, 4).toString(Charsets.US_ASCII))
            CarPlayMediaKeys.detach(old.controller)
            assertFalse(ecarx.last().registered)
            assertTrue(runCatching { java.net.URL(artwork.toString()).openStream().use { it.readBytes() } }.isFailure)
            val next = attach("ECARX new")
            old.now(song("stale song")); old.cover(7, png()); awaitArtwork()
            assertTrue(ecarx.last().registered)
            assertFalse(ecarx.last().frames.any { it.first.title == "stale song" })
            assertTrue(ecarx.last().frames.any { it.first.title == "ECARX new" })
            CarPlayMediaKeys.detach(next.controller)
        }
        prefs.edit().remove("backend").commit()
    }

    private data class Attached(val controller: CarPlayController,
        val now: (CarPlayNowPlaying) -> Unit, val cover: (Int, ByteArray) -> Unit)
    private fun attach(title: String, artwork: Int? = null): Attached {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        `when`(controller.nowPlayingSnapshot()).thenReturn(song(title, artwork))
        var now: ((CarPlayNowPlaying) -> Unit)? = null
        var cover: ((Int, ByteArray) -> Unit)? = null
        doAnswer { now = it.getArgument(0); null }.`when`(controller).nowPlayingListener = any()
        doAnswer { cover = it.getArgument(0); null }.`when`(controller).artworkListener = any()
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480, this,
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayBackgroundSession.active = true
        CarPlayMediaKeys.attach(app, controller)
        shadowOf(Looper.getMainLooper()).idle()
        return Attached(controller, checkNotNull(now), checkNotNull(cover))
    }
    private fun awaitArtwork() {
        val queue = field(CarPlayMediaKeys, "artworkQueue").get(CarPlayMediaKeys) as NowPlayingArtworkQueue<*>
        val done = CountDownLatch(1)
        queue.execute(Runnable { done.countDown() }); assertTrue(done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try { java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun tickEngine() { (engine as com.shilapi.xcertplay.mediacenter.MediaCenterSessionEngine).tick() }
    private fun song(title: String, artwork: Int? = null) = CarPlayNowPlaying(title = title, artworkTransferId = artwork, durationMillis = 90_000, playing = true)
    private fun singleton(name: String): Any = Class.forName(name).getField("INSTANCE").get(null)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private inner class Transport : MediaCenterTransport {
        override var registered = false
        val frames = mutableListOf<Pair<CarPlayNowPlaying, Uri?>>()
        var unregisterCalls = 0
        override fun connect() = Unit
        override fun register(now: CarPlayNowPlaying, artwork: Uri?) { registered = true }
        override fun requestPlay() = claim
        override fun publish(now: CarPlayNowPlaying, artwork: Uri?): Boolean { frames += now to artwork; return accepted }
        override fun clear() = Unit
        override fun unregister(): Boolean { unregisterCalls++; registered = false; return true }
        override fun close() = Unit
    }
}
