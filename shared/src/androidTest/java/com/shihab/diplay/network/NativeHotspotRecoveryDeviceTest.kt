package com.shihab.diplay.network

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHotspotRecoveryDeviceTest {
    @Test fun nativeReadinessMatchesTheKotlinContract() {
        assertTrue(NativeMedia.available)
        HotspotRecoveryCoreContract.verifyReadiness()
    }
    @Test fun nativeRadioRecoveryMatchesTheKotlinContract() {
        assertTrue(NativeMedia.available)
        HotspotRecoveryCoreContract.verifyRadioRecovery()
    }
}
