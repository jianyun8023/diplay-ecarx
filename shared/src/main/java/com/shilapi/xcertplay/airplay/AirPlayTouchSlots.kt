package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.NativeMedia
import java.io.Closeable

/**
 * Keeps each finger in one touch report slot from press to release (#444). Rust owns the slot state
 * and the HID report; Kotlin keeps the Android pointer ids and the display size.
 */
internal class AirPlayTouchSlots(private val slots: Int) : Closeable {
    init { NativeMedia.requireAvailable() }
    private var handle = NativeTouchSlots.create(slots).also { check(it != 0L) }
    private var closed = false

    /** The 12-byte HID report for contacts carrying Android pointer ids, scaled to the display. */
    @Synchronized
    fun report(contacts: List<AirPlayContact>, widthPixels: Int, heightPixels: Int): ByteArray? {
        if (closed) return null
        val ids = IntArray(contacts.size)
        val xs = DoubleArray(contacts.size)
        val ys = DoubleArray(contacts.size)
        val down = BooleanArray(contacts.size)
        contacts.forEachIndexed { index, contact ->
            ids[index] = contact.id
            xs[index] = contact.x
            ys[index] = contact.y
            down[index] = contact.down
        }
        return NativeTouchSlots.assignReport(handle, ids, xs, ys, down, widthPixels, heightPixels)
    }

    @Synchronized
    override fun close() {
        closed = true
        if (handle != 0L) NativeTouchSlots.destroy(handle)
        handle = 0L
    }
}

internal object NativeTouchSlots {
    @JvmStatic external fun create(slots: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun assignReport(
        handle: Long, ids: IntArray, xs: DoubleArray, ys: DoubleArray, down: BooleanArray,
        widthPixels: Int, heightPixels: Int,
    ): ByteArray
}
