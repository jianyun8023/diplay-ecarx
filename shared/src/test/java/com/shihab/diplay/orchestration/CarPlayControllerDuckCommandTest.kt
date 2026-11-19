package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.*
import com.shihab.diplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * duckAudio/unduckAudio reach media once, through the session's control-command plan; the
 * controller's generic command listener only forwards the raw commands (2026-11-19 边界复核第 2 项).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayControllerDuckCommandTest {
    @Test fun duckCommandsDriveMediaOnceViaTheSessionPlanAndStillForwardToTheUiListenerInOrder() {
        val ducks = mutableListOf<Boolean>()
        val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
        val media = object : AirPlayMediaHandler {
            override fun onAudioDuck(session: AirPlaySession, ducked: Boolean) { ducks += ducked }
        }
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                    manufacturer = "test", serialNumber = "test", firmwareVersion = "1",
                    hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
            ),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener {
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                    commands += type to params
                }
            },
            media,
            {},
        )
        val sessionListener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        val session = AirPlaySession(
            Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, sessionListener, media,
        )
        val handle = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
        fun command(type: String, vararg params: Pair<String, Any?>) {
            val body = BplistCodec.encode(mapOf("type" to type, "params" to params.toMap()))
            handle.invoke(session, RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), body))
        }
        try {
            command("duckAudio", "durationMs" to 500L, "volume" to 0.3)
            command("modesChanged")
            command("unduckAudio")
            // The media side observes exactly one action per duck command, from the session's plan.
            assertEquals(listOf(true, false), ducks)
            // The generic listener still receives the three commands in their original order.
            assertEquals(listOf("duckAudio", "modesChanged", "unduckAudio"), commands.map { it.first })
            // Removing the controller's own interpretation dropped no data: the raw params still
            // reach the listener exactly as the iPhone sent them.
            assertEquals(500L, (commands[0].second["durationMs"] as Number).toLong())
            assertEquals(0.3, (commands[0].second["volume"] as Number).toDouble(), 1e-6)
        } finally {
            session.close()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }
}
