package com.shihab.diplay.airplay

import java.util.Date
import java.io.ByteArrayInputStream
import com.shihab.diplay.network.CarPlayBonjourProtocol
import com.shihab.diplay.network.KotlinCarPlayBonjourProtocol
import org.junit.Assert.*

/** Independent pre-migration implementations also exercise the Android JNI boundary. */
internal object AirPlayProtocolContract {
    fun cornerMasks() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        val opaque = object { override fun toString(): String = error("opaque leaf must stay opaque") }
        val unread = object : AbstractMap<String, Any?>() {
            override val entries: Set<Map.Entry<String, Any?>> get() = error("unvisited subtree must stay unvisited")
        }
        val cases = listOf(null, opaque, first, listOf(null, opaque, first),
            linkedMapOf("topLeftCornerMask" to first, "unvisited" to unread),
            listOf(mapOf("topLeftCornerMask" to first), unread),
            linkedMapOf("wrong" to opaque, "a" to mapOf("topLeftCornerMask" to first), "b" to mapOf("topLeftCornerMask" to second)),
            mapOf("topLeftCornerMask" to "wrong", "nested" to mapOf("topLeftCornerMask" to byteArrayOf())),
            List(1000) { mapOf("not-mask" to it) } + mapOf("topLeftCornerMask" to first))
        for (value in cases) {
            assertSame(kotlinFindIphoneCornerMask(value), findIphoneCornerMask(value))
        }
        for (layers in 0..8) for (depth in listOf(-1, 0, 1, 6, 7, Int.MAX_VALUE)) {
            var tree: Any = mapOf("topLeftCornerMask" to first)
            repeat(layers) { tree = listOf(tree) }
            assertSame(kotlinFindIphoneCornerMask(tree, depth), findIphoneCornerMask(tree, depth))
        }
        val old = runCatching { kotlinFindIphoneCornerMask(unread) }.exceptionOrNull()
        val new = runCatching { findIphoneCornerMask(unread) }.exceptionOrNull()
        assertEquals(old?.javaClass, new?.javaClass)
        assertEquals(old?.message, new?.message)
    }

    fun commandsAndBonjour() {
        fun command(type: String, vararg params: Pair<String, Any?>): Map<String, Any?> =
            linkedMapOf("type" to type, "params" to linkedMapOf(*params))
        val bytes = byteArrayOf(0, 127, -1)
        equal(command("setVideoPlaybackAllowed", "videoPlaybackAllowed" to true), AirPlayCommands.videoAllowed(true))
        equal(command("stopUI", "uuid" to "u\ud800"), AirPlayCommands.stopUi("u\ud800"))
        equal(command("showUI", "uuid" to "u", "url" to "map\u0000\udc00"), AirPlayCommands.showUi("u", "map\u0000\udc00"))
        equal(mapOf("type" to "forceKeyFrame"), AirPlayCommands.keyFrame())
        equal(command("forceKeyFrame", "uuid" to "u"), AirPlayCommands.keyFrame("u"))
        for (flag in listOf(false, true)) {
            equal(command("changeMapZoomLevel", "uuid" to AirPlayInfoPlist.ALT_UUID, "zoomDirection" to if (flag) 0 else 1), AirPlayCommands.zoom(flag))
            equal(command("setNightMode", "nightMode" to flag), AirPlayCommands.night(flag))
            equal(command("changeModes", "appStates" to listOf(mapOf("appStateID" to 3, "state" to flag)),
                "reasonStr" to if (flag) "User requested navigation exit" else "Navigation exit arbitration complete"), AirPlayCommands.navigationExit(flag))
        }
        equal(command("requestSiri", "siriAction" to 2), AirPlayCommands.siri(2))
        equal(command("iAPSendMessage", "data" to bytes), AirPlayCommands.iap(bytes))
        equal(mapOf("params" to mapOf("data" to bytes)), AirPlayCommands.remoteControl(bytes))
        for (stream in listOf(null, 1L, Long.MIN_VALUE)) for (size in listOf(0, 123)) {
            val extra = stream?.let { "X-Apple-StreamID: $it\r\n" } ?: ""
            assertEquals("POST /command RTSP/1.0\r\n" + extra +
                "Content-Type: application/x-apple-binary-plist\r\nContent-Length: $size\r\nCSeq: -1\r\n\r\n",
                AirPlayCommands.eventHeader(-1, size, stream))
        }
        for (uid in listOf(AirPlayHid.TOUCH_HID_UID, -1, Int.MIN_VALUE)) {
            equal(mapOf("type" to "hidSendReport", "uuid" to uid.toString(16), "hidReport" to bytes), AirPlayCommands.hid(uid, bytes))
        }
        for (value in listOf(null, false, true, 0, 1, 1L, 1.0, "true")) {
            assertEquals(value == true || value == 1L, AirPlayCommands.needsKeepAlive(value))
        }
        for (keep in listOf(null, 7003)) {
            val expected = linkedMapOf<String, Any?>("timingPort" to 7001, "eventPort" to 7002)
            if (keep != null) expected["keepAlivePort"] = keep
            expected["enabledFeatures"] = listOf("iAPChannel")
            equal(expected, AirPlayCommands.setup(7001, 7002, keep, listOf("iAPChannel")))
        }
        for (type in listOf("modesChanged", "duckAudio", "unduckAudio", "suggestUI", "requestUI", "unknown")) {
            for (entity in listOf(null, -1L, 2, 3.5, Double.NaN, "3")) {
                val params = mapOf("appStates" to listOf("ignored", mapOf("appStateID" to 3L, "entity" to entity)), "url" to "videoplayback:")
                val plan = AirPlayCommands.incoming(type, params)
                assertEquals(type in listOf("modesChanged", "duckAudio", "unduckAudio", "suggestUI"), plan.noisy)
                assertEquals(when (type) { "duckAudio" -> true; "unduckAudio" -> false; else -> null }, plan.duck)
                assertEquals(if (type == "requestUI") 2 else 0, plan.ui)
                assertEquals(if (type == "modesChanged") (entity as? Number)?.toInt() else null, plan.navigationEntity)
            }
        }
        for (bits in listOf(0L, 0xffffffffL, 0x615653aee2L, -1L, Long.MIN_VALUE)) {
            assertEquals(KotlinCarPlayBonjourProtocol.featuresTxt(bits), CarPlayBonjourProtocol.featuresTxt(bits))
        }
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "bt", "366.0\ud800", AirPlayDisplayConfig(1280, 720))
        val identity = AirPlayIdentity(ByteArray(32), byteArrayOf(1, 2, -1), "pair\u0000\udc00")
        for (disabled in listOf(false, true)) {
            equal(KotlinCarPlayBonjourProtocol.airPlayTxtRecords(config.copy(disableAudioOutput = disabled), identity),
                CarPlayBonjourProtocol.airPlayTxtRecords(config.copy(disableAudioOutput = disabled), identity))
        }
        val hosts = listOf("192.168.1.1", "fe80::1%wlan0", "[fe80::1%wlan0]", "x\u0000\ud800", "", "\u00a0", "\u001c", "\u0085", "bad\rhost", "bad\nhost")
        for (host in hosts) for (port in listOf(0, 1, 65535, 65536)) for (version in listOf("366.0", "", "bad\rversion")) {
            val old = runCatching { KotlinCarPlayBonjourProtocol.connectProbeRequest(host, port, version, config.deviceId) }
            val new = runCatching { CarPlayBonjourProtocol.connectProbeRequest(host, port, version, config.deviceId) }
            assertEquals(old.getOrNull(), new.getOrNull())
            assertEquals(old.exceptionOrNull()?.javaClass, new.exceptionOrNull()?.javaClass)
            assertEquals(old.exceptionOrNull()?.message, new.exceptionOrNull()?.message)
        }
        for (line in listOf(null, "HTTP/1.1 200 OK", "HTTP/2 099", "HTTP/12 200", "HTTP/1.1 2000", "HTTP/1.1 200\n", "HTTP/1.1 200\r\n", "HTTP/1.1 200\u0085", "HTTP/1.1 200x", "HTTP/１.１ ２００ OK")) {
            val expected = line?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1) }
            assertEquals(expected, CarPlayBonjourProtocol.probeStatus(line))
        }
        for ((bytesIn, expected) in listOf(byteArrayOf() to null, "\n".toByteArray() to "",
            "HTTP/1.1 200 OK\r\nrest".toByteArray() to "HTTP/1.1 200 OK",
            "partial\r".toByteArray() to "partial\r", byteArrayOf(-1, 10) to "\ufffd",
            ("x".repeat(8192) + "\n").toByteArray() to "x".repeat(8192))) {
            assertEquals(expected, CarPlayBonjourProtocol.readProbeStatusLine(ByteArrayInputStream(bytesIn)))
        }
        val overflow = runCatching { CarPlayBonjourProtocol.readProbeStatusLine(ByteArrayInputStream("x".repeat(8193).toByteArray())) }.exceptionOrNull()
        assertTrue(overflow is java.io.IOException)
        assertEquals("AirPlay control probe status line over 8192 bytes", overflow?.message)
    }

    fun declarations() {
        val base = AirPlayConfig("name\u0000\ud800", "device", "bt", "366.0",
            AirPlayDisplayConfig(1280, 720))
        val displays = listOf(base.main,
            base.main.copy(widthPhysicalMm = -1, heightPhysicalMm = 3000, fps = 37, features = -1,
                safeArea = AirPlayInsets(20, 30, 40, 50), viewArea = AirPlayInsets(1, 2, 3, 4),
                initialUrl = "url\u0000\udc00", mainDisplayCornerMasks = true),
            base.main.copy(viewAreas = listOf(AirPlayViewArea(600, 360, dockEdge = 1),
                AirPlayViewArea(300, 200, 900, 500, 2)), initialViewArea = 30,
                safeArea = AirPlayInsets(500, 100, 950, 20), safeAreaDrawOutside = false),
            base.main.copy(widthPixels = 0, heightPixels = Int.MAX_VALUE, fps = Int.MIN_VALUE,
                viewAreas = emptyList(), initialViewArea = -1))
        for (bits in 0 until 128) for (rate in listOf(44100, 48000, 16000)) {
            val config = base.copy(main = displays[bits % displays.size], cluster = displays[(bits + 1) % displays.size],
                disableAudioOutput = bits and 1 != 0, microphone = bits and 2 != 0,
                microphoneOpus = bits and 4 != 0, mainBufferedAudio = bits and 8 != 0,
                speechWithout8And16Khz = bits and 16 != 0, callAtCallRate = bits and 32 != 0,
                videoInCar = bits and 64 != 0, entertainmentSampleRate = rate, hevc = bits and 4 != 0,
                icons = listOf(AirPlayIcon(24, 32, byteArrayOf(0, -1))), rightHandDrive = bits and 8 != 0)
            val expected = KotlinAirPlayInfoPlist.build(config)
            val actual = AirPlayInfoPlist.build(config)
            equal(expected, actual)
            assertEquals(KotlinAirPlayInfoPlist.features(config), AirPlayInfoPlist.features(config))
            assertEquals(config.mainBufferedAudio && !config.disableAudioOutput, config.bufferedAudioOutputEnabled)
            assertArrayEquals(BplistCodec.encodeFallback(expected), BplistCodec.encode(actual))
        }
        for (count in listOf(-1, 0, 1, 2, 4)) for (index in -1..5) {
            equal(KotlinAirPlayInfoPlist.viewAreaCommand(index, count), AirPlayInfoPlist.viewAreaCommand(index, count))
        }
    }

    fun videoBodies() {
        for (bits in listOf(0L, 0x615653aee2L, Long.MAX_VALUE, Long.MIN_VALUE, -1L, -128L, -129L)) {
            assertEquals(KotlinVideoInCar.featuresEx(bits), VideoInCar.featuresEx(bits))
            for (allowed in listOf(false, true)) equal(KotlinVideoInCar.info(bits, allowed), VideoInCar.info(bits, allowed))
        }
        val states = listOf<VideoInCar.PlayerState?>(null,
            VideoInCar.PlayerState(false, true, 0.0, 0.0, 0.0),
            VideoInCar.PlayerState(true, false, -0.0005, 123.5, Double.POSITIVE_INFINITY),
            VideoInCar.PlayerState(true, true, Double.NaN, Double.NEGATIVE_INFINITY, -1.5))
        for (state in states) for (id in listOf(null, 1, -1L, 1.25f, "id\u0000\ud800")) {
            val old = state?.let { KotlinVideoInCar.PlayerState(it.prepared, it.playing, it.positionSeconds, it.durationSeconds, it.bufferedSeconds) }
            equal(KotlinVideoInCar.playbackInfoResponse(id, id, old), VideoInCar.playbackInfoResponse(id, id, state))
            equal(KotlinVideoInCar.seekResponse(id), VideoInCar.seekResponse(id))
            equal(KotlinVideoInCar.errorNotification(id, -12927), VideoInCar.errorNotification(id, -12927))
            for (playing in listOf(false, true)) equal(KotlinVideoInCar.playbackStateNotification(playing, id), VideoInCar.playbackStateNotification(playing, id))
            for (key in listOf(null, "hasEnabledAudio", "muted", "seekableTimeRanges", "loadedTimeRanges", "unknown\ud800")) {
                equal(KotlinVideoInCar.propertyResponse(id, key, old), VideoInCar.propertyResponse(id, key, state))
            }
        }
        for (seconds in listOf(-0.0005, -0.0015, -0.0, 0.0005, 61.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            equal(KotlinVideoInCar.cmTime(seconds), VideoInCar.cmTime(seconds))
            for (scale in listOf(0, -1, 1, 1000, Double.NaN, Double.POSITIVE_INFINITY)) {
                val message = mapOf("time" to mapOf("value" to seconds, "timescale" to scale))
                assertEquals(KotlinVideoInCar.seekMillis(message), VideoInCar.seekMillis(message))
            }
        }
        val items = listOf<Map<String, Any?>>(
            emptyMap(), mapOf("item" to "wrong"), mapOf("item" to mapOf("uuid" to "x")),
            mapOf("item" to mapOf("Content-Location" to "file:///x")),
            mapOf("item" to mapOf("Content-Location" to "mainm3u8://x", "uuid" to 7, "Start-Position-Seconds" to 1.25)),
            mapOf("item" to mapOf("Content-Location" to "https://x\ud800", "Start-Date" to Date(1234),
                "uuid" to listOf(1, null, 2L), "Start-Position" to mapOf("value" to 48962676084L, "timescale" to 1000000000L))),
            mapOf("item" to mapOf("Content-Location" to "http://x", "Start-Position-Seconds" to Double.NaN)))
        for (message in items) for (app in listOf(false, true)) {
            val expected = KotlinVideoInCar.parseItem(message, app)
            val actual = VideoInCar.parseItem(message, app)
            equal(expected?.let { listOf(it.uuid, it.url, it.startMillis) }, actual?.let { listOf(it.uuid, it.url, it.startMillis) })
        }
    }

    fun availability() {
        var seed = 0x12345678
        val oldSent = mutableListOf<Boolean>()
        val newSent = mutableListOf<Boolean>()
        var writable = true
        val expected = KotlinVideoPlaybackAvailability { oldSent += it; writable }
        val actual = VideoPlaybackAvailability { newSent += it; writable }
        repeat(2000) {
            seed = seed * 1664525 + 1013904223
            writable = seed and 8 != 0
            val value = seed and 16 != 0
            val old = when ((seed ushr 24) % 3) { 0 -> expected.setDesired(value); 1 -> expected.setFeatureEnabled(value); else -> expected.setEventReady(value) }
            val new = when ((seed ushr 24) % 3) { 0 -> actual.setDesired(value); 1 -> actual.setFeatureEnabled(value); else -> actual.setEventReady(value) }
            assertEquals(old, new)
            assertEquals(oldSent, newSent)
        }
        // A platform send may synchronously update the desired value; preserve outer commit ordering.
        val oldEvents = mutableListOf<Boolean>()
        val newEvents = mutableListOf<Boolean>()
        lateinit var old: KotlinVideoPlaybackAvailability
        lateinit var new: VideoPlaybackAvailability
        old = KotlinVideoPlaybackAvailability { value -> oldEvents += value; if (value) old.setDesired(false); true }
        new = VideoPlaybackAvailability { value -> newEvents += value; if (value) new.setDesired(false); true }
        old.setFeatureEnabled(true); new.setFeatureEnabled(true)
        old.setEventReady(true); new.setEventReady(true)
        assertEquals(old.setDesired(true), new.setDesired(true))
        assertEquals(old.setDesired(false), new.setDesired(false))
        assertEquals(oldEvents, newEvents)
    }

    private fun equal(expected: Any?, actual: Any?) {
        when (expected) {
            is ByteArray -> assertArrayEquals(expected, actual as ByteArray)
            is Map<*, *> -> {
                actual as Map<*, *>
                assertEquals(expected.keys.toList(), actual.keys.toList())
                expected.forEach { (key, value) -> equal(value, actual[key]) }
            }
            is List<*> -> {
                actual as List<*>
                assertEquals(expected.size, actual.size)
                expected.indices.forEach { equal(expected[it], actual[it]) }
            }
            else -> { assertEquals(expected?.javaClass, actual?.javaClass); assertEquals(expected, actual) }
        }
    }
}
