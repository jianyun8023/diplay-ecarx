package com.shihab.diplay.airplay

import org.junit.Test

class NativeAirPlayProtocolTest {
    @Test fun cornerMaskTraversalPreservesDepthOrderAndOpaqueLeaves() = AirPlayProtocolContract.cornerMasks()
    @Test fun commandsAndBonjourMatchIndependentContracts() = AirPlayProtocolContract.commandsAndBonjour()
    @Test fun declarationsMatchIndependentKotlin() = AirPlayProtocolContract.declarations()
    @Test fun videoBodiesMatchIndependentKotlin() = AirPlayProtocolContract.videoBodies()
    @Test fun availabilityMatchesIndependentKotlin() = AirPlayProtocolContract.availability()
}
