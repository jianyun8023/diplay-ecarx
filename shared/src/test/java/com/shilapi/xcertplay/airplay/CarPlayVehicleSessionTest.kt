package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayVehicleSessionTest {
    @Test fun advertisesOnlyTheOpenedSourceAndReleasesItOnceWithTheSession() {
        for (mode in 0..2) {
            var opened = 0
            var closed = 0
            val config = AirPlayConfig("test", "test", "02:00:00:00:00:01", "950.7.1", AirPlayDisplayConfig(1280, 720),
                vehicleStateSource = if (mode == 0) null else { _ ->
                    opened++
                    if (mode == 1) null else object : CarPlayVehicleSource {
                        override val name = "ECARX test"
                        override fun configuration() = emptyList<Map<String, Any>>()
                        override fun values() = emptyMap<Long, Any>()
                        override fun write(id: Long, value: Any) = false
                        override fun close() { closed++ }
                    }
                })
            val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
            try {
                // Every accepted socket builds a session; only /info or SETUP may bind the car.
                assertEquals(0, opened)
                val method = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
                val response = method.invoke(session, RtspMessage.Request("GET", "/info", "RTSP/1.0", emptyMap(), ByteArray(0))) as RtspMessage.Response
                val info = BplistCodec.decode(response.body) as Map<*, *>
                assertEquals(mode == 2, info.containsKey("vehicleStateProtocolInfo"))
                val setup = method.invoke(session, RtspMessage.Request("SETUP", "/", "RTSP/1.0", emptyMap(),
                    BplistCodec.encode(mapOf("features" to listOf(CarPlayVehicleState.FEATURE))))) as RtspMessage.Response
                val features = (BplistCodec.decode(setup.body) as Map<*, *>)["enabledFeatures"] as List<*>
                assertEquals(mode == 2, CarPlayVehicleState.FEATURE in features)
                assertEquals(if (mode == 0) 0 else 1, opened)
            } finally { session.close(); session.close() }
            assertEquals(if (mode == 2) 1 else 0, closed)
        }
    }

    @Test fun aClosedSessionNeverOpensTheVehicleSource() {
        var opened = 0
        val config = AirPlayConfig("test", "test", "02:00:00:00:00:01", "950.7.1", AirPlayDisplayConfig(1280, 720),
            vehicleStateSource = { _ -> opened++; null })
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        session.close()
        assertNull(session.vehicleState)
        assertEquals(0, opened)
    }
}
