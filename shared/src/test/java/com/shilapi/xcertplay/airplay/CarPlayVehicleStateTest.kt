package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayVehicleStateTest {
    private fun message(command: String, values: Any) = CarPlayOpack.encode(mapOf("pluginID" to 0,
        "pluginData" to CarPlayOpack.encode(mapOf("command" to command, "transactionID" to 7, "values" to values))))
    private fun payload(bytes: ByteArray) = CarPlayOpack.decode(CarPlayOpack.decode(bytes)["pluginData"] as ByteArray)

    @Test fun phoneReadsSubscribesAndWritesTheSameSimulatedVehicleWithRangeChecks() {
        val car = SimulatedVehicle()
        val protocol = CarPlayVehicleState(car) {}
        val initial = payload(protocol.handle(message("registerRequest", listOf(520, 1538, 2562, 9999))).single())
        assertEquals(22.0, (initial["values"] as Map<*, *>)[520L])
        assertEquals(-6727L, (initial["errors"] as Map<*, *>)[9999L])
        assertTrue(protocol.updates().isEmpty())
        val writes = payload(protocol.handle(message("writeRequest", mapOf(520 to 24.5, 1538 to 12, 525 to 99))).single())
        assertEquals(mapOf(520L to 0L, 1538L to -6727L, 525L to -6727L), writes["errors"])
        assertEquals(24.5, car.reading().leftTemperature, 0.0)
        assertEquals(78, car.reading().fuel)
        val update = payload(protocol.updates().single())
        assertEquals("updateNotify", update["command"])
        assertEquals(mapOf(520L to 24.5), update["values"])
        val config = payload(protocol.handle(message("configRequest", emptyList<Any>())).single())
        assertEquals(7L, config["transactionID"])
        assertEquals(11, ((config["values"] as Map<*, *>)["accessories"] as List<*>).size)
        val accessories = (config["values"] as Map<*, *>)["accessories"] as List<*>
        val audio = accessories.map { it as Map<*, *> }.single { it["iid"] == 2304L }
        val types = (audio["services"] as List<*>).flatMap { (it as Map<*, *>)["characteristics"] as List<*> }
            .map { it as Map<*, *> }.filter { it["type"] == 0x33000005L }
        // iOS otherwise clamps undeclared enum ranges to zero and labels every slider as media.
        assertEquals(setOf(0L, 1L, 2L, 3L, 6L), types.map { it["initialValue"] }.toSet())
        assertTrue(types.all { it["minimumValue"] == 0L && it["maximumValue"] == 7L })
    }

    @Test fun radioCommandsChangeOnlyTheSimulatorAndRejectInvalidFrequencies() {
        val car = SimulatedVehicle()
        val protocol = CarPlayVehicleState(car) {}
        val bad = payload(protocol.handle(message("controlRequest", mapOf(4365 to mapOf("frequency" to 120000)))).single())
        assertEquals(-6735L, (bad["errors"] as Map<*, *>)[4365L])
        protocol.handle(message("controlRequest", mapOf(4365 to mapOf("frequency" to 90500, "sourceIdentifier" to "FM"))))
        assertEquals(90500, car.reading().radioKhz)
        assertTrue(car.reading().radioPlaying)
        protocol.handle(message("controlRequest", mapOf(4361 to emptyMap<String, Any>())))
        assertFalse(car.reading().radioPlaying)
    }
}
