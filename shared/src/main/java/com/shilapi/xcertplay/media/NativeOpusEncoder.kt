package com.shilapi.xcertplay.media

/** The uplink owns this encoder; close may also run after its worker's bounded join times out. */
internal class NativeOpusEncoder(bitrate: Int) : MicrophoneOpusEncoder {
    private var handle = if (libraryAvailable) nativeCreate(bitrate) else 0L
    private val output = ByteArray(1_275)

    override val implementation = "libopus"
    override val available: Boolean
        @Synchronized get() = handle != 0L

    @Synchronized
    override fun encode(pcm: ByteArray): List<ByteArray> {
        if (handle == 0L) return emptyList()
        val length = nativeEncode(handle, pcm, output)
        return if (length > 0) listOf(output.copyOf(length)) else emptyList()
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    private companion object {
        val libraryAvailable: Boolean by lazy {
            runCatching { System.loadLibrary("opus_encoder"); true }.getOrDefault(false)
        }

        @JvmStatic external fun nativeCreate(bitrate: Int): Long
        @JvmStatic external fun nativeEncode(handle: Long, pcm: ByteArray, output: ByteArray): Int
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
