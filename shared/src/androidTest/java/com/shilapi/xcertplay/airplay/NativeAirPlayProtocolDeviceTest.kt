package com.shilapi.xcertplay.airplay

import org.junit.Test

class NativeAirPlayProtocolDeviceTest {
    @Test fun cornerMaskTraversalPreservesDepthOrderAndOpaqueLeaves() = AirPlayProtocolContract.cornerMasks()
    @Test fun commandsAndBonjourPreserveWireContract() = AirPlayProtocolContract.commandsAndBonjour()
    @Test fun declarationsPreserveJvmTypesAndUtf16() = AirPlayProtocolContract.declarations()
    @Test fun videoControlsPreservePlatformContract() = AirPlayProtocolContract.videoBodies()
    @Test fun availabilityPreservesRetryAndReentry() = AirPlayProtocolContract.availability()
}
