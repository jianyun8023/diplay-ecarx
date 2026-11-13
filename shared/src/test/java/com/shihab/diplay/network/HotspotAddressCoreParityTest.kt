package com.shihab.diplay.network

import org.junit.Test

class HotspotAddressCoreParityTest {
    @Test fun addressSelectionMatchesTheKotlinContract() = HotspotAddressCoreContract.verifySelection()
    @Test fun recoveryClocksWaitsAndCancellationMatchTheKotlinContract() = HotspotAddressCoreContract.verifyRecovery()
}
