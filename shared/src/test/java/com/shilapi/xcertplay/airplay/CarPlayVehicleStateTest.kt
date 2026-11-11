package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayVehicleStateTest {
    private fun message(command: String, values: Any) = CarPlayOpack.encode(mapOf("pluginID" to 0,
        "pluginData" to CarPlayOpack.encode(mapOf("command" to command, "transactionID" to 7, "values" to values))))
    private fun payload(bytes: ByteArray) = CarPlayOpack.decode(CarPlayOpack.decode(bytes)["pluginData"] as ByteArray)

    @Test fun unavailableSubscribedValuesReportErrorsAndRecoverWithoutSimulatedFallback() {
        val values = mutableMapOf<Long, Any>()
        var closed = false
        val source = object : CarPlayVehicleSource {
            override val name = "real car"
            override fun configuration() = emptyList<Map<String, Any>>()
            override fun values() = values.toMap()
            override fun write(id: Long, value: Any) = false
            override fun close() { closed = true }
        }
        val protocol = CarPlayVehicleState(source) {}
        val response = payload(protocol.handle(message("registerRequest", listOf(520))).single())
        assertEquals(mapOf(520L to -6727L), response["errors"])
        assertTrue(protocol.updates().isEmpty())
        values[520] = 22.5
        assertEquals(mapOf(520L to 22.5), payload(protocol.updates().single())["values"])
        values.clear()
        assertEquals(mapOf(520L to -6727L), payload(protocol.updates().single())["errors"])
        assertTrue(protocol.updates().isEmpty())
        values[520] = 22.5
        assertEquals(mapOf(520L to 22.5), payload(protocol.updates().single())["values"])
        protocol.close()
        assertTrue(closed)
    }

    @Test fun phoneReadsSubscribesAndWritesTheSameSimulatedVehicleWithRangeChecks() {
        val car = SimulatedVehicle()
        val protocol = CarPlayVehicleState(SimulatedVehicleState(car)) {}
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

    @Test fun seatVentilationWritesNotifyIndependentlyOfSeatHeatAndCabinFan() {
        val car = SimulatedVehicle().apply { change { it.copy(seatHeating = 1, passengerHeating = 2) } }
        val protocol = CarPlayVehicleState(SimulatedVehicleState(car)) {}
        val config = payload(protocol.handle(message("configRequest", emptyList<Any>())).single())
        val accessories = (config["values"] as Map<*, *>)["accessories"] as List<*>
        val seats = accessories.map { it as Map<*, *> }.single { it["iid"] == 1024L }
        val fan = (seats["services"] as List<*>).map { it as Map<*, *> }.single { it["type"] == 0x22000002L }
        assertEquals(1033L, fan["iid"])
        val fields = (fan["characteristics"] as List<*>).map { it as Map<*, *> }
        val level = fields.single { it["iid"] == 1034L }
        assertEquals(0x31000012L, level["type"])
        assertEquals(1L, level["format"])
        assertEquals(0L, level["minimumValue"])
        assertEquals(3L, level["maximumValue"])
        assertEquals("seat_front_left", fields.single { it["type"] == 0x36000065L }["initialValue"])
        val initial = payload(protocol.handle(message("registerRequest", listOf(1034, 1026, 1030, 525))).single())
        assertEquals(mapOf(1034L to 0L, 1026L to 1L, 1030L to 2L, 525L to 3L), initial["values"])
        for (target in listOf(1, 2, 3, 0)) {
            val response = payload(protocol.handle(message("writeRequest", mapOf(1034 to target))).single())
            assertEquals(mapOf(1034L to 0L), response["errors"])
            assertEquals(target, car.reading().seatVentilation)
            assertEquals(mapOf(1034L to target.toLong()), payload(protocol.updates().single())["values"])
            assertTrue(protocol.updates().isEmpty())
        }
        val rejected = payload(protocol.handle(message("writeRequest", mapOf(1034 to 4))).single())
        assertEquals(mapOf(1034L to -6727L), rejected["errors"])
        assertTrue(protocol.updates().isEmpty())
        assertEquals(1, car.reading().seatHeating)
        assertEquals(2, car.reading().passengerHeating)
        assertEquals(3, car.reading().fan)
    }

    @Test fun radioCommandsChangeOnlyTheSimulatorAndRejectInvalidFrequencies() {
        val car = SimulatedVehicle()
        val protocol = CarPlayVehicleState(SimulatedVehicleState(car)) {}
        val bad = payload(protocol.handle(message("controlRequest", mapOf(4365 to mapOf("frequency" to 120000)))).single())
        assertEquals(-6735L, (bad["errors"] as Map<*, *>)[4365L])
        protocol.handle(message("controlRequest", mapOf(4365 to mapOf("frequency" to 90500, "sourceIdentifier" to "FM"))))
        assertEquals(90500, car.reading().radioKhz)
        assertTrue(car.reading().radioPlaying)
        protocol.handle(message("controlRequest", mapOf(4361 to emptyMap<String, Any>())))
        assertFalse(car.reading().radioPlaying)
    }
}
