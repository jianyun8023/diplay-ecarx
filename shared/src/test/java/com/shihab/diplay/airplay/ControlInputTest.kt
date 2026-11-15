package com.shihab.diplay.airplay

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControlInputTest {
    @Test fun encryptedSplits() = ControlInputContract.everySplitPreservesCipherAndRtspResidue()
    @Test fun activationLimitsAndClose() = ControlInputContract.activationLimitsAndCloseKeepTheirExistingBoundary()
}
