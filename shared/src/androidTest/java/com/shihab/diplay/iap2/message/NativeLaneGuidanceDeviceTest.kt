package com.shihab.diplay.iap2.message

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLaneGuidanceDeviceTest {
    @Test fun laneParsingMatchesThePlatformContract() {
        assertTrue(NativeMedia.available)
        LaneGuidanceCoreContract.verify()
    }
}
