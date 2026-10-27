package com.shilapi.xcertplay.hud

import android.content.Context
import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.ecarx.xui.adaptapi.diminteraction.FixtureLog
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.media.CarPlayPlaybackStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

/** Controls scheduling only; frames still pass through the production parser, bridge and DIM API. */
internal class EcarxMediaFlowFixture(val context: Context) : AutoCloseable {
    val phone = CarPlayPlaybackStatus()
    val reports = mutableListOf<String>()
    private val broker = EcarxMediaMemorySocket()

    init {
        resetBridge()
        DimInteraction.reset()
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit().clear().commit()
        EcarxOutputSettings.setEnabled(context, false)
        EcarxOutputSettings.setMediaEnabled(context, true)
        resetProjection()
        EcarxMediaBridge.attachReport { reports += it }
    }

    fun initialize() = NavigationOutputs.onAppOpened(context)

    fun update(block: Iap2BodyBuilder.() -> Unit) {
        phone.acceptUpdate(Iap2Messages.buildRaw(CarPlayPlaybackStatus.NOW_PLAYING_UPDATE, block))
            ?.let(NavigationOutputs::onNowPlaying)
    }

    fun song(title: String = "Dreams", playing: Boolean = true, elapsed: Long = 12_500L) = update {
        group(0) {
            string(1, title)
            u32(4, 257_000L)
            string(6, "Rumours")
            string(12, "Fleetwood Mac")
        }
        group(1) {
            u8(0, if (playing) 1 else 2)
            u32(1, elapsed)
            string(7, "com.apple.Music")
        }
    }

    fun disconnect() {
        phone.clearAll()?.let(NavigationOutputs::onNowPlaying)
    }

    fun tick() = invoke(EcarxMediaBridge, "tick")

    fun calls(method: String) = FixtureLog.calls().filter { it.method == method }
    fun methods() = FixtureLog.calls().map { it.method }.filter { it.startsWith("update") }
    fun lastTrack() = calls("updatePlaybackInfo").last().payload

    fun bridgeField(name: String, value: Any?) = set(EcarxMediaBridge, name, value)

    // queued=true holds the real projection worker at its scheduling gate. flush is the actual
    // production drain, with a memory socket providing PUBACKs; no real broker is contacted.
    fun flushProjection(): List<Pair<String, String>> {
        invoke(EcarxProjectionChannel, "flush")
        set(EcarxProjectionChannel, "queued", true)
        return broker.publications()
    }

    override fun close() {
        resetBridge()
        resetProjection()
        set(EcarxProjectionChannel, "socket", null)
        set(EcarxProjectionChannel, "queued", false)
        set(EcarxMediaBridge, "tickerStarted", false)
        set(EcarxMediaBridge, "reportSink", null)
        set(EcarxProjectionChannel, "reportSink", null)
        DimInteraction.reset()
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun resetBridge() {
        for (field in listOf("context", "dim", "media", "trackInterface", "itemInterface", "wanted", "pushed")) {
            set(EcarxMediaBridge, field, null)
        }
        for (field in listOf("available", "sourceTypeSent", "sourceListSent")) set(EcarxMediaBridge, field, false)
        set(EcarxMediaBridge, "tickerStarted", true)
        set(EcarxMediaBridge, "secondsSinceReport", 0)
        set(EcarxMediaBridge, "secondsSincePush", 0)
        set(EcarxMediaBridge, "lastProgress", -1L)
        set(EcarxMediaBridge, "progressMeasuredAt", 0L)
    }

    private fun resetProjection() {
        for (field in listOf("navigationWanted", "mediaWanted", "touched")) set(EcarxProjectionChannel, field, false)
        set(EcarxProjectionChannel, "published", null)
        set(EcarxProjectionChannel, "queued", true)
        set(EcarxProjectionChannel, "packetId", 0)
        set(EcarxProjectionChannel, "socket", broker)
    }

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invoke(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }
}

/** A transport fixture only: the production channel writes and reads the MQTT packets. */
private class EcarxMediaMemorySocket : Socket() {
    private val output = ByteArrayOutputStream()
    private val input = ByteArrayInputStream((1..100).flatMap { listOf(0x40, 2, 0, it) }.map(Int::toByte).toByteArray())
    override fun getOutputStream() = output
    override fun getInputStream() = input

    fun publications(): List<Pair<String, String>> {
        val bytes = ByteArrayInputStream(output.toByteArray())
        val frames = mutableListOf<Pair<String, String>>()
        while (bytes.available() > 0) {
            check(bytes.read() == 0x32) { "Expected QoS 1 MQTT PUBLISH" }
            var size = 0
            var multiplier = 1
            do {
                val digit = bytes.read()
                check(digit >= 0)
                size += (digit and 0x7f) * multiplier
                multiplier *= 128
            } while (digit and 0x80 != 0)
            val body = ByteArray(size)
            check(bytes.read(body) == size)
            val topicSize = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
            frames += String(body, 2, topicSize, Charsets.UTF_8) to
                String(body, 4 + topicSize, size - 4 - topicSize, Charsets.UTF_8)
        }
        return frames
    }
}
