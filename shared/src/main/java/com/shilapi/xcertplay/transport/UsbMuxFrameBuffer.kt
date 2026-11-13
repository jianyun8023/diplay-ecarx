package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(val protocol: Int, val length: Int, val word8: Int, val sequence: Int, val payload: ByteArray)

/** JNI owns buffering and the captured padding rules; callbacks never run while Rust is borrowed. */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) : java.io.Closeable {
    private var handle = NativeUsbMux.framerCreate()
    val bufferedBytes: Int @Synchronized get() = if (handle == 0L) 0 else NativeUsbMux.framerBuffered(handle)
    @Synchronized fun append(transfer: ByteArray) {
        if (handle == 0L) return // A late USB completion cannot revive a retired owner.
        NativeUsbMux.framerAppend(handle, transfer)
    }
    fun takeFrame(): UsbMuxFrame? {
        while (true) {
            val result = synchronized(this) {
                if (handle == 0L) return null
                NativeUsbMux.framerRead(handle) ?: return null
            }
            val fields = result[0] as IntArray
            when (fields[0]) {
                1 -> runCatching { diagnostic(result[2] as String) }
                2 -> return UsbMuxFrame(fields[1], fields[2], fields[3], fields[4], result[1] as ByteArray)
                3 -> {
                    runCatching { diagnostic(result[2] as String) }
                    throw IphoneUsbException.Protocol("Invalid USBMUX frame length ${fields[1]}")
                }
            }
        }
    }
    @Synchronized override fun close() {
        if (handle == 0L) return
        NativeUsbMux.framerDestroy(handle)
        handle = 0L
    }
}
