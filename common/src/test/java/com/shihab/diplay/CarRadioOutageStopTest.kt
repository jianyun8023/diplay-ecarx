package com.shihab.diplay

import com.shihab.diplay.network.CarRadioOutage
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRadioOutageStopTest {
    @After fun clear() = CarRadioOutage.clear()

    @Test fun stoppingCarPlayEndsTheWaitForTheCarsRadios() {
        CarRadioOutage.mark(nowMillis = 1_000L)
        assertTrue(CarRadioOutage.active(nowMillis = 2_000L))
        var stopped = false
        CarPlayBackgroundSession.stop { stopped = true }
        assertTrue(stopped)
        // A later connect with Bluetooth off says so instead of waiting.
        assertFalse(CarRadioOutage.active(nowMillis = 2_000L))
    }
}
