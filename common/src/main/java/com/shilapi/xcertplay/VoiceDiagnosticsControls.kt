package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import android.widget.*
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Local PCM never leaves this dialog; only device metadata and levels enter diagnostics. */
internal class VoiceDiagnosticsControls(
    private val activity: Activity,
    private val labelFactory: (String) -> TextView = { TextView(activity).apply { text = it; textSize = 15f } },
    private val buttonFactory: (String, () -> Unit) -> Button = { title, click -> Button(activity).apply {
        text = title; setOnClickListener { click() }
    } },
) {
    private var sourceDialog: Dialog? = null
    private var root: LinearLayout? = null
    private var source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var pcm: ShortArray? = null
    private var status = ""
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private var running = false

    fun build(): LinearLayout = LinearLayout(activity).also { root = it; it.orientation = LinearLayout.VERTICAL; display() }

    private fun display() {
        val view = root ?: return
        view.removeAllViews()
        fun text(value: String) = view.addView(labelFactory(value), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(BLOCK_GAP_DP) })
        fun button(value: String, enabled: Boolean = true, action: () -> Unit) = view.addView(
            buttonFactory(value, action).apply { isEnabled = enabled },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTROL_GAP_DP) })
        text(activity.getString(R.string.settings_voice_description))
        button(activity.getString(R.string.settings_voice_source) + " · " + sourceName(source), !running) {
            val sources = intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            sourceDialog = activity.appDialogBuilder().setTitle(R.string.settings_voice_source)
                .setSingleChoiceItems(sources.map { sourceName(it) }.toTypedArray(), sources.indexOf(source)) { dialog, which ->
                    source = sources[which]; dialog.dismiss(); display()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        button(activity.getString(R.string.settings_voice_record), !running) {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                status = activity.getString(R.string.settings_voice_permission)
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 781)
                display()
            } else start(record = true)
        }
        button(activity.getString(R.string.settings_voice_media), !running) { start(record = false) }
        button(activity.getString(R.string.settings_voice_assistant), !running) { start(record = false, assistant = true) }
        button(activity.getString(R.string.settings_voice_clear)) { cancel(); status = activity.getString(R.string.settings_voice_cleared); display() }
        if (status.isNotBlank()) text(status)
    }

    private fun start(record: Boolean, assistant: Boolean = false) {
        if (closed || running) return
        if (CarPlayBackgroundSession.active) { status = activity.getString(R.string.settings_voice_disconnect); display(); return }
        if (!busy.compareAndSet(false, true)) { status = activity.getString(R.string.settings_voice_busy); display(); return }
        running = true; cancelled = false
        status = activity.getString(if (record) R.string.settings_voice_recording else R.string.settings_voice_playing)
        val selected = source
        val mediaChannel = AirPlayPersistence.loadMediaAudioChannel(activity)
        display()
        worker.execute {
            val result = runCatching {
                if (record) capture(selected) else playback(assistant, mediaChannel)
            }.fold({ it }, { "FAILED operation=${if (record) "capture" else "playback"} error=${it.javaClass.simpleName}" })
            SettingsActionDiagnostics.record(activity.applicationContext, SettingsActionDiagnostics.Action.VOICE_SELF_TEST, result)
            busy.set(false)
            activity.runOnUiThread { running = false; if (!closed) { if (!cancelled) status = result; display() } }
        }
    }

    private fun capture(selected: Int): String {
        synchronized(this) { pcm?.fill(0); pcm = null }
        val manager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val visible = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { "id=${it.id}/type=${it.type}" }
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "input buffer=$minimum" }
        val samples = ShortArray(RATE * 5)
        @Suppress("DEPRECATION")
        val recorder = AudioRecord(selected, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE / 5 * 2))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "input unavailable" }
            if (cancelled) return "capture cancelled"
            recorder.startRecording()
            val deadline = SystemClock.elapsedRealtime() + 5000
            var count = 0
            while (!cancelled && count < samples.size && SystemClock.elapsedRealtime() < deadline) {
                val read = recorder.read(samples, count, minOf(4096, samples.size - count), AudioRecord.READ_NON_BLOCKING)
                check(read >= 0) { "input read=$read" }
                count += read
                if (read == 0) Thread.sleep(10)
            }
            val routed = recorder.routedDevice?.let { "id=${it.id}/type=${it.type}" } ?: "none"
            val microphones = if (Build.VERSION.SDK_INT >= 28) runCatching {
                recorder.activeMicrophones.joinToString { "id=${it.id}/type=${it.type}/channelMapping=${it.channelMapping}" }
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" } else "API<28"
            SettingsActionDiagnostics.record(activity.applicationContext, SettingsActionDiagnostics.Action.VOICE_SELF_TEST,
                "capture inputs=[$visible]")
            SettingsActionDiagnostics.record(activity.applicationContext, SettingsActionDiagnostics.Action.VOICE_SELF_TEST,
                "capture activeMicrophones=[$microphones]")
            val report = "capture ${levels(samples, count)} requested=${sourceName(selected)} actualSource=${recorder.audioSource} rate=${recorder.sampleRate} channels=${recorder.channelCount} mode=${manager.mode} mute=${manager.isMicrophoneMute} routed=[$routed] cancelled=$cancelled"
            synchronized(this) { if (!cancelled && !closed && count > 0) { pcm = samples.copyOf(count) } }
            return report
        } finally {
            samples.fill(0)
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun playback(assistant: Boolean, mediaChannel: Int): String {
        val recorded = synchronized(this) { pcm?.copyOf() }
        val data = recorded ?: ShortArray(RATE) { (sin(2 * Math.PI * 660 * it / RATE) * 2000).toInt().toShort() }
        var track: AudioTrack? = null
        try {
            if (cancelled) return "playback cancelled"
            val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "output buffer=$minimum" }
            val usage = if (assistant && Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA
            @Suppress("DEPRECATION")
            val output = if (!assistant && mediaChannel != 0) AudioTrack(mediaChannel, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minimum, AudioTrack.MODE_STREAM)
            else AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                .setContentType(if (assistant) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(minimum).setTransferMode(AudioTrack.MODE_STREAM).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "output unavailable" }
            if (cancelled) return "playback cancelled"
            output.play()
            var written = 0
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!cancelled && written < data.size && SystemClock.elapsedRealtime() < deadline) {
                val count = output.write(data, written, minOf(4096, data.size - written), AudioTrack.WRITE_NON_BLOCKING)
                check(count >= 0) { "output write=$count" }
                written += count
                if (count == 0) Thread.sleep(10)
            }
            while (!cancelled && output.playbackHeadPosition < written && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            return "playback path=${if (assistant) "assistant usage=$usage" else "media stream=$mediaChannel usage=$usage"} audio=${if (recorded == null) "tone" else "recording"} samples=$written/${data.size} head=${output.playbackHeadPosition} routed=${output.routedDevice?.let { "id=${it.id}/type=${it.type}" }} cancelled=$cancelled"
        } finally { data.fill(0); track?.let { runCatching { it.stop() }; it.release() } }
    }

    private fun cancel() { cancelled = true; synchronized(this) { pcm?.fill(0); pcm = null } }
    fun close() { closed = true; cancel(); sourceDialog?.dismiss(); sourceDialog = null; root = null }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    companion object {
        private const val RATE = 48_000
        private const val BLOCK_GAP_DP = 12
        private const val CONTROL_GAP_DP = 8
        private val busy = AtomicBoolean(false)
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-voice-test").apply { isDaemon = true } }
        internal fun sourceName(source: Int) = when (source) { MediaRecorder.AudioSource.MIC -> "MIC"; MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"; else -> "VOICE_COMMUNICATION" }
        internal fun levels(samples: ShortArray, count: Int): String {
            var peak = 0; var squares = 0.0; var zero = 0
            for (index in 0 until count) { val sample = samples[index].toInt(); peak = maxOf(peak, abs(sample)); squares += sample.toDouble() * sample; if (sample == 0) zero++ }
            val rms = if (count == 0) 0.0 else sqrt(squares / count)
            return "samples=$count peak=$peak rms=$rms zero=$zero peakDbfs=${20 * log10(peak / 32768.0)} rmsDbfs=${20 * log10(rms / 32768.0)}"
        }
    }
}
