package com.shihab.diplay.network

import org.junit.Test

class NativeBonjourProbeDeviceTest {
    @Test fun probeBudgetAndCancellationKeepTheExistingOrder() = BonjourProbeContract.attemptsAndCancellation()
    @Test fun endpointSelectionKeepsTheExistingFamilyFallbacks() = BonjourProbeContract.endpointSelection()
}
