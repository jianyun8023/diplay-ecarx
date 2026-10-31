package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcarxDayNightMappingTest {
    @Test fun adaptapiDayNightValuesMapToNight() {
        assertEquals(false, EcarxDayNightMapping.fromAdaptapiDayNight(1))
        assertEquals(true, EcarxDayNightMapping.fromAdaptapiDayNight(2))
    }

    @Test fun adaptapiInvalidValuesAreIgnored() {
        assertNull(EcarxDayNightMapping.fromAdaptapiDayNight(0))
        assertNull(EcarxDayNightMapping.fromAdaptapiDayNight(3))
        assertNull(EcarxDayNightMapping.fromAdaptapiDayNight(-1))
    }

    @Test fun amapStandardBroadcastStatesMapToNight() {
        assertEquals(false, EcarxDayNightMapping.fromAmapState(30))
        assertEquals(true, EcarxDayNightMapping.fromAmapState(32))
    }

    @Test fun amapUnknownStatesAreIgnored() {
        assertNull(EcarxDayNightMapping.fromAmapState(0))
        assertNull(EcarxDayNightMapping.fromAmapState(31))
        assertNull(EcarxDayNightMapping.fromAmapState(10019))
        assertNull(EcarxDayNightMapping.fromAmapState(-1))
    }
}
