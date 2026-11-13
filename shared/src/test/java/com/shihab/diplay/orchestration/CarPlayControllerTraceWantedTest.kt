package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.*
import com.shihab.diplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerTraceWantedTest {
    @Test fun sessionsBuildTraceTextOnlyWhenTheHostWantsIt() {
        assertEquals(false, wantsTrace(host(wants = false)))
        assertEquals(true, wantsTrace(host(wants = true)))
    }

    @Test fun sessionLogsReachTheHostWithoutASecondLogcatEntry() {
        val messages = mutableListOf<String>()
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { messages += message }
            }, object : AirPlayMediaHandler {}, {},
        )
        try {
            val listener = controller.javaClass.getDeclaredField("sessionListener")
                .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
            AirPlaySession(Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity.generate(), PairingStore(), null, listener, object : AirPlayMediaHandler {}).use { session ->
                ShadowLog.clear()
                session.logDebug("session log forwarding test")
                assertEquals(listOf("session log forwarding test"), messages)
                assertEquals(1, ShadowLog.getLogs().count { it.msg == "session log forwarding test" })
            }
        } finally {
            controller.close()
            controller.awaitClosed(2_000)
        }
    }

    private fun host(wants: Boolean) = object : AirPlaySessionListener {
        override val wantsTrace: Boolean get() = wants
    }

    private fun wantsTrace(host: AirPlaySessionListener): Boolean {
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), host, object : AirPlayMediaHandler {}, {},
        )
        try {
            val listener = controller.javaClass.getDeclaredField("sessionListener")
                .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
            return listener.wantsTrace
        } finally {
            controller.close()
            controller.awaitClosed(2_000)
        }
    }
}
