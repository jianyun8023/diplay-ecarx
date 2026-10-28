package com.shilapi.xcertplay.mediacenter

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Transport only; its owner serializes calls, controls claims, and handles uncertain IPC timeouts. */
internal interface MediaCenterTransport {
    val registered: Boolean
    val connected: Boolean get() = registered
    val artworkSupported: Boolean get() = false
    fun connect()
    fun register(now: CarPlayNowPlaying, artwork: Uri?)
    fun publish(now: CarPlayNowPlaying, artwork: Uri?): Boolean
    fun requestPlay(): Boolean
    fun clear()
    fun unregister(): Boolean
    fun close()
}

/** Independent implementation of observed ECARX interfaces, not copied launcher code. */
internal class EcarxMediaCenterTransport(
    context: Context,
    private val command: (Int) -> Boolean,
    private val valid: () -> Boolean,
    private val platform: Platform = AndroidPlatform(context.applicationContext),
) : MediaCenterTransport {
    internal interface Platform {
        fun contract(): MediaCenterProbe.Contract
        fun bind(connection: ServiceConnection): Boolean
        fun unbind(connection: ServiceConnection)
        fun launchIntent(): PendingIntent? = null
    }
    private class AndroidPlatform(private val context: Context) : Platform {
        override fun contract(): MediaCenterProbe.Contract {
            val access = MediaCenterProbe.access(context)
            if (!access.granted) throw SecurityException("MEDIACENTER_PERMISSION_DENIED")
            if (!access.serviceUsable) throw SecurityException("MEDIACENTER_SERVICE_UNAVAILABLE")
            return MediaCenterProbe.Contract.resolve(MediaCenterProbe.types(MediaCenterProbe.loader(context)))
        }
        override fun bind(connection: ServiceConnection): Boolean = context.bindService(
            Intent(MediaCenterProbe.SERVICE_ACTION).setComponent(MediaCenterProbe.component), connection, Context.BIND_AUTO_CREATE)
        override fun unbind(connection: ServiceConnection) = context.unbindService(connection)
        override fun launchIntent(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @Volatile private var now = CarPlayNowPlaying()
    @Volatile private var artwork: Uri? = null
    private val closed = AtomicBoolean(false)
    private val connectionAlive = AtomicBoolean(false)
    private var bound = false
    private var connection: ServiceConnection? = null
    private lateinit var contract: MediaCenterProbe.Contract
    private var service: Any? = null
    private var client: Any? = null
    private var playback: Any? = null
    private var media: Any? = null
    private var wirePlayback: Any? = null
    @Volatile private var token: Any? = null
    override val registered: Boolean get() = token != null
    override val connected: Boolean get() = connectionAlive.get() && !closed.get()
    override val artworkSupported: Boolean get() = ::contract.isInitialized && contract.artworkSupported

    override fun connect() {
        check(!closed.get())
        contract = platform.contract() // Permission and cleanup contract precede even binding.
        check(valid()) { "SESSION_CHANGED" }
        val arrived = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, value: IBinder) {
                if (!closed.get()) { binder.set(value); connectionAlive.set(true) }
                arrived.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { connectionAlive.set(false); arrived.countDown() }
            override fun onBindingDied(name: ComponentName) { connectionAlive.set(false); arrived.countDown() }
            override fun onNullBinding(name: ComponentName) { connectionAlive.set(false); arrived.countDown() }
        }
        connection = callback
        bound = platform.bind(callback)
        check(bound) { "BIND_REJECTED" }
        check(arrived.await(3, TimeUnit.SECONDS)) { "BIND_TIMEOUT" }
        check(valid() && !closed.get()) { "SESSION_CHANGED" }
        val received = binder.get() ?: error("BIND_UNAVAILABLE")
        service = contract.asInterface.invoke(null, received) ?: error("SERVICE_UNAVAILABLE")
    }

    override fun register(now: CarPlayNowPlaying, artwork: Uri?) {
        check(valid() && !closed.get() && connectionAlive.get()) { "SESSION_CHANGED" }
        require(realMedia(now)) { "NO_MEDIA" }
        check(token == null)
        this.now = now; this.artwork = artwork
        val types = contract.types
        playback = payload(types.playback)
        media = payload(types.media)
        client = Proxy.newProxyInstance(types.client.classLoader, arrayOf(types.client)) { proxy, method, args ->
            objectValue(proxy, method, args) ?: clientValue(method, args)
        }
        // SDK wrapper constructor is package-private on the observed DCY11 image. No hidden API exemptions.
        val wireClient = contract.clientConstructor.apply { isAccessible = true }.newInstance(client)
        wirePlayback = contract.playbackConstructor.apply { isAccessible = true }.newInstance(playback)
        check(valid() && !closed.get()) { "SESSION_CHANGED" }
        token = contract.register.invoke(service, wireClient)
        check(token != null) { "REGISTER_NO_TOKEN" }
        if (valid() && !closed.get()) contract.sourceList.invoke(service, token, intArrayOf(SOURCE))
    }

    override fun publish(now: CarPlayNowPlaying, artwork: Uri?): Boolean {
        check(registered && connectionAlive.get() && !closed.get() && valid()) { "SESSION_CHANGED" }
        require(realMedia(now)) { "NO_MEDIA" }
        this.now = now; this.artwork = artwork
        val queue = contract.mediaList.apply { isAccessible = true }.invoke(null, listOf(media))
        contract.playlist.invoke(service, token, SOURCE, queue)
        val accepted = contract.playback.invoke(service, token, wirePlayback) == true
        contract.progress.invoke(service, token, (now.elapsedMillis ?: 0L).coerceAtLeast(0L))
        return accepted
    }

    override fun requestPlay(): Boolean {
        check(registered && connectionAlive.get() && !closed.get() && valid()) { "SESSION_CHANGED" }
        val accepted = contract.requestPlay.invoke(service, token) == true
        if (accepted && valid()) contract.source.invoke(service, token, SOURCE)
        return accepted
    }

    override fun clear() {
        now = CarPlayNowPlaying(); artwork = null
        if (token == null || service == null || !connectionAlive.get()) return
        contract.playlist.invoke(service, token, SOURCE, emptyList<Any>())
        wirePlayback?.let { contract.playback.invoke(service, token, it) }
        contract.progress.invoke(service, token, 0L)
    }

    override fun unregister(): Boolean {
        now = CarPlayNowPlaying(); artwork = null
        val owned = token ?: return true
        val current = service ?: return false
        val accepted = contract.unregister.invoke(current, owned) == true
        if (accepted) token = null
        return accepted
    }

    override fun close() {
        closed.set(true); connectionAlive.set(false)
        if (bound) {
            bound = false
            connection?.let(platform::unbind)
        }
        connection = null
    }

    private fun available(): Boolean = !closed.get() && connectionAlive.get() && valid() && realMedia(now)
    private fun clientValue(method: Method, args: Array<out Any?>?): Any? {
        if (!available()) return if (method.name == "getCurrentSourceType") numeric(method.returnType, -1) else defaultValue(method.returnType)
        return when (method.name) {
            "getCurrentProgress" -> numeric(method.returnType, now.elapsedMillis ?: 0L)
            "getCurrentSourceType" -> numeric(method.returnType, SOURCE.toLong())
            "getMediaSourceTypeList" -> intArrayOf(SOURCE)
            "getMusicPlaybackInfo" -> playback
            "getPlaylist" -> if ((args?.firstOrNull() as? Number)?.toInt() == SOURCE) listOf(media) else emptyList<Any>()
            "onNext" -> command(CarPlayMediaButton.NEXT)
            "onPrevious" -> command(CarPlayMediaButton.PREVIOUS)
            "onPlay" -> command(CarPlayMediaButton.PLAY)
            "onPause" -> command(CarPlayMediaButton.PAUSE)
            "onSourceSelected" -> (args?.firstOrNull() as? Number)?.toInt() == SOURCE && command(SELECT_SOURCE)
            else -> defaultValue(method.returnType)
        }
    }

    private fun payload(type: Class<*>): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        objectValue(proxy, method, args) ?: payloadValue(method)
    }
    private fun payloadValue(method: Method): Any? {
        val snapshot = if (valid() && !closed.get()) now else CarPlayNowPlaying()
        // A blank state exists only for teardown; no fabricated track is ever registered.
        return when (method.name) {
            "getTitle" -> snapshot.title.orEmpty()
            "getArtist" -> snapshot.artist.orEmpty()
            "getAlbum" -> snapshot.album.orEmpty()
            "getDuration" -> numeric(method.returnType, snapshot.durationMillis ?: 0L)
            "getPlayingItemPositionInQueue" -> numeric(method.returnType, 0L)
            "getSourceType" -> numeric(method.returnType, SOURCE.toLong())
            "getPlaybackStatus" -> numeric(method.returnType, if (snapshot.playing) 1L else 0L)
            // Observed MusicPlaybackInfo.getArtwork():Uri; no guessed cover/URL aliases.
            "getArtwork" -> if (method.returnType == Uri::class.java && realMedia(snapshot)) artwork else null
            "getLaunchIntent", "getPlayerIntent" -> if (method.returnType == PendingIntent::class.java) platform.launchIntent() else null
            else -> defaultValue(method.returnType)
        }
    }

    companion object {
        const val SOURCE = 6
        const val SELECT_SOURCE = -1
        internal fun realMedia(now: CarPlayNowPlaying): Boolean =
            !now.title.isNullOrBlank() || !now.artist.isNullOrBlank() || !now.album.isNullOrBlank() ||
                now.artworkTransferId != null || (now.durationMillis ?: 0) > 0
        private fun objectValue(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "DiPlay MediaCenter payload"
            else -> null
        }
        private fun numeric(type: Class<*>, value: Long): Any = when (type) {
            Long::class.javaObjectType, Long::class.javaPrimitiveType -> value
            else -> value.toInt()
        }
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> 0.toChar()
            IntArray::class.java -> intArrayOf()
            String::class.java -> ""
            else -> if (List::class.java.isAssignableFrom(type)) emptyList<Any>() else null
        }
    }
}
