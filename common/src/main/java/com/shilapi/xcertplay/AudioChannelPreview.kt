package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioRouteAttributes
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Plays the same bundled speech sample through every tested CarPlay audio route. */
internal class AudioChannelPreview(
    context: Context,
    private val report: (String) -> Unit = {},
    private val onUnavailable: (Int) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-channel-preview").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private var pending: Future<*>? = null
    @Volatile private var closed = false

    fun play(channel: Int, navigation: Boolean, requestFocus: Boolean = false, oemContent: Boolean = false) {
        if (closed) return
        require(channel in AirPlayPersistence.AUDIO_CHANNELS)
        val request = generation.incrementAndGet()
        val usage = if (navigation) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA
        val contentType = if (navigation) {
            if (channel == 0 && oemContent) 20 else AudioAttributes.CONTENT_TYPE_SPEECH
        } else AudioAttributes.CONTENT_TYPE_MUSIC
        val focusGain = if (navigation) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN
        report("phase=request request=$request channel=$channel navigation=$navigation focusRequested=$requestFocus " +
            "configurationSource=${if (channel == 0) "attributes" else "legacyStream"} " +
            "legacyStream=${if (channel == 0) -1 else channel} " +
            "configuredUsage=${if (channel == 0) usage else -1} configuredContentType=${if (channel == 0) contentType else -1}")
        pending?.cancel(true)
        activeTrack.get()?.let { runCatching { it.stop() } }
        pending = worker.submit {
            var track: AudioTrack? = null
            var focus: AudioFocusRequestCompat? = null
            var focusGranted = false
            val focusListener = AudioManager.OnAudioFocusChangeListener { }
            try {
                if (closed || generation.get() != request) return@submit
                val attributes = if (navigation) {
                    AudioRouteAttributes.navigation(channel, oemContent)
                } else AudioRouteAttributes.resolve(channel) {
                    AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
                }
                report("phase=attributes request=$request resolvedUsage=${attributes.usage} " +
                    "resolvedContentType=${attributes.contentType} attributesSource=builder")
                if (requestFocus) {
                    val manager = checkNotNull(audioManager)
                    val next = AudioFocusRequestCompat(focusGain, attributes, focusListener, mainHandler)
                    focus = next
                    val granted = next.request(manager)
                    focusGranted = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    report("phase=focus request=$request granted=$focusGranted result=$granted focusGain=$focusGain " +
                        "focusConfigurationSource=${if (Build.VERSION.SDK_INT >= 26) "attributes" else "legacyStream"} " +
                        "focusUsage=${if (Build.VERSION.SDK_INT >= 26) attributes.usage else -1} " +
                        "focusContentType=${if (Build.VERSION.SDK_INT >= 26) attributes.contentType else -1}")
                    check(focusGranted) { "Preview audio focus was not granted" }
                }
                val pcm = appContext.resources.openRawResource(R.raw.audio_channel_preview).use { it.readBytes() }
                report("phase=sample request=$request sample=spoken_zh_v1 sampleRate=$SAMPLE_RATE channels=1 " +
                    "durationMs=${pcm.size * 1000L / (SAMPLE_RATE * 2)} sampleBytes=${pcm.size} volume=0.6")
                val minimum = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimum > 0) { "No PCM output buffer is available" }
                val bufferBytes = maxOf(minimum, SAMPLE_RATE / 10 * 2)
                val built = if (channel == 0) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            attributes,
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                } else {
                    // Match playback and let the head unit handle vendor-specific stream types.
                    @Suppress("DEPRECATION")
                    AudioTrack(channel, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
                }
                track = built
                check(built.state == AudioTrack.STATE_INITIALIZED) { "Audio output did not initialize" }
                if (closed || generation.get() != request) return@submit
                activeTrack.set(built)
                built.setVolume(0.6f)
                built.play()
                val actualAttributes = if (Build.VERSION.SDK_INT >= 29) built.audioAttributes else null
                report("phase=started request=$request channel=$channel navigation=$navigation " +
                    "trackState=${built.state} usage=${actualAttributes?.usage ?: -1} " +
                    "contentType=${actualAttributes?.contentType ?: -1} attributesSource=${if (actualAttributes == null) "unavailable" else "track"}")
                var written = 0
                while (written < pcm.size && !closed && generation.get() == request) {
                    val count = built.write(
                        pcm, written, minOf(4096, pcm.size - written), AudioTrack.WRITE_BLOCKING,
                    )
                    check(count > 0) { "Could not write preview speech" }
                    written += count
                }
                if (!closed && generation.get() == request && written == pcm.size) {
                    Log.i(TAG, "Preview started channel=$channel navigation=$navigation")
                }
                // A blocking write can leave the last buffer queued; let the spoken ending drain.
                Thread.sleep(bufferBytes * 1000L / (SAMPLE_RATE * 2) + 120L)
                val device = built.routedDevice
                report("phase=result request=$request channel=$channel navigation=$navigation " +
                    "cancelled=${closed || generation.get() != request} writtenBytes=$written " +
                    "playbackHeadFrames=${built.playbackHeadPosition.toLong() and 0xffff_ffffL} " +
                    "routeId=${device?.id ?: -1} routeType=${device?.type ?: -1} " +
                    "routeAddress=${device?.address ?: "none"}")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                Log.w(TAG, "Channel preview unavailable channel=$channel", error)
                report("phase=failed request=$request channel=$channel navigation=$navigation error=${error.javaClass.simpleName} " +
                    "detail=${DiagnosticRedactor.redact(error.message ?: error.javaClass.simpleName)}")
                mainHandler.post {
                    if (!closed && generation.get() == request) onUnavailable(channel)
                }
            } finally {
                activeTrack.compareAndSet(track, null)
                track?.let { runCatching { it.stop() }; it.release() }
                if (focusGranted) {
                    val result = focus!!.abandon(audioManager!!)
                    report("phase=focus-released request=$request result=$result")
                }
            }
        }
    }

    override fun close() {
        closed = true
        generation.incrementAndGet()
        pending?.cancel(true)
        activeTrack.get()?.let { runCatching { it.stop() } }
        worker.shutdownNow()
    }

    private companion object {
        private const val TAG = "DiPlayAudioPreview"
        private const val SAMPLE_RATE = 48_000
    }
}
