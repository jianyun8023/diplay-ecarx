package com.shihab.diplay.network

import org.junit.Test

class HotspotRecoveryCoreParityTest {
    @Test fun stableSampleConfirmationMatchesTheKotlinContract() = HotspotRecoveryCoreContract.verifyReadiness()
    @Test fun radioReturnAndOutageMatchTheKotlinContract() = HotspotRecoveryCoreContract.verifyRadioRecovery()
}
