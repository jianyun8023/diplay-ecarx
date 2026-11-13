package com.shihab.diplay.media

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import com.shihab.diplay.airplay.NativeMedia
import java.nio.ByteBuffer

/**
 * Where an [AudioRenderer] plays its PCM: an [AudioTrack], or AAudio through `libdiplay_media`
 * ([NativeAudioOut]). The members are the AudioTrack ones the renderer uses, with the same meaning.
 */
internal interface PcmOutput {
    val bufferSizeInFrames: Int
    val sampleRate: Int
    val underrunCount: Int
    /** Frames played so far, wrapping like AudioTrack's. */
    val playbackHeadPosition: Int
    val state: Int
    val playState: Int
    val routedDevice: AudioDeviceInfo?
    /** Blocking writes; the count written or a negative error. */
    fun write(data: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteBuffer, offset: Int, length: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun setPlaybackRate(rate: Int): Int
    /** The focus ducking gain on both channels; native output applies accepted changes on its worker. */
    fun setVolume(volume: Float): Boolean
}

internal class TrackOutput(val track: AudioTrack) : PcmOutput {
    override val bufferSizeInFrames get() = track.bufferSizeInFrames
    override val sampleRate get() = track.sampleRate
    override val underrunCount get() = track.underrunCount
    override val playbackHeadPosition get() = track.playbackHeadPosition
    override val state get() = track.state
    override val playState get() = track.playState
    override val routedDevice: AudioDeviceInfo? get() = track.routedDevice
    override fun write(data: ByteArray, offset: Int, length: Int) = track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
    override fun write(buffer: ByteBuffer, offset: Int, length: Int): Int {
        buffer.limit(offset + length).position(offset)
        return track.write(buffer, length, AudioTrack.WRITE_BLOCKING)
    }
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun release() = track.release()
    override fun setPlaybackRate(rate: Int) = track.setPlaybackRate(rate)
    override fun setVolume(volume: Float) = track.setStereoVolume(volume, volume) == AudioTrack.SUCCESS
}

/**
 * AAudio for a stream routed by usage. Ducking is a PCM gain and the clock trim's rate is resampling,
 * both in Rust; the playback head counts the renderer's own frames. The renderer's worker owns it.
 */
internal class NativeAudioOut private constructor(
    private var handle: Long,
    private val frameBytes: Int,
    /** Maps this worker's live AAudio stream to its current SDK route for diagnostics. */
    private val routedDeviceProvider: ((Long) -> AudioDeviceInfo?)?,
) : PcmOutput {
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var playing = false
    @Volatile private var requestedGain = 1f
    private var appliedGain = 1f

    override val bufferSizeInFrames get() = NativeMedia.outQuery(handle, 1)
    override val sampleRate get() = NativeMedia.outQuery(handle, 3)
    override val underrunCount get() = NativeMedia.outQuery(handle, 0)
    override val playbackHeadPosition get() = NativeMedia.outFramesRead(handle)
    /** AudioTrack.STATE_INITIALIZED while open. */
    override val state get() = if (handle != 0L) AudioTrack.STATE_INITIALIZED else AudioTrack.STATE_UNINITIALIZED
    override val playState get() = if (playing) AudioTrack.PLAYSTATE_PLAYING else AudioTrack.PLAYSTATE_PAUSED
    override val routedDevice: AudioDeviceInfo? get() = if (handle != 0L) routedDeviceProvider?.invoke(handle) else null

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (staging.capacity() < length) staging = ByteBuffer.allocateDirect(length)
        staging.clear()
        staging.put(data, offset, length)
        return write(staging, 0, length)
    }

    override fun write(buffer: ByteBuffer, offset: Int, length: Int): Int {
        val gain = requestedGain
        if (gain != appliedGain) {
            NativeMedia.outSetGain(handle, gain)
            appliedGain = gain
        }
        return NativeMedia.outWrite(handle, buffer, offset, length - length % frameBytes)
    }

    /** Drains a transformed block even when no more packets arrive. Called only by the worker. */
    fun drain(): Int = write(staging, 0, 0)

    override fun play() { NativeMedia.outControl(handle, 0); playing = true }
    override fun pause() { NativeMedia.outControl(handle, 1); playing = false }
    override fun flush() { NativeMedia.outControl(handle, 2) }

    override fun release() {
        if (handle != 0L) NativeMedia.outClose(handle)
        handle = 0L
        playing = false
    }

    override fun setPlaybackRate(rate: Int) = if (NativeMedia.outSetRate(handle, rate) == 0) AudioTrack.SUCCESS else AudioTrack.ERROR
    // Focus callbacks never touch the worker's native handle, including after it has closed.
    override fun setVolume(volume: Float): Boolean { requestedGain = volume; return true }

    companion object {
        /** Null when AAudio cannot open this format and these attributes; the renderer then builds an AudioTrack. */
        fun open(
            sampleRate: Int, channels: Int, usage: Int, contentType: Int, capacityFrames: Int,
            deviceResolver: ((Int) -> AudioDeviceInfo?)? = null,
        ): NativeAudioOut? {
            NativeMedia.requireAvailable()
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
            val handle = NativeMedia.outOpen(sampleRate, channels, usage, contentType, capacityFrames)
            if (handle == 0L) return null
            return NativeAudioOut(handle, 2 * channels) { current ->
                val id = NativeMedia.outQuery(current, 4)
                if (id > 0) deviceResolver?.invoke(id) else null
            }
        }
    }
}
