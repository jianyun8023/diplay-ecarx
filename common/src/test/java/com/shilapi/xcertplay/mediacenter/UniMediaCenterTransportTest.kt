package com.shilapi.xcertplay.mediacenter

import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UniMediaCenterTransportTest {
    interface Client {
        fun getCurrentSourceType(): Int
        fun getMediaSourceTypeList(): IntArray
        fun getMusicPlaybackInfo(): Playback?
        fun getPlaylist(source: Int): List<Media>
        fun onNext(): Boolean
        fun onPrevious(): Boolean
        fun onPlay(): Boolean
        fun onPause(): Boolean
        fun onSourceSelected(source: Int): Boolean
    }
    interface Playback {
        fun getTitle(): String
        fun getArtwork(): Uri?
        fun getArtist(): String
        fun getAlbum(): String
        fun getDuration(): Long
        fun getPlaybackStatus(): Int
        fun getSourceType(): Int
    }
    interface Media { fun getTitle(): String; fun getDuration(): Long }
    class ClientWrapper(val client: Client)
    class PlaybackWrapper(val playback: Playback)
    class Token
    class Helper { companion object { @JvmStatic fun getIMediaList(items: List<Media>): List<Media> = items } }
    class Stub { companion object { lateinit var current: Service; @JvmStatic fun asInterface(binder: IBinder): Service = current } }
    open class Service {
        var registrations = 0; var unregistrations = 0; var claims = 0; var source = -1
        var sourceList = intArrayOf(); var playlist = emptyList<Media>(); var progress = -1L
        var info: Playback? = null; var client: Client? = null
        var accepted = true; var cleanupAccepted = true; var registerError = false
        val token = Token()
        fun registerMusic(wrapper: ClientWrapper): Token {
            registrations++; if (registerError) throw SecurityException("denied")
            client = wrapper.client; return token
        }
        fun unregister(token: Token): Boolean { assertSame(this.token, token); unregistrations++; return cleanupAccepted }
        fun updateMediaSourceTypeList(token: Token, sources: IntArray) { sourceList = sources }
        fun updateCurrentSourceType(token: Token, source: Int) { this.source = source }
        fun updatePlaylist(token: Token, source: Int, media: List<Media>) { playlist = media }
        fun updateMusicPlaybackState(token: Token, wrapper: PlaybackWrapper): Boolean { info = wrapper.playback; return accepted }
        fun updateCurrentProgress(token: Token, position: Long) { progress = position }
        fun requestPlay(token: Token): Boolean { claims++; return accepted }
    }
    private class Platform(val service: Service = Service()) : UniMediaCenterTransport.Platform {
        var binds = 0; var unbinds = 0; var denied = false; var nullBinding = false
        var connection: ServiceConnection? = null
        override fun contract(): MediaCenterProbe.Contract {
            if (denied) throw SecurityException("permission denied")
            return MediaCenterProbe.Contract.resolve(types())
        }
        override fun bind(connection: ServiceConnection): Boolean {
            binds++; this.connection = connection; Stub.current = service
            if (nullBinding) connection.onNullBinding(MediaCenterProbe.component)
            else connection.onServiceConnected(MediaCenterProbe.component, Binder())
            return true
        }
        override fun unbind(connection: ServiceConnection) { unbinds++ }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private fun transport(p: Platform, valid: () -> Boolean = { true }, command: (Int) -> Boolean = { true }) =
        UniMediaCenterTransport(context, command, valid, p)
    private val track = CarPlayNowPlaying(title = "Actual title", artist = "Actual artist", album = "Actual album",
        durationMillis = 120_000, elapsedMillis = 42_000, playing = true)

    @Test fun permissionDenialCannotBindOrRegister() {
        val platform = Platform().apply { denied = true }; val t = transport(platform)
        try { t.connect(); fail() } catch (_: SecurityException) { }
        t.close(); assertEquals(0, platform.binds); assertEquals(0, platform.service.registrations)
    }
    @Test fun contractResolutionDoesNotConstructOrRegister() {
        val platform = Platform(); platform.contract()
        assertEquals(0, platform.binds); assertEquals(0, platform.service.registrations)
    }
    @Test fun actualTrackRegistersWithoutClaimingPlaybackAndPublishesOneZeroStatusMapping() {
        val p = Platform(); val t = transport(p); t.connect(); t.register(track, null)
        assertTrue(t.artworkSupported)
        assertTrue(t.registered); assertEquals(1, p.service.registrations); assertEquals(0, p.service.claims)
        assertArrayEquals(intArrayOf(6), p.service.sourceList)
        val cover = Uri.parse("http://127.0.0.1:12345/current.png")
        assertTrue(t.publish(track, cover)); assertEquals("Actual title", p.service.info!!.getTitle())
        assertEquals(cover, p.service.info!!.getArtwork())
        assertEquals(1, p.service.info!!.getPlaybackStatus()); assertEquals(42_000L, p.service.progress)
        assertEquals("Actual title", p.service.playlist.single().getTitle())
        t.publish(track.copy(playing = false), null); assertEquals(0, p.service.info!!.getPlaybackStatus())
        assertEquals(0, p.service.claims)
        assertTrue(t.requestPlay()); assertEquals(1, p.service.claims); assertEquals(6, p.service.source)
        t.clear(); assertNull(p.service.info!!.getArtwork()); assertEquals("", p.service.info!!.getTitle()); assertEquals(0, p.service.info!!.getPlaybackStatus())
        assertTrue(p.service.playlist.isEmpty()); assertEquals(0L, p.service.progress)
        assertTrue(t.unregister()); t.close(); assertEquals(1, p.unbinds)
    }
    @Test fun emptyMetadataCannotRegisterAPlaceholder() {
        val p = Platform(); val t = transport(p); t.connect()
        try { t.register(CarPlayNowPlaying(playing = true), null); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, p.service.registrations); t.close()
    }
    @Test fun callbacksUseCurrentOwnerAndDoNotReplayOldCommands() {
        val p = Platform(); var valid = true; val calls = mutableListOf<Int>()
        val t = transport(p, { valid }) { calls += it; true }; t.connect(); t.register(track, null)
        val client = p.service.client!!
        assertTrue(client.onNext()); assertTrue(client.onPrevious()); assertTrue(client.onPlay()); assertTrue(client.onPause())
        assertFalse(client.onSourceSelected(1)); assertTrue(client.onSourceSelected(6))
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.PLAY,
            CarPlayMediaButton.PAUSE, UniMediaCenterTransport.SELECT_SOURCE), calls)
        valid = false
        assertFalse(client.onNext()); assertEquals(-1, client.getCurrentSourceType()); assertTrue(client.getMediaSourceTypeList().isEmpty())
        assertEquals(5, calls.size); t.unregister(); t.close()
    }
    @Test fun cleanupRejectedKeepsTokenAndRegistrationUncertain() {
        val p = Platform(); val t = transport(p); t.connect(); t.register(track, null)
        p.service.cleanupAccepted = false
        assertFalse(t.unregister()); assertTrue(t.registered)
        p.service.cleanupAccepted = true
        assertTrue(t.unregister()); assertFalse(t.registered); t.close()
    }
    @Test fun disconnectedServiceCannotClaimOrPublishButCanAttemptTokenCleanup() {
        val p = Platform(); val t = transport(p); t.connect(); t.register(track, null)
        assertTrue(t.connected)
        p.connection!!.onServiceDisconnected(MediaCenterProbe.component)
        assertFalse(t.connected)
        assertTrue("lost service does not erase a cleanup token", t.registered)
        try { t.requestPlay(); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, p.service.claims); assertTrue(t.unregister()); t.close()
    }
    @Test fun nullBindingIsRejectedAndUnboundWithoutRegistration() {
        val p = Platform().apply { nullBinding = true }; val t = transport(p)
        try { t.connect(); fail() } catch (_: IllegalStateException) { }
        t.close(); t.close(); assertEquals(1, p.unbinds); assertEquals(0, p.service.registrations)
    }
    @Test fun missingUnregisterContractIsRejectedBeforeBinding() {
        val bad = types().copy(service = Any::class.java)
        try { MediaCenterProbe.Contract.resolve(bad); fail() } catch (_: Exception) { }
    }
    @Test fun rejectedPlaybackDoesNotBecomeAcceptedOrTriggerAClaim() {
        val p = Platform(); val t = transport(p); t.connect(); t.register(track, null)
        p.service.accepted = false
        assertFalse(t.publish(track, null)); assertEquals(0, p.service.claims)
        assertFalse(t.requestPlay()); assertEquals(-1, p.service.source); t.unregister(); t.close()
    }
    companion object {
        private fun types() = MediaCenterProbe.Types(Client::class.java, ClientWrapper::class.java, Playback::class.java,
            PlaybackWrapper::class.java, Media::class.java, Helper::class.java, Service::class.java, Stub::class.java)
    }
}
