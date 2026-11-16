package com.shihab.diplay.airplay

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClusterContentCoreParityTest {
    @Test fun transitionsMatchIndependentKotlin() = ClusterContentCoreContract.transitionsMatchIndependentKotlin()
    @Test fun retainedUtf16AndLateResults() = ClusterContentCoreContract.retainedUtf16AndLateResultsKeepTheOldContract()
}
