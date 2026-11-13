package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

/** Actual JNI, compared against the independent pre-migration implementation on this platform. */
class NativeLocationReportingDeviceTest {
    @Test fun nmeaKeepsPlatformRoundingUnknownsSignedZeroAndCalendar() {
        NativeMedia.requireAvailable()
        val base = CarPlayLocationFix(52.520008, 13.404954, 34.56, 271.5, 13.9, 12.0, 1759665845000)
        val fixes = listOf(base,
            base.copy(latitudeDegrees = -33.8688, longitudeDegrees = -151.2093,
                altitudeMeters = null, bearingDegrees = null, speedMetersPerSecond = null, accuracyMeters = null),
            base.copy(latitudeDegrees = -0.0, longitudeDegrees = -0.0,
                altitudeMeters = -0.0, bearingDegrees = -0.0, speedMetersPerSecond = -0.0, accuracyMeters = 0.1),
            base.copy(latitudeDegrees = 89.99999999, longitudeDegrees = -179.99999999,
                altitudeMeters = Double.NaN, bearingDegrees = Double.POSITIVE_INFINITY,
                speedMetersPerSecond = -1.0, accuracyMeters = Double.POSITIVE_INFINITY),
            base.copy(latitudeDegrees = 90.0, longitudeDegrees = -180.0,
                altitudeMeters = 2.05, bearingDegrees = 2.005, speedMetersPerSecond = Double.MAX_VALUE, accuracyMeters = 1000.0),
            base.copy(altitudeMeters = Double.NEGATIVE_INFINITY, bearingDegrees = Double.NaN,
                speedMetersPerSecond = Double.NaN, accuracyMeters = -1.0, timestampMillis = 1),
            base.copy(timestampMillis = Long.MAX_VALUE))
        for (fix in fixes) assertEquals(fix.toString(), KotlinNmeaLocationEncoder.encode(fix), NmeaLocationEncoder.encode(fix))
        val noCourse = NmeaLocationEncoder.encode(base.copy(bearingDegrees = null))
        assertTrue(noCourse.contains(",27.02,,051025,,*"))
        assertTrue(NmeaLocationEncoder.encode(fixes[4]).contains(",Infinity,"))
    }

    @Test fun locationAttemptsNullFixAndFailedSendKeepTheOriginalTrace() {
        assertEquals(locationTrace(false), locationTrace(true))
    }

    private fun locationTrace(native: Boolean): List<String> {
        val trace = mutableListOf<String>()
        var now = 0L
        var sentence: String? = null
        var failSend = false
        val provider = object : Iap2LocationProvider {
            override fun onRequested(components: Set<Int>) { trace += "requested=$components" }
            override fun start(): Boolean { trace += "start"; return true }
            override fun stop() { trace += "stop" }
            override fun latestNmea(): String? { trace += "fix"; return sentence }
        }
        val clock = { trace += "clock=$now"; now }
        val log: (String) -> Unit = { trace += it }
        val real = if (native) Iap2LocationReporter(provider, log, clock) else null
        val old = if (!native) KotlinIap2LocationReporter(provider, log, clock) else null
        val send: (Iap2Frame) -> Unit = { trace += "send=${it.messageId}"; if (failSend) throw IllegalStateException("send") }
        fun tick() { if (native) real!!.tick(send) else old!!.tick(send) }
        fun timeout(): Long = if (native) real!!.pollTimeout(5000) else old!!.pollTimeout(5000)
        fun handle(id: Int) { if (native) real!!.handle(Iap2Frame(id, byteArrayOf()), send) else old!!.handle(Iap2Frame(id, byteArrayOf()), send) }
        trace += "inactive=${timeout()}"
        handle(Iap2LocationMessages.START_LOCATION_INFORMATION) // null fix still consumes an attempt
        now = 999_999_999
        tick()
        trace += "poll=${timeout()}"
        now = 1_000_000_000
        sentence = "\$GPRMC,fixture*00\r\n"
        failSend = true
        try { tick(); fail("send failure expected") } catch (error: IllegalStateException) { trace += "caught=${error.message}" }
        trace += "failedPoll=${timeout()}"
        failSend = false
        now = 2_000_000_000
        tick()
        now = 3_000_000_000
        tick() // first-send log is emitted once
        handle(Iap2LocationMessages.STOP_LOCATION_INFORMATION)
        trace += "stopped=${timeout()}"
        tick()
        return trace
    }

