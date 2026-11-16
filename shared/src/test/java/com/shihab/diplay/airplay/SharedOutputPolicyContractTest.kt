package com.shihab.diplay.airplay

import org.junit.Test

class SharedOutputPolicyContractTest {
    @Test fun nativeDecisionsMatchIndependentKotlinReference() = SharedOutputPolicyContract.verify()
}
