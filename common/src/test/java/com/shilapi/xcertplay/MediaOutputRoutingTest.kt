package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import com.shilapi.xcertplay.mediacenter.EcarxMediaCenterTransport
import com.shilapi.xcertplay.mediacenter.EcarxMediaCenterTransportTest.Platform
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual media-session listeners → production engine → fake OEM endpoints. No system Binder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaOutputRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val engine get() = field(CarPlayMediaSession, "mediaCenterEngine").get(CarPlayMediaSession)!!
    private val controllers = mutableListOf<CarPlayController>()
    private val ecarx = mutableListOf<Transport>()
    private lateinit var savedEngine: Map<String, Any?>
    private var savedTicking = false
    private var savedScheduled = false
    private var savedDirty = false
    private var savedMaster = true
    private var savedFocus = false
    private var savedMode = MediaSessionMode.ANDROID
    private var claim = true
    private var accepted = true
    private var callbackPlatform: Platform? = null

    @Before fun setup() {
        savedMaster = EcarxOutputSettings.mediaEnabled(app)
        savedFocus = AirPlayPersistence.loadDisableMediaKeyAudioFocus(app)
        savedMode = MediaSessionSettings.mode(app)
        MediaSessionSettings.setMode(app, MediaSessionMode.ECARX)
        EcarxOutputSettings.setMediaEnabled(app, true)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        CarPlayBackgroundSession.clear()
        savedTicking = field(CarPlayMediaSession, "mediaCenterTicking").getBoolean(CarPlayMediaSession)
        field(CarPlayMediaSession, "mediaCenterTicking").setBoolean(CarPlayMediaSession, true)
        savedEngine = listOf("worker", "factory", "desired", "status", "registeredOwner", "callbackOwner",
            "operationAt", "operation", "operationOwner", "transport", "currentOwner", "failedOwner", "timedOutOwner",
            "uncertainOwner", "last", "lastArtwork", "selectionOwner").associateWith { field(engine, it).get(engine) }
        savedScheduled = (field(engine, "scheduled").get(engine) as AtomicBoolean).get()
        savedDirty = (field(engine, "dirty").get(engine) as AtomicBoolean).get()
        field(engine, "worker").set(engine, Executor { it.run() })
        val factory: (Any, () -> Boolean) -> MediaCenterTransport = { identity, valid ->
            val platform = callbackPlatform
            if (platform == null) Transport().also(ecarx::add)
            else {
                val dispatch = CarPlayMediaSession.javaClass.declaredMethods.single { it.name == "dispatchMediaCenter" }
                    .apply { isAccessible = true }
                EcarxMediaCenterTransport(app, { command ->
                    dispatch.invoke(CarPlayMediaSession, identity, command) as Boolean
                }, valid, platform)
            }
        }
        field(engine, "factory").set(engine, factory)
    }

    @After fun cleanup() {
        field(engine, "worker").set(engine, Executor { it.run() })
        try {
            controllers.forEach(CarPlayMediaSession::detach)
            CarPlayBackgroundSession.clear()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            savedEngine.forEach { (name, value) -> field(engine, name).set(engine, value) }
            (field(engine, "scheduled").get(engine) as AtomicBoolean).set(savedScheduled)
            (field(engine, "dirty").get(engine) as AtomicBoolean).set(savedDirty)
            field(CarPlayMediaSession, "mediaCenterTicking").setBoolean(CarPlayMediaSession, savedTicking)
            EcarxOutputSettings.setMediaEnabled(app, savedMaster)
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, savedFocus)
            MediaSessionSettings.setMode(app, savedMode)
        }
    }

    @Test fun disabledMasterPreventsActualRouterRegistrationDespiteActiveMedia() {
        EcarxOutputSettings.setMediaEnabled(app, false)
        val attached = attach("Do not register")
        attached.now(song("Do not register")); tickEngine()
        assertTrue(ecarx.isEmpty())
        assertFalse(CarPlayMediaSession.isMediaCenterActive(attached.controller))
    }

    @Test fun artworkDiagnosticRequestsRetainTransitionsButDoNotLogProgressOrTrackText() {
        val attached = attach("Private title", 7)
        attached.cover(7, png()); awaitArtwork()
        val events = field(CarPlayMediaSession, "mediaPublicationEvents").get(CarPlayMediaSession) as ArrayDeque<*>
        val before = events.toList()
        val next = song("Private title", 7).copy(elapsedMillis = 1_000)
        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(next)
        attached.now(next); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, events.toList())

        val changed = song("Another private title", 8)
        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(changed)
        attached.now(changed); shadowOf(Looper.getMainLooper()).idle()
        attached.cover(8, png()); awaitArtwork()
        val report = CarPlayMediaSession.mediaCenterDiagnosticReport()
        assertTrue(report.contains("artwork=true->false"))
        assertTrue(report.contains("artwork=false->true"))
        assertTrue(report.contains("reason=artwork-decoded"))
        assertFalse(report.contains("Private title"))
        assertFalse(report.contains("Another private title"))
        assertFalse(report.contains("http://"))
        assertTrue(events.size <= 32)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun cachedReferenceNeverPublishesAnEmptyCoverDuringMetadataChanges() {
        val attached = attach("First", 7)
        attached.cover(7, png(Color.RED)); awaitArtwork()
        val transport = ecarx.last()
        val uri = transport.frames.last().second
        assertNotNull(uri)
        transport.frames.clear()
        val next = song("Next", 7).copy(artist = "New artist", durationMillis = 123_000)
        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(next)
        attached.now(next); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(transport.frames.isNotEmpty())
        assertTrue(transport.frames.all { it.second == uri })

        attached.cover(7, png(Color.RED)); awaitArtwork()
        assertEquals(uri, transport.frames.last().second)
        attached.cover(7, png(Color.BLUE)); awaitArtwork()
        val replacement = transport.frames.last().second
        assertNotNull(replacement)
        assertNotEquals(uri, replacement)
        val image = java.net.URL(replacement.toString()).openStream().use {
            android.graphics.BitmapFactory.decodeStream(it)
        }!!
        assertEquals(Color.BLUE, image.getPixel(0, 0))
    }

    @Test fun androidSessionPublishesMetadataAndStandardControlsWithMediaCenterDisabled() {
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        EcarxOutputSettings.setMediaEnabled(app, false)
        val attached = attach("Android track", 7)
        CarPlayMediaSession.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's system session Binder does not retain controller metadata; observe the
        // actual publications at the Android MediaSession boundary instead.
        val session = spy(field(CarPlayMediaSession, "session").get(CarPlayMediaSession) as MediaSession)
        field(CarPlayMediaSession, "session").set(CarPlayMediaSession, session)
        attached.cover(7, png()); awaitArtwork(); tickEngine()
        assertTrue(session.isActive)
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("active=true"))
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("titlePresent=true"))
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("artworkDecoded=true"))
        assertFalse(CarPlayMediaSession.diagnosticReport().contains("Android track"))
        val published = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session, atLeastOnce()).setMetadata(published.capture())
        val metadata = published.value
        assertEquals("Android track", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertTrue(ecarx.isEmpty())
        assertFalse(CarPlayMediaSession.isMediaCenterActive())

        val callback = field(CarPlayMediaSession, "callback").get(CarPlayMediaSession) as MediaSession.Callback
        val dispatchCounts = Regex("attempts=(\\d+) sent=(\\d+)").find(CarPlayMediaSession.diagnosticReport())!!.groupValues
        `when`(attached.controller.sendMediaButton(anyInt())).thenReturn(true)
        clearInvocations(attached.controller)
        callback.onPlay(); callback.onPause(); callback.onSkipToNext(); callback.onSkipToPrevious()
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PLAY)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        assertTrue(CarPlayMediaSession.diagnosticReport().contains(
            "attempts=${dispatchCounts[1].toLong() + 4} sent=${dispatchCounts[2].toLong() + 4}"))
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("source=previous"))
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("sent=true atEpochMs="))
        `when`(attached.controller.sendMediaButton(CarPlayMediaButton.NEXT)).thenReturn(false)
        callback.onSkipToNext()
        assertTrue(CarPlayMediaSession.diagnosticReport().contains(
            "attempts=${dispatchCounts[1].toLong() + 5} sent=${dispatchCounts[2].toLong() + 4}"))
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("sent=false atEpochMs="))
        `when`(attached.controller.requestSiri()).thenReturn(true)
        assertFalse(CarPlayMediaSession.onExternalSiriKey())

        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(song("Android track", 7).copy(playing = false, elapsedMillis = 12_000))
        attached.now(song("Android track", 7).copy(playing = false, elapsedMillis = 12_000))
        shadowOf(Looper.getMainLooper()).idle()
        val states = ArgumentCaptor.forClass(PlaybackState::class.java)
        verify(session, atLeastOnce()).setPlaybackState(states.capture())
        val playback = states.value
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertEquals(12_000L, playback.position)
        assertTrue("paused media remains controllable", session.isActive)
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("iphonePlaying=false elapsedMs=12000"))
        CarPlayMediaSession.detach(attached.controller)
        assertFalse(session.isActive)
        assertTrue(CarPlayMediaSession.diagnosticReport().contains("controllerAttached=false created=false active=false"))
    }

    @Test fun externalMediaKeysWaitForAcceptedClaimButSiriRemainsAvailable() {
        claim = false
        val attached = attach("Playing")
        attached.now(song("Playing"))
        assertFalse(CarPlayMediaSession.isMediaCenterActive(attached.controller))
        CarPlayMediaSession.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        clearInvocations(attached.controller)

        attached.now(song("Playing").copy(playing = false))
        claim = true
        attached.now(song("Playing"))
        assertTrue(CarPlayMediaSession.isMediaCenterActive(attached.controller))
        CarPlayMediaSession.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller, never()).sendMediaButton(anyInt())
        `when`(attached.controller.requestSiri()).thenReturn(true)
        assertTrue(CarPlayMediaSession.onExternalSiriKey())
        verify(attached.controller).requestSiri()

        CarPlayMediaSession.detach(attached.controller)
    }

    @Test fun claimedButRejectedPlaybackDoesNotSuppressExistingWheelPath() {
        accepted = false
        val attached = attach("Rejected card")
        attached.now(song("Rejected card"))
        assertTrue(ecarx.single().registered)
        assertFalse(CarPlayMediaSession.isMediaCenterActive(attached.controller))
        CarPlayMediaSession.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        clearInvocations(attached.controller)
        accepted = true
        attached.now(song("Accepted card"))
        assertTrue(CarPlayMediaSession.isMediaCenterActive(attached.controller))
        CarPlayMediaSession.onExternalMediaKey(CarPlayMediaButton.NEXT)
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
            CarPlayMediaSession.detach(old.controller)
            assertFalse(ecarx.last().registered)
            assertTrue(runCatching { java.net.URL(artwork.toString()).openStream().use { it.readBytes() } }.isFailure)
            val next = attach("ECARX new")
            old.now(song("stale song")); old.cover(7, png()); awaitArtwork()
            assertTrue(ecarx.last().registered)
            assertFalse(ecarx.last().frames.any { it.first.title == "stale song" })
            assertTrue(ecarx.last().frames.any { it.first.title == "ECARX new" })
            CarPlayMediaSession.detach(next.controller)
        }
        prefs.edit().remove("backend").commit()
    }

    @Test fun vendorCallbacksDispatchOnceOnMainThroughTheRealTransportAndRouter() {
        val platform = Platform().also { callbackPlatform = it }
        val attached = attach("Callback track")
        val client = checkNotNull(platform.service.client)
        doAnswer {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            true
        }.`when`(attached.controller).sendMediaButton(anyInt())
        clearInvocations(attached.controller)
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                assertTrue(client.onPlay()); assertTrue(client.onPause())
                assertTrue(client.onNext()); assertTrue(client.onPrevious())
            }.get(3, TimeUnit.SECONDS)
            verify(attached.controller, never()).sendMediaButton(anyInt())
            shadowOf(Looper.getMainLooper()).idle()
            val ordered = inOrder(attached.controller)
            for (command in listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE,
                CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS)) {
                ordered.verify(attached.controller).sendMediaButton(command)
            }
            verify(attached.controller, times(4)).sendMediaButton(anyInt())
            assertTrue(CarPlayMediaSession.diagnosticReport().contains("source=mediacenter"))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun disconnectDropsQueuedVendorCommandsAndRejectsLaterCallbacks() {
        val platform = Platform().also { callbackPlatform = it }
        val attached = attach("Disconnect")
        val client = checkNotNull(platform.service.client)
        assertTrue(client.onNext())
        CarPlayMediaSession.detach(attached.controller)
        assertFalse(client.onNext())
        shadowOf(Looper.getMainLooper()).idle()
        verify(attached.controller, never()).sendMediaButton(anyInt())
    }

    @Test fun queuedOldVendorCommandsCannotControlTheReplacementSession() {
        val platform = Platform().also { callbackPlatform = it }
        val old = attach("Old")
        val client = checkNotNull(platform.service.client)
        assertTrue(client.onNext())
        val replacementPlatform = Platform().also { callbackPlatform = it }
        val replacement = attach("New")
        assertFalse(client.onNext())
        verify(old.controller, never()).sendMediaButton(anyInt())
        verify(replacement.controller, never()).sendMediaButton(anyInt())
        assertTrue(checkNotNull(replacementPlatform.service.client).onPrevious())
        shadowOf(Looper.getMainLooper()).idle()
        verify(replacement.controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
    }

    @Test fun sourceSelectionClaimsOnceAndSendsOnePlayCommand() {
        val platform = Platform().also { callbackPlatform = it }
        val attached = attach("Select source")
        val client = checkNotNull(platform.service.client)
        val claims = platform.service.claims
        assertFalse(client.onSourceSelected(1))
        assertTrue(client.onSourceSelected(6))
        verify(attached.controller, never()).sendMediaButton(anyInt())
        shadowOf(Looper.getMainLooper()).idle()
        tickEngine()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(claims + 1, platform.service.claims)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PLAY)
        verify(attached.controller, times(1)).sendMediaButton(anyInt())
    }

    @Test fun acceptedVendorCallbackReportsPhoneFailureWithoutRetrying() {
        val platform = Platform().also { callbackPlatform = it }
        val attached = attach("Rejected command")
        `when`(attached.controller.sendMediaButton(anyInt())).thenReturn(false)
        assertTrue(checkNotNull(platform.service.client).onNext())
        shadowOf(Looper.getMainLooper()).idle()
        tickEngine()
        shadowOf(Looper.getMainLooper()).idle()
        verify(attached.controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
        assertTrue(CarPlayMediaSession.diagnosticReport().contains(
            "source=mediacenter index=${CarPlayMediaButton.NEXT} sent=false"))
    }

    @Test fun disablingMediaOutputDropsAnAlreadyQueuedVendorCommand() {
        val platform = Platform().also { callbackPlatform = it }
        val attached = attach("Disable output")
        val client = checkNotNull(platform.service.client)
        assertTrue(client.onNext())
        EcarxOutputSettings.setMediaEnabled(app, false)
        shadowOf(Looper.getMainLooper()).idle()
        verify(attached.controller, never()).sendMediaButton(anyInt())
        assertFalse(client.onNext())
    }

    @Test fun vendorMediaKeyKeepsTheCarVideoPlayerPriority() {
        val platform = Platform().also { callbackPlatform = it }
        val attached = attach("Car video")
        val activity = field(CarPlayVideo, "activity")
        val previous = activity.get(CarPlayVideo)
        val player = mock(CarPlayVideoActivity::class.java)
        try {
            activity.set(CarPlayVideo, player)
            assertTrue(checkNotNull(platform.service.client).onNext())
            shadowOf(Looper.getMainLooper()).idle()
            verify(player).skip(10_000)
            verify(attached.controller, never()).sendMediaButton(anyInt())
        } finally {
            activity.set(CarPlayVideo, previous)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun outputModesAreExclusiveAndSwitchOnlyOnReconnect() {
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        val android = attach("Android", 7)
        CarPlayMediaSession.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        android.cover(7, png()); awaitArtwork()
        val standard = field(CarPlayMediaSession, "session").get(CarPlayMediaSession) as MediaSession
        assertTrue(standard.isActive)
        assertTrue(ecarx.isEmpty())
        assertFalse(CarPlayMediaSession.managesVehicleKeys())
        CarPlayMediaSession.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(android.controller, never()).sendMediaButton(anyInt())
        val callback = field(CarPlayMediaSession, "callback").get(CarPlayMediaSession) as MediaSession.Callback
        callback.onSkipToNext()
        verify(android.controller).sendMediaButton(CarPlayMediaButton.NEXT)

        MediaSessionSettings.setMode(app, MediaSessionMode.ECARX)
        android.now(song("Android", 7)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ecarx.isEmpty())
        assertTrue(CarPlayMediaSession.mediaCenterDiagnosticReport().contains("active=ANDROID saved=ECARX"))
        CarPlayMediaSession.detach(android.controller)
        assertFalse(standard.isActive)
        val vendor = attach("ECARX", 7)
        CarPlayMediaSession.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        vendor.cover(7, png()); awaitArtwork()
        assertNull(field(CarPlayMediaSession, "session").get(CarPlayMediaSession))
        assertTrue(CarPlayMediaSession.managesVehicleKeys())
        assertTrue(CarPlayMediaSession.isMediaCenterActive(vendor.controller))
        val transport = ecarx.single()
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        vendor.now(song("ECARX", 7)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(transport.registered)
        assertNull(field(CarPlayMediaSession, "session").get(CarPlayMediaSession))
        CarPlayMediaSession.detach(vendor.controller)
        assertEquals(1, transport.unregisterCalls)
        val again = attach("Android again", 7)
        CarPlayMediaSession.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        again.cover(7, png()); awaitArtwork()
        assertTrue((field(CarPlayMediaSession, "session").get(CarPlayMediaSession) as MediaSession).isActive)
        assertEquals(1, ecarx.size)
        assertFalse(transport.registered)
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == "com.hyphp.hywebview.HyMediaReceiver" })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun androidMetadataNeverLabelsThePreviousCoverAsTheNextTrack() {
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        val attached = attach("First", 7)
        CarPlayMediaSession.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        val session = spy(field(CarPlayMediaSession, "session").get(CarPlayMediaSession) as MediaSession)
        field(CarPlayMediaSession, "session").set(CarPlayMediaSession, session)
        val published = ArgumentCaptor.forClass(MediaMetadata::class.java)
        attached.cover(7, png(Color.RED)); awaitArtwork()
        verify(session, atLeastOnce()).setMetadata(published.capture())
        val oldArt = published.value.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)!!
        val oldUri = published.value.description.iconUri
        assertNotNull(oldUri)
        val next = song("Second", 8).copy(durationMillis = 123_000)
        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(next)
        attached.now(next); shadowOf(Looper.getMainLooper()).idle()
        verify(session, atLeastOnce()).setMetadata(published.capture())
        assertEquals("Second", published.value.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNull(published.value.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNull(published.value.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        assertNull(published.value.description.iconUri)
        attached.cover(8, png(Color.BLUE)); awaitArtwork()
        verify(session, atLeastOnce()).setMetadata(published.capture())
        val newArt = published.value.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)!!
        val newUri = published.value.description.iconUri
        assertEquals(oldArt.rowBytes, newArt.rowBytes)
        assertEquals(Color.BLUE, newArt.getPixel(0, 0))
        assertNotEquals(oldUri, newUri)
        val image = java.net.URL(newUri.toString()).openStream().use {
            android.graphics.BitmapFactory.decodeStream(it)
        }!!
        assertEquals(Color.BLUE, image.getPixel(0, 0))
        clearInvocations(session)
        val progress = next.copy(elapsedMillis = 10_000, playing = false)
        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(progress)
        attached.now(progress); shadowOf(Looper.getMainLooper()).idle()
        verify(session, never()).setMetadata(any())
        attached.cover(99, png(Color.GREEN)); awaitArtwork()
        verify(session, never()).setMetadata(any())
        assertTrue(ecarx.isEmpty())
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == "com.hyphp.hywebview.HyMediaReceiver" })
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
        CarPlayMediaSession.attach(app, controller)
        shadowOf(Looper.getMainLooper()).idle()
        return Attached(controller, checkNotNull(now), checkNotNull(cover))
    }
    private fun awaitArtwork() {
        val queue = field(CarPlayMediaSession, "artworkQueue").get(CarPlayMediaSession) as NowPlayingArtworkQueue<*>
        val done = CountDownLatch(1)
        queue.execute(Runnable { done.countDown() }); assertTrue(done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun png(color: Int = Color.TRANSPARENT): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
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
