package com.shihab.diplay.airplay

import androidx.test.runner.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Device JNI parity; real TCP/output-cleanup coverage remains in BufferedAudioSessionTest. */
@RunWith(AndroidJUnit4::class)
class BufferedEngineCoreDeviceTest {
    @Test fun nativeOwnershipMatchesIndependentReference() = BufferedEngineContract.verifyTransitions()
    @Test fun nativeCleanupBoundariesMatchReference() = BufferedEngineContract.verifyCompletionBoundaries()
    @Test fun nativeEngineEligibilityIsIsolated() = BufferedEngineContract.verifyEngineIsolation()
    @Test fun nativeRejectsMalformedOwnershipValues() = BufferedEngineContract.verifyInvalidValues()
}
