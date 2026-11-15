package com.shihab.diplay.airplay

/** A reader owns its residue; cipher operations and close share ControlCipher's existing monitor. */
internal class RtspInput : java.io.Closeable {
    init { NativeMedia.requireAvailable() }
    private var handle = NativeRtsp.inputCreate().also { check(it != 0L) }

    @Synchronized fun read(buffer: ByteArray, count: Int, cipher: ControlCipher?): List<RtspMessage.Request> {
        check(handle != 0L) { "RTSP input is closed" }
        return (if (cipher == null) NativeRtsp.inputFeed(handle, buffer, count, 0) else cipher.readRtsp(handle, buffer, count)).messages
    }

    @get:Synchronized val encryptedBytes: Int
        get() = if (handle == 0L) 0 else NativeRtsp.inputEncryptedBytes(handle)

    @Synchronized override fun close() {
        if (handle == 0L) return
        NativeRtsp.inputDestroy(handle)
        handle = 0L
    }
}
