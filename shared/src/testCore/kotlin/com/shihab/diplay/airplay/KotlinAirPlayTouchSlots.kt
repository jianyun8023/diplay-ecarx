@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.airplay

/** Original slot reference, only compiled into tests. */
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

