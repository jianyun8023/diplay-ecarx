package com.shihab.diplay.airplay

/** One counter owner with separate buffers for each concurrently accepted TCP connection. */
internal class DataStreamProtocol(private val mode: Int) : java.io.Closeable {
    private var handle = 0L
    private var closed = false

    @Synchronized fun begin(): Long {
        if (closed) return 0
        if (handle == 0L) handle = NativeDataStream.create(mode).also { check(it != 0L) }
        return NativeDataStream.begin(handle)
    }
    @Synchronized fun append(id: Long, bytes: ByteArray) { if (!closed) NativeDataStream.append(handle, id, bytes) }
    @Synchronized fun next(id: Long, readKey: ByteArray, writeKey: ByteArray? = null): Array<Any?>? =
        if (closed) null else NativeDataStream.next(handle, id, readKey, writeKey)
    @Synchronized fun end(id: Long) { if (handle != 0L && id != 0L) NativeDataStream.end(handle, id) }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (handle != 0L) NativeDataStream.destroy(handle)
        handle = 0L
    }
}

internal object NativeDataStream {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(mode: Int): Long
    @JvmStatic external fun begin(handle: Long): Long
    @JvmStatic external fun append(handle: Long, id: Long, bytes: ByteArray)
    @JvmStatic external fun next(handle: Long, id: Long, readKey: ByteArray, writeKey: ByteArray?): Array<Any?>?
    @JvmStatic external fun end(handle: Long, id: Long)
    @JvmStatic external fun destroy(handle: Long)
}
