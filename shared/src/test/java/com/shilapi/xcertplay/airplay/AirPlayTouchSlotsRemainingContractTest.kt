package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wire contracts established against the original Kotlin slot owner before native migration. */
class AirPlayTouchSlotsRemainingContractTest {
    @Test fun theLastDuplicatePointerEntryWinsAndItsReleaseIsSentOnlyOnce() {
        val slots = AirPlayTouchSlots(2)
        assertEquals(listOf(0, 1, 188, 2, 144, 1, 1, 0, 0, 0, 0, 0),
            report(slots, listOf(AirPlayContact(42, .1, .2, true), AirPlayContact(42, .7, .8, true))))
        assertEquals(listOf(0, 0, 188, 2, 144, 1, 1, 0, 0, 0, 0, 0), report(slots, emptyList()))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0), report(slots, emptyList()))
    }

    @Test fun aReleasedSlotBecomesAvailableOnTheNextEventRatherThanLaterInTheSameBatch() {
        val slots = AirPlayTouchSlots(2)
        report(slots, listOf(AirPlayContact(7, .1, .2, true), AirPlayContact(9, .3, .4, true)))
        assertEquals(listOf(0, 0, 150, 0, 125, 0, 1, 1, 94, 1, 225, 0), report(slots, listOf(
            AirPlayContact(7, .15, .25, false), AirPlayContact(5, .5, .6, true), AirPlayContact(9, .35, .45, true),
        )))
        assertEquals(listOf(0, 1, 244, 1, 44, 1, 1, 0, 94, 1, 225, 0),
            report(slots, listOf(AirPlayContact(5, .5, .6, true))))
    }

    @Test fun aMissingFingerRetainsNormalizedCoordinatesWhenTheDisplaySizeChanges() {
        val slots = AirPlayTouchSlots(2)
        report(slots, listOf(AirPlayContact(7, .6, .5, true)))
        assertEquals(listOf(0, 0, 176, 4, 244, 1, 1, 0, 0, 0, 0, 0),
            report(slots, emptyList(), width = 2000, height = 1000))
    }

    // The exact pre-migration AirPlaySession pipeline. Native integration can replace this body
    // with slots.report(...); the golden reports and event sequences remain independent.
    private fun report(slots: AirPlayTouchSlots, contacts: List<AirPlayContact>, width: Int = 1000,
        height: Int = 500): List<Int> = AirPlayHid.touchReport(slots.assign(contacts).map {
            it.copy(x = it.x * width, y = it.y * height)
        }).map { it.toInt() and 0xff }
}
