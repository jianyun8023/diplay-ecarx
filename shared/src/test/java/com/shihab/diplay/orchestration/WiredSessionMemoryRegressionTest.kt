package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Regression coverage for the two conditions behind the wired reconnect fix:
 * StartSession progress without AirPlay RECORD must not remember the enumeration, and an AirPlay
 * session becoming active must.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WiredSessionMemoryRegressionTest {
    private val deviceName = "/dev/bus/usb/001/005"

    @After fun clearMemory() {
        WiredSessionMemory.clear()
    }

    @Test fun startSessionProgressWithoutAirPlayRecordDoesNotRememberTheEnumeration() {
        val controller = controller()
        try {
            ReflectionHelpers.setField(controller, "wiredDeviceName", deviceName)
            val watchdog = WiredStartWatchdog({ _, _ -> {} }, {})
            controller.javaClass.getDeclaredMethod(
                "handleWiredControlProgress",
                String::class.java,
                WiredStartWatchdog::class.java,
            ).apply { isAccessible = true }.invoke(controller, "iap2 tx=0x4301", watchdog)

            assertFalse(WiredSessionMemory.startedOn(deviceName))
            // A failed first attempt must retry on the configuration the iPhone already offers.
            assertEquals(
                WiredConfigurationAction.USE,
                WiredConfigurationAction.choose(
                    found = true,
                    carPlayMode = true,
                    transitionsTried = 0,
                    maximumTransitions = 2,
                    sessionAlreadyStarted = WiredSessionMemory.startedOn(deviceName),
                ),
            )
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    @Test fun airPlaySessionBecomingActiveRemembersTheEnumeration() {
        val controller = controller()
        val session = session()
        try {
            ReflectionHelpers.setField(controller, "wiredDeviceName", deviceName)
            val listener = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")

            listener.onSessionActive(session)

            assertTrue(WiredSessionMemory.startedOn(deviceName))
            // A genuinely active session still keeps the #335 transition behaviour.
            assertEquals(
                WiredConfigurationAction.REQUEST_TRANSITION,
                WiredConfigurationAction.choose(
                    found = true,
                    carPlayMode = true,
                    transitionsTried = 0,
                    maximumTransitions = 2,
                    sessionAlreadyStarted = WiredSessionMemory.startedOn(deviceName),
                ),
            )
        } finally {
            session.close()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private fun controller() = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRED,
            identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                carPlayUsbInterfaceNumber = 3,
            ),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(),
        PairingStore(),
        object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {},
        {},
    )

    private fun session() = AirPlaySession(
        Socket(),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(),
        PairingStore(),
        null,
        object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {},
    )
}
