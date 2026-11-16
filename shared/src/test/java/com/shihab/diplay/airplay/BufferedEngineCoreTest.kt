package com.shihab.diplay.airplay

import org.junit.Test

class BufferedEngineCoreTest {
    @Test fun matchesIndependentOldTransitionTraces() = BufferedEngineContract.verifyTransitions()
    @Test fun preservesCloseReturnCleanupAndPreflightBoundaries() = BufferedEngineContract.verifyCompletionBoundaries()
    @Test fun eligibilityAndInstallingBarriersBelongToEachEngine() = BufferedEngineContract.verifyEngineIsolation()
    @Test fun rejectsMalformedNativeValues() = BufferedEngineContract.verifyInvalidValues()
}