    @Test fun reentrantClockKeepsActiveSnapshotAndCurrentAttemptTimestamp() {
        fun trace(native: Boolean): List<String> {
            val events = mutableListOf<String>()
            var now = Long.MAX_VALUE - 500_000_000
            var reenter = false
            lateinit var stop: () -> Unit
            val provider = object : Iap2LocationProvider {
                override fun start() = true
                override fun stop() { events += "stop" }
                override fun latestNmea() = "\$GPRMC,fixture*00\r\n"
            }
            val clock = { if (reenter) { reenter = false; stop() }; events += "clock"; now }
            val send: (Iap2Frame) -> Unit = { events += "send" }
            val real = if (native) Iap2LocationReporter(provider, { events += it }, clock) else null
            val old = if (!native) KotlinIap2LocationReporter(provider, { events += it }, clock) else null
            stop = { if (native) real!!.handle(Iap2Frame(0xfffc, byteArrayOf()), send) else old!!.handle(Iap2Frame(0xfffc, byteArrayOf()), send); Unit }
            if (native) real!!.handle(Iap2Frame(0xfffa, byteArrayOf()), send) else old!!.handle(Iap2Frame(0xfffa, byteArrayOf()), send)
            now = Long.MIN_VALUE + 500_000_000
            reenter = true
            if (native) real!!.tick(send) else old!!.tick(send)
            events += "poll=" + if (native) real!!.pollTimeout(-7) else old!!.pollTimeout(-7)
            return events
        }
        val actual = trace(true)
        assertEquals(trace(false), actual)
        assertEquals(2, actual.count { it == "send" }) // clock's stop does not undo the already-read active
    }

    @Test fun vehicleUsesSuccessfulSendThirtySecondsAndNoPollClock() {
        fun trace(native: Boolean): List<String> {
            val events = mutableListOf<String>()
            var now = 0L
            var snapshot: VehicleStatusSnapshot? = null
            var failSend = false
            val provider = VehicleStatusProvider { events += "snapshot"; snapshot }
            val clock = { events += "clock=$now"; now }
            val log: (String) -> Unit = { events += it }
            val real = if (native) Iap2VehicleStatusReporter(provider, log, clock) else null
            val old = if (!native) KotlinIap2VehicleStatusReporter(provider, log, clock) else null
            val send: (Iap2Frame) -> Unit = { events += "send"; if (failSend) throw IllegalStateException("send") }
            fun handle(id: Int) { if (native) real!!.handle(Iap2Frame(id, byteArrayOf()), send) else old!!.handle(Iap2Frame(id, byteArrayOf()), send) }
            fun tick() { if (native) real!!.tick(send) else old!!.tick(send) }
            fun timeout() = if (native) real!!.pollTimeout(50_000) else old!!.pollTimeout(50_000)
            handle(0xa100) // null snapshot: no clock and no send timestamp
            events += "poll=${timeout()}"
            now = 30_000_000_000
            snapshot = VehicleStatusSnapshot(200, false, 50.0, null, null, 300)
            failSend = true
            try { tick(); fail("send failure expected") } catch (error: IllegalStateException) { events += "caught=${error.message}" }
            failSend = false
            tick() // failed send did not advance lastSent
            now += 29_999_999_999
            tick()
            now++
            tick()
            handle(0xa102)
            events += "stopped=${timeout()}"
            tick()
            return events
        }
        assertEquals(trace(false), trace(true))
    }

    @Test fun nativeCarrierRejectsInvalidShapeAndKeepsIndependentSubscriptions() {
        val location = NativeIap2ReportSubscription.create(false)
        val vehicle = NativeIap2ReportSubscription.create(true)
        NativeIap2ReportSubscription.start(location, true)
        assertTrue(NativeIap2ReportSubscription.active(location))
        assertFalse(NativeIap2ReportSubscription.active(vehicle))
        NativeIap2ReportSubscription.mark(location, Long.MAX_VALUE - 500_000_000)
        assertTrue(NativeIap2ReportSubscription.due(location, Long.MIN_VALUE + 500_000_000))
        assertTrue(NativeIap2ReportSubscription.firstSent(location))
        assertFalse(NativeIap2ReportSubscription.firstSent(location))
        NativeIap2ReportSubscription.stop(location)
        assertFalse(NativeIap2ReportSubscription.active(location))
        try {
            NativeIap2ReportSubscription.active(longArrayOf())
            fail("invalid carrier expected")
        } catch (_: IllegalArgumentException) { }
    }
}
