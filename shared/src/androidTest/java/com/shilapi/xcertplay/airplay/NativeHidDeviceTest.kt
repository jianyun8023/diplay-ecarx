package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class NativeHidDeviceTest {
    @Test fun nativeTouchMatchesAndroidRoundingSpecialValuesAndFirstSlotIds() {
        assertTrue(NativeMedia.available)
        val values = listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -Double.MAX_VALUE,
            -1.5, -0.0, 0.0, Double.MIN_VALUE, 0.49999999999999994, 0.5, Math.nextUp(0.5), 1.5,
            65535.49, 65535.5, 65536.5, 70000.0, 4294967297.0, 9_223_372_036_854_774_784.0,
            Long.MAX_VALUE.toDouble(), Double.MAX_VALUE)
        assertArrayEquals(KotlinAirPlayHid.touchReport(emptyList()), AirPlayHid.touchReport(emptyList()))
        for (x in values) for (y in values) for (down in listOf(false, true)) {
            val contacts = listOf(AirPlayContact(2, 99.0, 99.0, true), AirPlayContact(1, y, x, !down),
                AirPlayContact(1, 1.0, 1.0, down), AirPlayContact(0, x, y, down),
                AirPlayContact(0, 2.0, 2.0, !down), AirPlayContact(-1, 3.0, 3.0, true))
            assertArrayEquals(KotlinAirPlayHid.touchReport(contacts), AirPlayHid.touchReport(contacts))
        }
        val random = java.util.Random(96)
        repeat(512) {
            val contacts = listOf(AirPlayContact(it % 2, Double.fromBits(random.nextLong()), Double.fromBits(random.nextLong()), it % 3 == 0))
            assertArrayEquals(KotlinAirPlayHid.touchReport(contacts), AirPlayHid.touchReport(contacts))
        }
    }

    @Test fun nativeKnobAndIndexedButtonsMatchAllBitsClampsAndNarrowing() {
        assertTrue(NativeMedia.available)
        val axes = listOf(Int.MIN_VALUE, -128, -127, -1, 0, 1, 127, 128, Int.MAX_VALUE)
        for (flags in 0..7) for (x in axes) for (y in axes) for (wheel in axes) {
            val state = AirPlayKnobState(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, x, y, wheel)
            assertArrayEquals(KotlinAirPlayHid.knobReport(state), AirPlayHid.knobReport(state))
        }
        for (index in axes + listOf(-65537, -257, -256, 255, 256, 65535, 65536)) {
            assertArrayEquals(KotlinAirPlayHid.mediaReport(index), AirPlayHid.mediaReport(index))
            assertArrayEquals(KotlinAirPlayHid.telephonyReport(index), AirPlayHid.telephonyReport(index))
        }
    }

    @Test fun nativeDescriptorsKeepBytesBoundsAndDeviceRegistrationOrder() {
        assertTrue(NativeMedia.available)
        val bounds = listOf(Int.MIN_VALUE, -65536, -1, 0, 1, 1080, 1920, 65535, 65536, Int.MAX_VALUE)
        for (x in bounds) for (y in bounds) {
            assertArrayEquals(KotlinAirPlayHid.descriptor(0, x, y),
                AirPlayHid.touchHidDevice(x, y, "display\u0000😀")["hidDescriptor"] as ByteArray)
        }
        for ((kind, device) in listOf(AirPlayHid.touchHidDevice(1920, 1080, "main"), AirPlayHid.knobHidDevice("main"),
            AirPlayHid.mediaHidDevice("main"), AirPlayHid.telephonyHidDevice("main")).withIndex()) {
            assertEquals(listOf("hidProductID", "hidVendorID", "hidCountryCode", "uuid", "name", "displayUUID", "hidDescriptor"), device.keys.toList())
            assertEquals((AirPlayHid.TOUCH_HID_UID + kind).toString(16), device["uuid"])
            assertEquals("xcertplay ${listOf("Touchscreen", "Knob", "Media", "Telephony")[kind]}", device["name"])
            assertEquals(1L, device["hidProductID"]); assertEquals(2L, device["hidVendorID"])
            assertEquals(0L, device["hidCountryCode"]); assertEquals("main", device["displayUUID"])
            assertArrayEquals(KotlinAirPlayHid.descriptor(kind, 1920, 1080), device["hidDescriptor"] as ByteArray)
        }
    }

    @Test fun invalidPrimitiveArrayShapesPreserveJavaExceptions() {
        assertTrue(NativeMedia.available)
        for ((xy, down) in listOf(doubleArrayOf() to booleanArrayOf(true), doubleArrayOf(1.0, 2.0) to booleanArrayOf())) {
            assertEquals("HID contact array lengths differ", assertThrows(IllegalArgumentException::class.java) {
                NativeMedia.hidTouchReport(intArrayOf(0), xy, down)
            }.message)
        }
        assertEquals("HID descriptor kind must be in 0..3", assertThrows(IllegalArgumentException::class.java) {
            NativeMedia.hidDescriptor(4, 1920, 1080)
        }.message)
        assertArrayEquals(byteArrayOf(1), AirPlayHid.mediaReport(1))
    }

    @Test fun actualSessionWritesTouchKnobMediaAndTelephonyToTheEncryptedEventChannel() {
        assertTrue(NativeMedia.available)
        val config = config()
        AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).use { session ->
            val wire = ByteArrayOutputStream()
            val event = object : Socket() { override fun getOutputStream() = wire }
            val sender = ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 })
            for ((name, value) in listOf("eventSocket" to event, "eventCipher" to sender)) {
                AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val expected = mutableListOf<Pair<Int, ByteArray>>()
            assertTrue(session.sendTouch(listOf(AirPlayContact(9, .5, .25, true), AirPlayContact(42, .25, .5, true))))
            expected += AirPlayHid.TOUCH_HID_UID to KotlinAirPlayHid.touchReport(listOf(
                AirPlayContact(0, 400.0, 120.0, true), AirPlayContact(1, 200.0, 240.0, true)))
            assertTrue(session.sendTouch(listOf(AirPlayContact(42, .3, .6, true))))
            expected += AirPlayHid.TOUCH_HID_UID to KotlinAirPlayHid.touchReport(listOf(
                AirPlayContact(0, 400.0, 120.0, false), AirPlayContact(1, 240.0, 288.0, true)))
            assertTrue(session.sendTouch(emptyList()))
            expected += AirPlayHid.TOUCH_HID_UID to KotlinAirPlayHid.touchReport(listOf(AirPlayContact(1, 240.0, 288.0, false)))
            val knob = AirPlayKnobState(select = true, back = true, x = -200, y = 128, wheel = -1)
            session.sendKnob(knob)
            expected += AirPlayHid.KNOB_HID_UID to KotlinAirPlayHid.knobReport(knob)
            expected += AirPlayHid.KNOB_HID_UID to byteArrayOf(0, 0, 0, 0)
            session.sendKnobSelect(true); session.sendKnobSelect(false)
            expected += AirPlayHid.KNOB_HID_UID to byteArrayOf(1, 0, 0, 0)
            expected += AirPlayHid.KNOB_HID_UID to byteArrayOf(0, 0, 0, 0)
            session.sendMedia(260); session.sendTelephony(-1)
            expected += AirPlayHid.MEDIA_HID_UID to byteArrayOf(4)
            expected += AirPlayHid.MEDIA_HID_UID to byteArrayOf(0)
            expected += AirPlayHid.TELEPHONY_HID_UID to byteArrayOf(-1)
            expected += AirPlayHid.TELEPHONY_HID_UID to byteArrayOf(0)
            val decrypted = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 }).use { it.decrypt(wire.toByteArray()) }
            assertTrue(decrypted.rest.isEmpty())
            val parsed = RtspMessage.parseMessages(decrypted.data)
            assertTrue(parsed.rest.isEmpty()); assertEquals(expected.size, parsed.messages.size)
            for ((request, wanted) in parsed.messages.zip(expected)) {
                assertEquals("POST", request.method); assertEquals("/command", request.path)
                val body = BplistCodec.decode(request.body) as Map<*, *>
                assertEquals(listOf("type", "uuid", "hidReport"), body.keys.toList())
                assertEquals("hidSendReport", body["type"]); assertEquals(wanted.first.toString(16), body["uuid"])
                assertArrayEquals(wanted.second, body["hidReport"] as ByteArray)
            }
        }
    }

    @Test fun actualInfoEndpointRegistersAllFourNativeDescriptors() {
        assertTrue(NativeMedia.available)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                AirPlaySession(server.accept(), config(), AirPlayIdentity.generate(), PairingStore(), null,
                    object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).use { session ->
                    session.start(); client.soTimeout = 5000
                    client.getOutputStream().apply { write("GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray()); flush() }
                    val header = ByteArrayOutputStream()
                    while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) {
                        val next = client.getInputStream().read(); check(next >= 0); header.write(next)
                    }
                    val head = header.toString("US-ASCII")
                    assertTrue(head.startsWith("RTSP/1.0 200 OK\r\n"))
                    val count = head.substringAfter("Content-Length: ").substringBefore("\r\n").toInt()
                    val body = ByteArray(count).also { DataInputStream(client.getInputStream()).readFully(it) }
                    val info = BplistCodec.decode(body) as Map<*, *>
                    val devices = info["hidDevices"] as List<*>
                    assertEquals(4, devices.size)
                    for ((kind, raw) in devices.withIndex()) {
                        val device = raw as Map<*, *>
                        assertEquals((AirPlayHid.TOUCH_HID_UID + kind).toString(16), device["uuid"])
                        assertEquals(AirPlayInfoPlist.MAIN_UUID, device["displayUUID"])
                        assertArrayEquals(KotlinAirPlayHid.descriptor(kind, 800, 480), device["hidDescriptor"] as ByteArray)
                    }
                }
            }
        }
    }

    private fun config() = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
        main = AirPlayDisplayConfig(800, 480))
}
