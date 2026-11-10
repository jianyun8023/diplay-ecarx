package com.shilapi.xcertplay.media

/**
 * The uplink owns this encoder; close may also run after its worker's bounded join times out.
 * [onError] receives why the native encoder is unavailable, so a head unit that falls back to the
 * Java encoder leaves the reason in its logs.
 */
internal class NativeOpusEncoder(
    bitrate: Int,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : MicrophoneOpusEncoder {
    private var handle = if (libraryLoad.isSuccess) nativeCreate(bitrate) else 0L
    private val output = ByteArray(1_275)

    init {
        if (handle == 0L) {
            onError("native Opus encoder unavailable",
                libraryLoad.exceptionOrNull() ?: IllegalStateException("opus_encoder_create failed bitrate=$bitrate"))
        }
    }

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
        val libraryLoad: Result<Unit> by lazy { runCatching { System.loadLibrary("opus_encoder") } }

        @JvmStatic external fun nativeCreate(bitrate: Int): Long
        @JvmStatic external fun nativeEncode(handle: Long, pcm: ByteArray, output: ByteArray): Int
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
