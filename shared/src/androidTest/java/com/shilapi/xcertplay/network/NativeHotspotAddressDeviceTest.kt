package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.NativeMedia
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
