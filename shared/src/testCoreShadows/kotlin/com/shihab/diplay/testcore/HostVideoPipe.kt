@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")
package com.shihab.diplay.testcore

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.NativePacket
import com.shihab.diplay.airplay.NativeVideoCallbacks
import com.shihab.diplay.airplay.VideoCodec
import com.shihab.diplay.media.NativeVideoPipe
import com.shihab.diplay.media.VideoDecodeQueue
import com.shihab.diplay.media.VideoJob
import com.shihab.diplay.media.VideoReferenceChain
import java.nio.ByteBuffer

/** SDK adapter for host control/lifecycle tests. This does not validate NDK callbacks or frame timing. */
object HostVideoPipe {
    var failCreate = false
    private var nextHandle = 1L
    private val pipes = mutableMapOf<Long, Pipe>()
    val activeCount: Int get() = synchronized(pipes) { pipes.size }
    fun codec(handle: Long): MediaCodec? = synchronized(pipes) { pipes[handle]?.codec }
    internal fun get(handle: Long): Pipe = synchronized(pipes) { checkNotNull(pipes[handle]) }
    internal fun create(callbacks: NativeVideoCallbacks): Long = synchronized(pipes) {
        if (failCreate) 0L else nextHandle++.also { pipes[it] = Pipe(callbacks) }
    }
    internal fun destroy(handle: Long) {
        val pipe = get(handle)
        pipe.destroy()
        synchronized(pipes) { pipes.remove(handle) }
    }

    internal class Pipe(private val callbacks: NativeVideoCallbacks) {
        private val lock = Object()
        private val queue = VideoDecodeQueue()
        private val reference = VideoReferenceChain()
        @Volatile var codec: MediaCodec? = null
            private set
        private var hevc = false
        private var holds = 0
        private var accepting = false
        private var shown = true
        private var retired = true
        private var busy = false
        private var closed = false
        private var requestedDecoder = false
        private var firstInput = false
        private var firstFrame = false
        private val counters = LongArray(4)
        private val stats = LongArray(14)
        private val decodeTimes = ArrayList<Int>()
        private val worker = Thread(::run, "host-video-pipe").apply { isDaemon = true; start() }

        fun configure(name: String?, mime: String, width: Int, height: Int, keys: Array<String>, values: IntArray,
            csd0: ByteArray?, csd1: ByteArray?, surface: Surface, hevc: Boolean,
            reportVendorFormat: Boolean, detail: Array<String?>): Int {
            var phase = -1
            var candidate: MediaCodec? = null
            return try {
                candidate = if (name == null) MediaCodec.createDecoderByType(mime) else MediaCodec.createByCodecName(name)
                val format = MediaFormat.createVideoFormat(mime, width, height)
                keys.indices.forEach { format.setInteger(keys[it], values[it]) }
                csd0?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                csd1?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                phase = -2
                candidate.configure(format, surface, null, 0)
                phase = -3
                candidate.start()
                synchronized(lock) {
                    codec = candidate
                    this.hevc = hevc
                    retired = false
                    requestedDecoder = false
                    firstInput = false
                    firstFrame = false
                    reference.reset()
                    lock.notifyAll()
                }
                detail[0] = candidate.name
                if (reportVendorFormat) event(NativeVideoPipe.EVENT_REPORT,
                    "QTI driver-reported output format vendor.qti-ext-dec-low-latency.enable=unreported")
                0
            } catch (failure: Exception) {
                candidate?.release()
                detail[0] = failure.message
                phase
            }
        }

        fun hold(hold: Boolean) = synchronized(lock) {
            if (hold) {
                holds++
                queue.discardFrames()
                reference.reset()
            } else if (holds > 0) holds--
            lock.notifyAll()
        }

        fun accepting(value: Boolean, reset: Boolean) = synchronized(lock) {
            accepting = value
            if (reset || !value) {
                queue.discardFrames()
                reference.reset()
            }
            lock.notifyAll()
        }

        fun submit(handle: Long, size: Int, flags: Int, sender: Long, arrival: Long) = synchronized(lock) {
            val packet = NativePacket(handle, size, flags)
            counters[0]++
            stats[6]++
            stats[7] += size
            if (closed || !accepting || flags and MediaPacket.FLAG_ANNEX_B == 0) packet.release()
            else if (holds == 0 && queue.backlogAfterCurrent().pendingFrames == 0 &&
                !reference.accepts(packet, if (hevc) VideoCodec.H265 else VideoCodec.H264)) {
                packet.release()
                event(NativeVideoPipe.EVENT_REQUEST_KEYFRAME)
            } else queue.offer(VideoJob.Frame(packet, senderNanos = sender, arrivalNanos = arrival))
            lock.notifyAll()
        }

