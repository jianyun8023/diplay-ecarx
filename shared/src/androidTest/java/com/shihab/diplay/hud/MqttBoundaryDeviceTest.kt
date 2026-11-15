package com.shihab.diplay.hud

import org.junit.Test

class MqttBoundaryDeviceTest {
    @Test fun wire() = MqttBoundaryContract.wireLimitsAndExceptions()
    @Test fun state() = MqttBoundaryContract.publicationAndObservationState()
}
