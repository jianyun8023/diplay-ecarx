package com.shihab.diplay.network

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHotspotAddressDeviceTest {
    @Test fun nativeSelectionMatchesThePlatformAddressContract() {
        assertTrue(NativeMedia.available)
        HotspotAddressCoreContract.verifySelection()
    }
    @Test fun nativeRecoveryPreservesClocksCancellationAndWaits() {
        assertTrue(NativeMedia.available)
        HotspotAddressCoreContract.verifyRecovery()
    }
}
