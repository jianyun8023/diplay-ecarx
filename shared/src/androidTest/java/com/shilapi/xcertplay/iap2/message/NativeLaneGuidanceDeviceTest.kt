package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.airplay.NativeMedia
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLaneGuidanceDeviceTest {
    @Test fun laneParsingMatchesThePlatformContract() {
        assertTrue(NativeMedia.available)
        LaneGuidanceCoreContract.verify()
    }
}
