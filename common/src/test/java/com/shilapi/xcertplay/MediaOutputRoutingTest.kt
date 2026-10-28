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
import com.shilapi.xcertplay.hud.MediaOutputBackendSettings
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.mediacenter.MediaCenterTransport
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.CopyOnWriteArrayList
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
    private val bridge = singleton("com.shilapi.xcertplay.hud.EcarxMediaBridge")
    private val projection = singleton("com.shilapi.xcertplay.hud.EcarxProjectionChannel")
    private val engine get() = field(CarPlayMediaOutput, "engine").get(CarPlayMediaOutput)!!
    private val controllers = mutableListOf<CarPlayController>()
    private val uni = mutableListOf<Transport>()
    private val dim = DimReceiver()
    private lateinit var savedBridge: Map<String, Any?>
    private lateinit var savedProjection: Map<String, Any?>
    private lateinit var savedEngine: Map<String, Any?>
    private var savedTicking = false
    private var savedBarrier = false
    private var savedScheduled = false
    private var savedDirty = false
    private val workers = mutableListOf<Thread>()
    private val initializationErrors = CopyOnWriteArrayList<Throwable>()
    private val releaseBlocked = CountDownLatch(1)
    private var registerAction: () -> Unit = {}
    private var unregisterAction: () -> Unit = {}
    private var unregisterResult = true
    private var savedMaster = true
    private var savedFocus = false
    private var savedBackend = MediaOutputBackendSettings.Backend.UNI_MEDIACENTER
    private var claim = true
    private var accepted = true

    @Before fun setup() {
        savedMaster = EcarxOutputSettings.mediaEnabled(app)
        savedFocus = AirPlayPersistence.loadDisableMediaKeyAudioFocus(app)
        savedBackend = MediaOutputBackendSettings.load(app)
        EcarxOutputSettings.setMediaEnabled(app, true)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.UNI_MEDIACENTER)
        CarPlayBackgroundSession.clear()
        savedBarrier = MediaOutputBackendSettings.dimBlocked()
        savedTicking = field(CarPlayMediaOutput, "ticking").getBoolean(CarPlayMediaOutput)
        field(CarPlayMediaOutput, "ticking").setBoolean(CarPlayMediaOutput, true)
        savedEngine = listOf("worker", "factory", "desired", "status", "registeredOwner", "callbackOwner",
            "operationAt", "operation", "operationOwner", "transport", "currentOwner", "failedOwner", "timedOutOwner",
            "uncertainOwner", "last", "lastArtwork", "selectionOwner").associateWith { field(engine, it).get(engine) }
        savedScheduled = (field(engine, "scheduled").get(engine) as AtomicBoolean).get()
        savedDirty = (field(engine, "dirty").get(engine) as AtomicBoolean).get()
        field(engine, "worker").set(engine, Executor { it.run() })
        val factory: (Any, () -> Boolean) -> MediaCenterTransport = { _, _ -> Transport().also(uni::add) }
        field(engine, "factory").set(engine, factory)
        val defaults = linkedMapOf(
            "context" to app, "available" to true, "dim" to Any(), "media" to dim,
            "trackInterface" to PlaybackInfo::class.java, "itemInterface" to null,
            "wanted" to null, "pushed" to null, "sourceTypeSent" to false, "sourceListSent" to false,
            "secondsSinceReport" to 0, "secondsSincePush" to 0, "lastProgress" to -1L, "progressMeasuredAt" to 0L)
        savedBridge = defaults.keys.associateWith { field(bridge, it).get(bridge) }
        defaults.forEach { (name, value) -> field(bridge, name).set(bridge, value) }
        savedProjection = listOf("queued", "mediaWanted").associateWith { field(projection, it).get(projection) }
        field(projection, "queued").set(projection, true)
    }

    @After fun cleanup() {
        releaseBlocked.countDown()
        workers.forEach { it.join(5_000); assertFalse("test worker must terminate", it.isAlive) }
        assertTrue("initializer threads must complete normally: $initializationErrors", initializationErrors.isEmpty())
        unregisterResult = true
        unregisterAction = {}
        registerAction = {}
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
            if (savedBarrier) MediaOutputBackendSettings.blockDim() else MediaOutputBackendSettings.releaseDimBarrier()
            field(CarPlayMediaOutput, "ticking").setBoolean(CarPlayMediaOutput, savedTicking)
            savedBridge.forEach { (name, value) -> field(bridge, name).set(bridge, value) }
            savedProjection.forEach { (name, value) -> field(projection, name).set(projection, value) }
            EcarxOutputSettings.setMediaEnabled(app, savedMaster)
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, savedFocus)
            MediaOutputBackendSettings.save(app, savedBackend)
        }
    }

    @Test fun actualTrackAndArtworkUseOnlyUniUntilExplicitDimChoiceStartsANewSession() {
        val attached = attach("UNI song", 7)
        attached.now(song("UNI song", 7))
        attached.cover(7, png())
        awaitArtwork()
        tickDim()
        assertTrue("real router registers only UNI", uni.single().registered)
        assertTrue(uni.single().frames.any { it.second != null })
        assertTrue("UNI song and cover must not dispatch DIM", dim.cards.isEmpty())

        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.DIM)
        attached.now(song("Still UNI", 7))
        tickDim()
        assertTrue("saving a preference does not hot-switch the existing owner", dim.cards.isEmpty())
        assertTrue(uni.single().frames.any { it.first.title == "Still UNI" })

        CarPlayMediaKeys.detach(attached.controller)
        assertFalse(uni.single().registered)
        val next = attach("Explicit DIM")
        next.now(song("Explicit DIM")); tickDim()
        assertEquals("Explicit DIM", dim.cards.last())
        assertEquals("DIM must not create another MediaCenter transport", 1, uni.size)
    }

    @Test fun disabledMasterPreventsActualRouterRegistrationDespiteActiveMedia() {
        EcarxOutputSettings.setMediaEnabled(app, false)
        val attached = attach("Do not register")
        attached.now(song("Do not register")); tickDim()
        assertTrue(uni.isEmpty())
        assertTrue(dim.cards.isEmpty())
        assertFalse(CarPlayMediaOutput.isMediaCenterActive(attached.controller))
    }

    @Test fun standardModePublishesAndroidSessionAndControlsWithoutEitherOemBackend() {
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.STANDARD)
        EcarxOutputSettings.setMediaEnabled(app, false)
        val attached = attach("HY track", 7)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's system session Binder does not retain controller metadata; observe the
        // actual publications at the Android MediaSession boundary instead.
        val session = spy(field(CarPlayMediaKeys, "session").get(CarPlayMediaKeys) as MediaSession)
        field(CarPlayMediaKeys, "session").set(CarPlayMediaKeys, session)
        attached.cover(7, png()); awaitArtwork(); tickDim()
        assertTrue(session.isActive)
        val published = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session, atLeastOnce()).setMetadata(published.capture())
        val metadata = published.value
        assertEquals("HY track", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertTrue(uni.isEmpty())
        assertTrue(dim.cards.isEmpty())
        assertFalse(CarPlayMediaOutput.isMediaCenterActive())
        assertTrue(MediaOutputBackendSettings.dimBlocked())

        val callback = field(CarPlayMediaKeys, "callback").get(CarPlayMediaKeys) as MediaSession.Callback
        clearInvocations(attached.controller)
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(attached.controller, never()).sendMediaButton(anyInt())
        callback.onPlay(); callback.onPause(); callback.onSkipToNext(); callback.onSkipToPrevious()
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PLAY)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(attached.controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        `when`(attached.controller.requestSiri()).thenReturn(true)
        assertTrue(CarPlayMediaKeys.onExternalSiriKey())

        `when`(attached.controller.nowPlayingSnapshot()).thenReturn(song("HY track", 7).copy(playing = false, elapsedMillis = 12_000))
        attached.now(song("HY track", 7).copy(playing = false, elapsedMillis = 12_000))
        shadowOf(Looper.getMainLooper()).idle()
        val states = ArgumentCaptor.forClass(PlaybackState::class.java)
        verify(session, atLeastOnce()).setPlaybackState(states.capture())
        val playback = states.value
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertEquals(12_000L, playback.position)
        assertTrue("paused media remains controllable", session.isActive)
        CarPlayMediaKeys.detach(attached.controller)
        assertFalse(session.isActive)
    }

    @Test fun standardSelectionWaitsForNextOwnerAndCleansPreviousUniRegistration() {
        val old = attach("UNI")
        assertTrue(uni.single().registered)
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.STANDARD)
        assertFalse(MediaOutputBackendSettings.standardOnly(app))
        old.now(song("Still UNI"))
        assertTrue(uni.single().frames.any { it.first.title == "Still UNI" })
        CarPlayMediaKeys.detach(old.controller)
        assertFalse(uni.single().registered)
        val next = attach("HY")
        next.now(song("HY")); tickDim()
        assertTrue(MediaOutputBackendSettings.standardOnly(app))
        assertEquals(1, uni.size)
        assertTrue(dim.cards.isEmpty())
    }

    @Test fun externalMediaKeysWaitForAcceptedClaimButSiriRemainsAvailableAndDimRestoresKeys() {
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
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.DIM)
        val next = attach("DIM")
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(next.controller).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun claimedButRejectedPlaybackDoesNotSuppressExistingWheelPath() {
        accepted = false
        val attached = attach("Rejected card")
        attached.now(song("Rejected card"))
        assertTrue(uni.single().registered)
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

    @Test fun queuedUnregisterKeepsDimBlockedUntilWorkerAcknowledgesCleanup() {
        val old = attach("UNI queued cleanup")
        val queue = ArrayDeque<Runnable>()
        field(engine, "worker").set(engine, Executor { queue.addLast(it) })
        CarPlayMediaKeys.detach(old.controller)
        val next = chooseDimAndAttach("DIM waits for queue")
        next.now(song("DIM waits for queue"))
        tickDim()
        assertTrue(MediaOutputBackendSettings.dimBlocked())
        assertTrue(dim.cards.isEmpty())
        assertEquals(0, uni.single().unregisterCalls)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        tickDim()
        assertEquals(1, uni.single().unregisterCalls)
        assertFalse(MediaOutputBackendSettings.dimBlocked())
        assertEquals("DIM waits for queue", dim.cards.last())
    }

    @Test fun blockedUnregisterCannotLetTheNewDimOwnerPublish() {
        val old = attach("UNI blocking cleanup")
        val entered = CountDownLatch(1)
        unregisterAction = { entered.countDown(); check(releaseBlocked.await(5, TimeUnit.SECONDS)) }
        asyncWorker()
        CarPlayMediaKeys.detach(old.controller)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        chooseDimAndAttach("DIM waits for Binder")
        tickDim()
        assertTrue(MediaOutputBackendSettings.dimBlocked())
        assertTrue(dim.cards.isEmpty())
        releaseBlocked.countDown()
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        tickDim()
        assertFalse(MediaOutputBackendSettings.dimBlocked())
        assertEquals("DIM waits for Binder", dim.cards.last())
    }

    @Test fun refusedUnregisterKeepsDimBlockedEvenAfterTheWorkerReturns() {
        val old = attach("UNI cleanup refused")
        unregisterResult = false
        CarPlayMediaKeys.detach(old.controller)
        chooseDimAndAttach("Must not publish DIM")
        tickDim()
        assertEquals(1, uni.single().unregisterCalls)
        assertTrue(CarPlayMediaOutput.diagnostics().contains("uncertain=true"))
        assertTrue(MediaOutputBackendSettings.dimBlocked())
        assertTrue(dim.cards.isEmpty())
    }

    @Test fun lateRegistrationTokenIsCleanedBeforeDimCanTakeOver() {
        val entered = CountDownLatch(1)
        registerAction = { entered.countDown(); check(releaseBlocked.await(5, TimeUnit.SECONDS)) }
        asyncWorker()
        val old = attach("Late UNI token")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        CarPlayMediaKeys.detach(old.controller)
        chooseDimAndAttach("DIM waits for late token")
        tickDim()
        assertTrue(MediaOutputBackendSettings.dimBlocked())
        assertTrue(dim.cards.isEmpty())
        releaseBlocked.countDown()
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(1, uni.single().unregisterCalls)
        assertFalse(uni.single().registered)
        assertTrue("obsolete UNI owner must never publish after registration returns", uni.single().frames.isEmpty())
        tickDim()
        assertFalse(MediaOutputBackendSettings.dimBlocked())
        assertEquals("DIM waits for late token", dim.cards.last())
    }

    @Test fun uniSelectionWithoutMediaOrRegistrationDoesNotPermanentlyBlockTheNextDimSession() {
        val emptyOwner = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaOutput.begin(app, emptyOwner)
        assertTrue(MediaOutputBackendSettings.dimBlocked())
        assertTrue(uni.isEmpty())
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.DIM)
        val dimOwner = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaOutput.begin(app, dimOwner)
        CarPlayMediaOutput.publish(song("First real DIM media"))
        tickDim()
        assertTrue("no UNI owner ever had real media", uni.isEmpty())
        assertFalse(MediaOutputBackendSettings.dimBlocked())
        assertEquals("First real DIM media", dim.cards.last())
    }

    @Test fun framePublishedDuringDimInitializationIsNotOverwrittenByCachedOldFrame() {
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.DIM)
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaOutput.begin(app, controller)
        CarPlayMediaOutput.publish(song("Old cached frame"))
        val entered = CountDownLatch(1)
        val initializing = blockDimInitializer(entered)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        CarPlayMediaOutput.publish(song("New frame during initialization"))
        tickDim()
        assertEquals("New frame during initialization", dim.cards.last())
        releaseBlocked.countDown()
        initializing.join(5_000)
        assertFalse(initializing.isAlive)
        tickDim()
        assertEquals("New frame during initialization", dim.cards.last())
        assertFalse("the initial cached frame must never replay over the new frame", dim.cards.contains("Old cached frame"))
    }

    @Test fun oldDimInitializerCannotPublishAfterANewDimOwnerTakesOver() {
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.DIM)
        val old = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaOutput.begin(app, old)
        CarPlayMediaOutput.publish(song("Old owner's cached frame"))
        val entered = CountDownLatch(1)
        val initializing = blockDimInitializer(entered)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val next = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaOutput.begin(app, next)
        CarPlayMediaOutput.publish(song("New DIM owner"))
        tickDim()
        assertEquals("New DIM owner", dim.cards.last())
        releaseBlocked.countDown()
        initializing.join(5_000)
        assertFalse(initializing.isAlive)
        tickDim()
        assertEquals("New DIM owner", dim.cards.last())
        assertFalse("stale initializer must not emit its old owner's frame", dim.cards.contains("Old owner's cached frame"))
    }

    private fun blockDimInitializer(entered: CountDownLatch): Thread {
        val initialize: (Context) -> Unit = {
            entered.countDown()
            check(releaseBlocked.await(5, TimeUnit.SECONDS)) { "test did not release initializer" }
        }
        val method = CarPlayMediaOutput.javaClass.getDeclaredMethod("refreshDimBarrier",
            kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        return Thread({
            try { method.invoke(CarPlayMediaOutput, initialize) }
            catch (error: Throwable) { initializationErrors += error }
        }, "test-dim-initializer").also { workers += it; it.start() }
    }

    private fun chooseDimAndAttach(title: String): Attached {
        MediaOutputBackendSettings.save(app, MediaOutputBackendSettings.Backend.DIM)
        return attach(title)
    }
    private fun asyncWorker() {
        field(engine, "worker").set(engine, Executor { task ->
            Thread(task, "test-media-router-worker").also { workers += it; it.start() }
        })
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
    private fun tickDim() {
        CarPlayMediaOutput.javaClass.getDeclaredMethod("refreshDimBarrier").apply { isAccessible = true }.invoke(CarPlayMediaOutput)
        bridge.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(bridge)
    }
    private fun song(title: String, artwork: Int? = null) = CarPlayNowPlaying(title = title, artworkTransferId = artwork, durationMillis = 90_000, playing = true)
    private fun singleton(name: String): Any = Class.forName(name).getField("INSTANCE").get(null)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private inner class Transport : MediaCenterTransport {
        override var registered = false
        val frames = mutableListOf<Pair<CarPlayNowPlaying, Uri?>>()
        var unregisterCalls = 0
        override fun connect() = Unit
        override fun register(now: CarPlayNowPlaying, artwork: Uri?) { registerAction(); registered = true }
        override fun requestPlay() = claim
        override fun publish(now: CarPlayNowPlaying, artwork: Uri?): Boolean { frames += now to artwork; return accepted }
        override fun clear() = Unit
        override fun unregister(): Boolean { unregisterCalls++; unregisterAction(); if (unregisterResult) registered = false; return unregisterResult }
        override fun close() = Unit
    }
    interface PlaybackInfo { fun getTitle(): String; fun getPlaybackStatus(): Int; fun getArtwork(): Uri? }
    @Suppress("UNUSED_PARAMETER")
    class DimReceiver {
        val cards = mutableListOf<String>()
        fun updateCurrentSourceType(sourceType: Int) {}
        fun updateMediaSourceTypeList(sourceTypes: IntArray) {}
        fun updateMediaMuteState(muted: Int) {}
        fun updatePlaybackInfo(info: PlaybackInfo) { cards += info.getTitle() }
        fun updateCurrentProgress(millis: Long) {}
    }
}
