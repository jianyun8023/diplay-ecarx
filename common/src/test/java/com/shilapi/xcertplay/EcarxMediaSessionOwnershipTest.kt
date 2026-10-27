package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Captures the production listener installed by attach, then observes actual DIM dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxMediaSessionOwnershipTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()
    private val bridge = singleton("com.shilapi.xcertplay.hud.EcarxMediaBridge")
    private val projection = singleton("com.shilapi.xcertplay.hud.EcarxProjectionChannel")
    private val oem = MediaRecorder()
    private lateinit var savedBridge: Map<String, Any?>
    private lateinit var savedProjection: Map<String, Any?>
    private var previousFocusDisabled = false
    private var previousMediaEnabled = true

    @Before fun setup() {
        previousFocusDisabled = AirPlayPersistence.loadDisableMediaKeyAudioFocus(app)
        previousMediaEnabled = EcarxOutputSettings.mediaEnabled(app)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
        EcarxOutputSettings.setMediaEnabled(app, true)
        // Shared's internal bridge is reached reflectively across the module boundary. Only its
        // OEM handle and scheduling are supplied here; NavigationOutputs, tick and DIM stay real.
        val bridgeDefaults = linkedMapOf(
            "context" to app, "available" to true, "dim" to Any(), "media" to oem,
            "trackInterface" to PlaybackInfo::class.java, "itemInterface" to null,
            "wanted" to null, "pushed" to null, "sourceTypeSent" to false,
            "sourceListSent" to false, "secondsSinceReport" to 0, "secondsSincePush" to 0,
            "lastProgress" to -1L, "progressMeasuredAt" to 0L,
        )
        savedBridge = bridgeDefaults.keys.associateWith { field(bridge, it).get(bridge) }
        bridgeDefaults.forEach { (name, value) -> field(bridge, name).set(bridge, value) }
        savedProjection = listOf("queued", "mediaWanted").associateWith { field(projection, it).get(projection) }
        field(projection, "queued").set(projection, true)
    }

    @After fun cleanup() {
        try {
            controllers.forEach(CarPlayMediaKeys::detach)
            CarPlayBackgroundSession.clear()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            savedBridge.forEach { (name, value) -> field(bridge, name).set(bridge, value) }
            savedProjection.forEach { (name, value) -> field(projection, name).set(projection, value) }
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, previousFocusDisabled)
            EcarxOutputSettings.setMediaEnabled(app, previousMediaEnabled)
        }
    }

    @Test fun actualArtworkListenerPublishesFetchableCoverAndDetachClosesItsServer() {
        val owner = mock(CarPlayController::class.java).also(controllers::add)
        val now = song("With cover").copy(artworkTransferId = 7)
        `when`(owner.nowPlayingSnapshot()).thenReturn(now)
        var installed: ((Int, ByteArray) -> Unit)? = null
        doAnswer { call ->
            call.getArgument<((Int, ByteArray) -> Unit)?>(0)?.let { installed = it }
            null
        }.`when`(owner).artworkListener = any()
        CarPlayMediaKeys.attach(app, owner)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().also {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray()
        bitmap.recycle()
        checkNotNull(installed).invoke(7, png)
        val queue = field(CarPlayMediaKeys, "artworkQueue").get(CarPlayMediaKeys) as NowPlayingArtworkQueue<*>
        val decoded = java.util.concurrent.CountDownLatch(1)
        queue.execute(Runnable { decoded.countDown() })
        assertTrue(decoded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        tick()
        val uri = checkNotNull(oem.cards.last().artwork)
        val fetched = java.net.URL(uri.toString()).openStream().use { it.readBytes() }
        assertTrue(fetched.size > 8)
        assertEquals(0x89.toByte(), fetched[0])
        assertEquals("PNG", fetched.copyOfRange(1, 4).toString(Charsets.US_ASCII))
        CarPlayMediaKeys.detach(owner)
        tick()
        assertNull(oem.cards.last().artwork)
        assertTrue(runCatching { java.net.URL(uri.toString()).openStream().use { it.readBytes() } }.isFailure)
        installed!!.invoke(7, png)
        shadowOf(Looper.getMainLooper()).idle()
        tick()
        assertNull(oem.cards.last().artwork)
    }

    @Test fun replacedControllersCapturedCallbackCannotOverwriteNewOwnersOemTrack()
 {
        val (_, oldCallback) = attach("Old phone")
        oldCallback(song("Old phone"))
        tick()
        val (_, currentCallback) = attach("New phone")
        currentCallback(song("New phone"))
        tick()
        assertEquals(listOf(Card("Old phone", 1), Card("New phone", 1)), oem.cards)

        oldCallback(song("Late old phone"))
        tick()

        assertEquals("A captured old production listener must not dispatch another OEM track",
            listOf(Card("Old phone", 1), Card("New phone", 1)), oem.cards)
    }

    @Test fun detachedControllersCapturedCallbackCannotReviveClearedOemCard() {
        val (owner, oldCallback) = attach("Old phone")
        oldCallback(song("Old phone"))
        tick()
        CarPlayMediaKeys.detach(owner)
        tick()
        assertEquals(listOf(Card("Old phone", 1), Card("", 2)), oem.cards)

        oldCallback(song("Late after stop"))
        tick()

        assertEquals("A callback captured before detach must leave the OEM card cleared",
            listOf(Card("Old phone", 1), Card("", 2)), oem.cards)
    }

    @Test fun currentCallbackStillPublishesAndOldOwnersDetachLeavesItAlone() {
        val (oldOwner, _) = attach("Old phone")
        val (_, currentCallback) = attach("New phone")
        currentCallback(song("New phone"))
        tick()
        CarPlayMediaKeys.detach(oldOwner)
        tick()
        assertEquals(listOf(Card("New phone", 1)), oem.cards)

        currentCallback(song("Next track"))
        tick()

        assertEquals(listOf(Card("New phone", 1), Card("Next track", 1)), oem.cards)
    }

    private fun attach(title: String): Pair<CarPlayController, (CarPlayNowPlaying) -> Unit> {
        val owner = mock(CarPlayController::class.java).also(controllers::add)
        `when`(owner.nowPlayingSnapshot()).thenReturn(song(title))
        var installed: ((CarPlayNowPlaying) -> Unit)? = null
        doAnswer { call ->
            call.getArgument<((CarPlayNowPlaying) -> Unit)?>(0)?.let { installed = it }
            null
        }.`when`(owner).nowPlayingListener = any()
        CarPlayMediaKeys.attach(app, owner)
        shadowOf(Looper.getMainLooper()).idle()
        return owner to checkNotNull(installed) { "attach did not install its production listener" }
    }

    private fun tick() {
        bridge.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(bridge)
    }

    private fun song(title: String) = CarPlayNowPlaying(title = title, durationMillis = 90_000, playing = true)
    private fun singleton(name: String): Any = Class.forName(name).getField("INSTANCE").get(null)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    interface PlaybackInfo {
        fun getTitle(): String
        fun getPlaybackStatus(): Int
        fun getArtwork(): android.net.Uri?
    }

    data class Card(val title: String, val playbackStatus: Int, val artwork: android.net.Uri? = null)

    /** The common module does not inherit shared's test fixtures. This is only an OEM receiver. */
    @Suppress("UNUSED_PARAMETER")
    class MediaRecorder {
        val cards = mutableListOf<Card>()
        fun updateCurrentSourceType(sourceType: Int) {}
        fun updateMediaSourceTypeList(sourceTypes: IntArray) {}
        fun updateMediaMuteState(muted: Int) {}
        fun updatePlaybackInfo(info: PlaybackInfo) {
            cards += Card(info.getTitle(), info.getPlaybackStatus(), info.getArtwork())
        }
        fun updateCurrentProgress(millis: Long) {}
    }
}
