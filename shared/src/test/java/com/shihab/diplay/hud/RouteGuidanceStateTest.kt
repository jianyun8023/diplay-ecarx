package com.shihab.diplay.hud

import com.shihab.diplay.iap2.body.Iap2BodyBuilder
import com.shihab.diplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceStateTest {
    private fun RouteGuidanceState.send(id: Int = 0x5201, block: Iap2BodyBuilder.() -> Unit) =
        accept(id, Iap2Messages.buildRaw(id, block).payload)

    private fun RouteGuidanceState.start() {
        send(0x5202) { u16(1, 7); u8(3, 2); string(4, "滨海路😀") }
        send { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 350) }
    }

    @Test fun injectedWallClockAnchorsEtaAndOnlyNewTimingFieldsReplaceIt() {
        var wall = 1_700_000_000L
        RouteGuidanceState(wallClockSeconds = { wall }).use { route ->
            route.start()
            route.send { u64(5, 100); u32(7, 4000) }
            assertEquals(1_700_000_100L, route.arrivalEpochSeconds)
            wall += 20
            route.send { u32(0x0a, 300) }
            assertEquals(1_700_000_100L, route.current()?.arrivalEpochSeconds)
            route.send { u64(6, 300) }
            assertEquals(wall + 300, route.arrivalEpochSeconds)
            assertEquals(100L, route.current()?.etaRawSeconds)
            route.send { u64(5, 1_800_000_000); u64(6, 60) }
            assertEquals(1_800_000_000L, route.arrivalEpochSeconds)
            route.send { u64(5, 0); u64(6, 0) }
            assertEquals(wall, route.arrivalEpochSeconds)
            assertEquals(0L, route.current()?.remainingSeconds)
            assertNull(route.current()?.etaRawSeconds)
        }
    }

    @Test fun liveSessionExtendsQuietRouteButEmptyListStillHidesIt() {
        var now = 0L
        var live = true
        RouteGuidanceState(nanoTime = { now }, liveSession = { live }).use { route ->
            route.start()
            now = 90_000_000_000L
            assertFalse(route.expired)
            assertNotNull(route.currentForDim())
            live = false
            assertTrue(route.expired)
            assertNull(route.current())
            live = true
            route.send { u16List(0x0d, emptyList()) }
            now += 3_000_000_000L
            assertNull(route.current())
            assertTrue(route.diagnosticState().contains("reason=empty-list-timeout"))
            route.send { u16List(0x0d, listOf(7)) }
            assertEquals("滨海路😀", route.currentForDim()?.road)
        }
    }

    @Test fun clearKeepsTerminalDiagnosticsAndRequiresNewManeuverDetails() {
        var now = 0L
        RouteGuidanceState(nanoTime = { now }).use { route ->
            assertNull(route.inputState); assertNull(route.phoneStateAgeMs)
            assertNull(route.laneGuidanceShowing); assertNull(route.remainingMeters)
            route.start()
            now = 1_000_000L
            assertEquals(RouteGuidanceChange.CLEAR, route.send { u8(1, 2) })
            assertEquals(2, route.inputState); assertEquals(2, route.lastPhoneState)
            now += 2_000_000L
            assertEquals(2L, route.phoneStateAgeMs)
            assertFalse(route.clear())
            assertEquals(2, route.lastPhoneState)
            route.send { u8(1, 1); u16List(0x0d, listOf(7)) }
            assertNull(route.current())
        }
    }

    @Test fun retainedNoRouteDoesNotExtendItsDeadlineOrChangeDestination() {
        var now = 0L
        RouteGuidanceState(nanoTime = { now }, keepAcrossNoRoute = true).use { route ->
            route.start()
            route.send { string(4, "destination") }
            now = 29_000_000_000L
            assertEquals(RouteGuidanceChange.NONE, route.send { u8(1, 0); string(4, "ignored") })
            assertNull(route.inputState)
            assertEquals(1, route.lastPhoneState)
            assertEquals("destination", route.destinationName)
            now += 1_000_000_000L
            assertNull(route.current())
            assertEquals(RouteGuidanceChange.CLEAR, route.send { u8(1, 2) })
        }
    }

    @Test fun malformedPayloadCannotPartiallyUpdateDistanceAndUnknownLaneValuesStayUnknown() {
        RouteGuidanceState().use { route ->
            route.start()
            val valid = Iap2Messages.buildRaw(0x5201) { u32(0x0a, 0) }.payload
            route.accept(0x5201, valid + byteArrayOf(0, 3, 0, 1))
            assertEquals(350, route.currentForDim()?.distanceMeters)
            assertEquals("invalid-tlv bytes=12", route.diagnosticInput)
            route.send { bytes(0x10, byteArrayOf(0)); bytes(0x12, byteArrayOf(0, 0)) }
            assertNull(route.laneGuidanceIndex); assertNull(route.laneGuidanceShowing)
            route.send { u16(0x10, 0); bool(0x12, false); u32(7, 0xffff_ffffL) }
            assertEquals(0, route.laneGuidanceIndex); assertEquals(false, route.laneGuidanceShowing)
            assertEquals(0xffff_ffffL, route.remainingMeters)
            route.close(); route.close()
        }
    }
}