        fun release(failedConfigHolds: Int) = synchronized(lock) {
            val previous = codec
            codec = null
            waitIdle()
            if (failedConfigHolds >= 0 && holds <= failedConfigHolds) queue.discardFrames()
            previous?.stop()
            previous?.release()
            retired = false
            reference.reset()
            requestedDecoder = false
            lock.notifyAll()
        }

        fun setSurface(surface: Surface, shown: Boolean): Int = synchronized(lock) {
            waitIdle()
            try {
                codec?.setOutputSurface(surface)
                this.shown = shown
                0
            } catch (_: Exception) { -1 }
        }

        fun live(out: LongArray) = synchronized(lock) { counters.copyInto(out) }
        fun takeStats(out: LongArray, decode: IntArray): Int = synchronized(lock) {
            stats.copyInto(out)
            val count = minOf(decode.size, decodeTimes.size)
            repeat(count) { decode[it] = decodeTimes[it] }
            stats.fill(0)
            decodeTimes.clear()
            count
        }

        fun destroy() {
            synchronized(lock) {
                closed = true
                accepting = false
                lock.notifyAll()
                waitIdle()
                codec?.stop()
                codec?.release()
                codec = null
                queue.drain().forEach { (it as? VideoJob.Frame)?.packet?.release() }
            }
            var interrupted = Thread.interrupted()
            while (worker.isAlive) {
                try { worker.join() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        private fun waitIdle() {
            var interrupted = false
            while (busy) {
                try { lock.wait() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        private fun event(kind: Int, detail: String? = null) = callbacks.onEvent(kind, detail)
        private fun hasPending(): Boolean = queue.backlogAfterCurrent().pendingFrames > 0 || queue.resyncPending()

        private fun run() {
            while (true) {
                val work = synchronized(lock) {
                    while (!closed && (holds > 0 || codec == null || !hasPending())) {
                        if (holds == 0 && codec == null && !retired && accepting &&
                            hasPending() && !requestedDecoder) {
                            requestedDecoder = true
                            event(NativeVideoPipe.EVENT_NEED_DECODER)
                        }
                        lock.wait()
                    }
                    if (closed) return
                    when (val job = queue.poll(0)) {
                        is VideoJob.Frame -> {
                            if (!reference.accepts(job.packet, if (hevc) VideoCodec.H265 else VideoCodec.H264)) {
                                job.packet.release()
                                event(NativeVideoPipe.EVENT_REQUEST_KEYFRAME)
                                null
                            } else {
                                busy = true
                                Pair(checkNotNull(codec), job)
                            }
                        }
                        VideoJob.Resync -> {
                            reference.reset()
                            event(NativeVideoPipe.EVENT_REQUEST_KEYFRAME)
                            null
                        }
                        else -> null
                    }
                } ?: continue
                val (decoder, frame) = work
                try {
                    val start = System.nanoTime()
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        frame.packet.copyInto(input, 0, frame.packet.size)
                        decoder.queueInputBuffer(index, 0, frame.packet.size, start / 1000, 0)
                        synchronized(lock) { reference.onQueued() }
                        if (!firstInput) {
                            firstInput = true
                            event(NativeVideoPipe.EVENT_LOG, "video decoder first input annexB=${frame.packet.size} packet=NativePacket decoder=host-sdk")
                        }
                        val info = MediaCodec.BufferInfo()
                        var output = decoder.dequeueOutputBuffer(info, 0)
                        while (output >= 0 || output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || output == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            if (output >= 0) {
                                val render = synchronized(lock) { shown }
                                decoder.releaseOutputBuffer(output, render)
                                if (render) {
                                    val elapsed = System.nanoTime() - start
                                    synchronized(lock) {
                                        counters[1]++
                                        counters[2] += elapsed
                                        counters[3]++
                                        stats[0]++
                                        decodeTimes += (elapsed / 1000).toInt()
                                    }
                                    if (!firstFrame) {
                                        firstFrame = true
                                        event(NativeVideoPipe.EVENT_FIRST_FRAME, "decoder=host-sdk")
                                    }
                                }
                            }
                            output = decoder.dequeueOutputBuffer(info, 0)
                        }
                    }
                } catch (failure: Exception) {
                    event(NativeVideoPipe.EVENT_CODEC_ERROR, failure.message)
                } finally {
                    frame.packet.release()
                    synchronized(lock) { busy = false; lock.notifyAll() }
                }
            }
        }
    }
}
