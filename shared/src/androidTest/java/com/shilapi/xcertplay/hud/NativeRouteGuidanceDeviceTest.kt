package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class NativeRouteGuidanceDeviceTest {
    private class Trace(keepNoRoute: Boolean = false) : AutoCloseable {
        var now = 0L
        var wall = 1_700_000_000L
        var live = false
        val actual = RouteGuidanceState({ now }, keepAcrossNoRoute = keepNoRoute,
            liveSession = { live }, wallClockSeconds = { wall })
        val expected = KotlinRouteGuidanceState({ now }, { wall }, keepAcrossNoRoute = keepNoRoute, liveSession = { live })

        fun send(id: Int = 0x5201, block: Iap2BodyBuilder.() -> Unit) = payload(id, Iap2Messages.buildRaw(id, block).payload)
        fun payload(id: Int, bytes: ByteArray) {
            assertEquals(expected.accept(id, bytes), actual.accept(id, bytes))
            checkState()
        }
        fun start() {
            send(0x5202) { u16(1, 7); u8(3, 2); string(4, "滨海路😀"); u8(8, 1) }
            send { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 350); string(4, "目的地") }
        }
        fun checkState() {
            assertEquals(expected.activeIndex, actual.activeIndex)
            assertEquals(expected.announced, actual.announced)
            assertEquals(expected.inputState, actual.inputState)
            assertEquals(expected.lastPhoneState, actual.lastPhoneState)
            assertEquals(expected.phoneStateAgeMs, actual.phoneStateAgeMs)
            assertEquals(expected.expired, actual.expired)
            assertEquals(expected.destinationName, actual.destinationName)
            assertEquals(expected.laneGuidanceIndex, actual.laneGuidanceIndex)
            assertEquals(expected.laneGuidanceShowing, actual.laneGuidanceShowing)
            assertEquals(expected.arrivalEpochSeconds, actual.arrivalEpochSeconds)
            assertEquals(expected.remainingMeters, actual.remainingMeters)
            assertEquals(expected.diagnosticInput, actual.diagnosticInput)
            assertEquals(expected.diagnosticState(), actual.diagnosticState())
            assertEquals(expected.current(), actual.current())
            assertEquals(expected.currentForDim(), actual.currentForDim())
        }
        fun clear(reason: String = "clear") {
            assertEquals(expected.clear(reason), actual.clear(reason))
            checkState()
        }
        override fun close() = actual.close()
    }

    @Test fun nativeTimeAndExplicitEndMatchTheExistingOwner() {
        assertTrue(NativeMedia.available)
        for (keep in listOf(false, true)) Trace(keep).use { trace ->
            trace.start()
            trace.now = 29_999_999_999L; trace.checkState()
            trace.now++; trace.checkState()
            trace.live = true; trace.now = 90_000_000_000L; trace.checkState()
            trace.send { u16List(0x0d, emptyList()) }
            trace.now += 2_000_000_000L; trace.send { u16List(0x0d, emptyList()) }
            trace.now += 1_000_000_000L; trace.checkState()
            trace.send { u16List(0x0d, listOf(7)) }
            trace.send { u8(1, 0); string(4, "ignored on retained output"); u64(5, 99) }
            trace.send { u8(1, 2) }
            trace.start()
            trace.clear("手动😀\u0000clear")
            trace.clear()
            trace.send { u8(1, 6); u16List(0x0d, listOf(7)) }
            trace.send(0x5202) { u16(1, 7); u8(3, 99); u8(8, 255) }
        }
    }

    @Test fun nativeTimingFieldsMatchEpochDurationUnknownAndLongBoundaries() {
        assertTrue(NativeMedia.available)
        Trace().use { trace ->
            trace.start()
            for (raw in listOf(0L, 1L, 999_999_999L, 1_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)) {
                val wire = java.nio.ByteBuffer.allocate(8).putLong(raw).array()
                trace.send { bytes(5, wire) }
                trace.wall += 20
                trace.send { u32(0x0a, 300) }
                trace.send { bytes(6, wire) }
                trace.send { bytes(5, wire); u64(6, 60); u32(7, 0xffff_ffffL) }
                trace.send { u64(5, 0); bytes(6, wire) }
            }
            trace.clear()
            trace.now = Long.MAX_VALUE - 1_000_000_000L; trace.start()
            trace.now += 29_999_999_999L; trace.checkState()
            trace.now++; trace.checkState()
        }
    }

    @Test fun nativeTlvLengthsDuplicatesAndMalformedMessagesAreAtomic() {
        assertTrue(NativeMedia.available)
        Trace().use { trace ->
            trace.start()
            for (id in listOf(1, 3, 4, 5, 6, 7, 0x0a, 0x0d, 0x10, 0x12, 65535)) {
                for (length in 0..9) trace.send { bytes(id, ByteArray(length) { 0xff.toByte() }) }
            }
            trace.start()
            trace.send { u16List(0x0d, listOf(7)); u16List(0x0d, emptyList()); u8(1, 3); u8(1, 6) }
            val frame = Iap2Messages.buildRaw(0x5201) { u32(0x0a, 0); u16List(0x0d, listOf(7)); string(4, "目标") }.payload
            for (length in frame.indices) trace.payload(0x5201, frame.copyOf(length))
            trace.payload(0x5201, frame + byteArrayOf(0, 3, 0, 1))
            trace.payload(0x1234, frame)
        }
    }

    @Test fun nativeTextMatchesJavaUtf8ReplacementNulAndTrim() {
        assertTrue(NativeMedia.available)
        Trace().use { trace ->
            trace.start()
            // Android replaces encoded-surrogate bytes individually; a desktop JDK can merge them.
            val malformed = listOf(byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
                byteArrayOf(0xed.toByte(), 0xa0.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 65),
                byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte()), byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()))
            val values = malformed + listOf("\u001c\u00a0路😀\u2007\u001f\u0000ignored".toByteArray(),
                "\u0085road\u0085".toByteArray(), "未知位置\u0000".toByteArray(), ByteArray(0)) +
                (0..255).map { byteArrayOf(it.toByte()) }
            for (value in values) {
                trace.send(0x5202) { u16(1, 7); u8(3, 2); bytes(4, value) }
                trace.send { bytes(3, value); bytes(4, value) }
            }
        }
    }

    @Test fun nativeManeuverQueriesKeepLocalReferencesBoundedAndOwnersIndependent() {
        assertTrue(NativeMedia.available)
        Trace().use { first -> Trace().use { second ->
            first.start()
            first.send { u64(5, 100); u64(6, 90); u32(7, 5000) }
            repeat(10_000) { assertNotNull(first.actual.currentForDim()) }
            second.start(); second.send { u32(0x0a, 0) }; second.clear()
            first.checkState(); second.checkState()
            first.actual.close(); first.actual.close()
            assertThrows(IllegalStateException::class.java) { first.actual.current() }
        } }
    }

    @Test fun singletonGlanceConsumesTheNativeOwnerAndClearsOnDisconnect() {
        assertTrue(NativeMedia.available)
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2); string(4, "滨海路😀") })
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 350); u64(5, 1_800_000_000)
        })
        val snapshot = CarPlayGlance.snapshot()
        assertEquals(2, snapshot.maneuverType); assertEquals(350, snapshot.distanceMeters)
        assertEquals("滨海路😀", snapshot.road); assertEquals(1_800_000_000L, snapshot.arrivalEpochSeconds)
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5201) { u8(1, 2) })
        assertNull(CarPlayGlance.snapshot().maneuverType)
        CarPlayGlance.setConnected(false)
        assertEquals(CarPlayGlance.Snapshot(), CarPlayGlance.snapshot())
    }
}
