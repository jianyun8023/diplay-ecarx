package com.shilapi.xcertplay.airplay

/** One normalized touch contact (0..1 coordinates). */
data class AirPlayContact(
    val id: Int,
    val x: Double,
    val y: Double,
    val down: Boolean,
)

/** Momentary knob/button state for the head-unit rotary controller. */
data class AirPlayKnobState(
    val select: Boolean = false,
    val home: Boolean = false,
    val back: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val wheel: Int = 0,
)

/** Rust owns HID wire bytes; Kotlin retains device registration and the Android input adapter. */
object AirPlayHid {
    init { NativeMedia.requireAvailable() }
    const val TOUCH_HID_UID = 0x2a2a2a2a
    const val KNOB_HID_UID = 0x2a2a2a2b
    const val MEDIA_HID_UID = 0x2a2a2a2c
    const val TELEPHONY_HID_UID = 0x2a2a2a2d

    internal const val TOUCH_CONTACTS = 2

    fun touchHidDevice(xMax: Int, yMax: Int, displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(TOUCH_HID_UID, "xcertplay Touchscreen", descriptor(0, xMax, yMax), displayUuid)

    fun knobHidDevice(displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(KNOB_HID_UID, "xcertplay Knob", descriptor(1), displayUuid)

    fun mediaHidDevice(displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(MEDIA_HID_UID, "xcertplay Media", descriptor(2), displayUuid)

    fun telephonyHidDevice(displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(TELEPHONY_HID_UID, "xcertplay Telephony", descriptor(3), displayUuid)

    /** Selection by slot id, rounding and packing happen in one native call per report. */
    fun touchReport(contacts: List<AirPlayContact>): ByteArray {
        val ids = IntArray(contacts.size)
        val xy = DoubleArray(contacts.size * 2)
        val down = BooleanArray(contacts.size)
        contacts.forEachIndexed { index, contact ->
            ids[index] = contact.id
            xy[index * 2] = contact.x
            xy[index * 2 + 1] = contact.y
            down[index] = contact.down
        }
        return NativeMedia.hidTouchReport(ids, xy, down)
    }

    fun knobReport(state: AirPlayKnobState): ByteArray =
        NativeMedia.hidKnobReport(state.select, state.home, state.back, state.x, state.y, state.wheel)

    fun mediaReport(index: Int): ByteArray = NativeMedia.hidIndexReport(index)

    fun telephonyReport(index: Int): ByteArray = NativeMedia.hidIndexReport(index)

    private fun descriptor(kind: Int, xMax: Int = 0, yMax: Int = 0): ByteArray =
        NativeMedia.hidDescriptor(kind, xMax, yMax)

    private fun hidDeviceEntry(
        uid: Int,
        name: String,
        descriptor: ByteArray,
        displayUuid: String,
    ): Map<String, Any?> = AirPlayCommands.hidDevice(uid, name, descriptor, displayUuid)

}
