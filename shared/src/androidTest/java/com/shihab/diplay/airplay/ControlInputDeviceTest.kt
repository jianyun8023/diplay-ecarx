package com.shihab.diplay.airplay

import org.junit.Test

class ControlInputDeviceTest {
    @Test fun encryptedSplits() = ControlInputContract.everySplitPreservesCipherAndRtspResidue()
    @Test fun activationLimitsAndClose() = ControlInputContract.activationLimitsAndCloseKeepTheirExistingBoundary()
}
