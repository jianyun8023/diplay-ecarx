package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.NativeMedia
import java.io.Closeable

/**
 * Keeps each finger in one touch report slot from press to release (#444). Rust owns the slot state
 * and the HID report; Kotlin keeps the Android pointer ids and the display size. The Kotlin copy
 * below runs only when the native library cannot load.
 */
internal class AirPlayTouchSlots(private val slots: Int) : Closeable {
    private val fallback = if (NativeMedia.available) null else KotlinAirPlayTouchSlots(slots)
    private var handle = if (fallback == null) NativeTouchSlots.create(slots).also { check(it != 0L) } else 0L
    private var closed = false

    /** The contacts to report, each with its slot as id; empty slots are left out. Fallback only. */
    @Synchronized
    fun assign(contacts: List<AirPlayContact>): List<AirPlayContact> {
        check(!closed) { "Touch slots are closed" }
        return checkNotNull(fallback) { "assign() is only available on the Kotlin fallback" }.assign(contacts)
    }

    /** The 12-byte HID report for contacts carrying Android pointer ids, scaled to the display. */
    @Synchronized
    fun report(contacts: List<AirPlayContact>, widthPixels: Int, heightPixels: Int): ByteArray? {
        if (closed) return null
        val kotlin = fallback
        if (kotlin != null) {
            val scaled = kotlin.assign(contacts).map {
                it.copy(x = it.x * widthPixels, y = it.y * heightPixels)
            }
            return AirPlayHid.touchReport(scaled)
        }
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

/** Library-unavailable fallback; the native path never creates this state. */
internal class KotlinAirPlayTouchSlots(private val slots: Int) {
    private val pointers = arrayOfNulls<Int>(slots)
    private val held = arrayOfNulls<AirPlayContact>(slots)

    fun assign(contacts: List<AirPlayContact>): List<AirPlayContact> {
        val report = arrayOfNulls<AirPlayContact>(slots)
        for (contact in contacts) {
            val slot = pointers.indexOf(contact.id).takeIf { it >= 0 }
                ?: pointers.indexOf(null).takeIf { it >= 0 && contact.down }
                ?: continue
            pointers[slot] = contact.id
            report[slot] = contact.copy(id = slot)
        }
        for (slot in 0 until slots) {
            if (report[slot] == null) report[slot] = held[slot]?.copy(down = false)
            val sent = report[slot]
            if (sent?.down == true) {
                held[slot] = sent
            } else {
                held[slot] = null
                pointers[slot] = null
            }
        }
        return report.filterNotNull()
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
