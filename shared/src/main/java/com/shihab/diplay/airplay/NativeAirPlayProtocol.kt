package com.shihab.diplay.airplay

/** Platform DTOs cross this boundary once; Rust owns protocol declarations and control bodies. */
internal object NativeAirPlayProtocol {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun info(facts: Map<String, Any?>): Map<String, Any?>
    @JvmStatic external fun features(disableAudio: Boolean): Long
    @JvmStatic external fun bufferedEnabled(buffered: Boolean, disableAudio: Boolean): Boolean
    @JvmStatic external fun viewArea(index: Int, count: Int): Map<String, Any?>
    @JvmStatic external fun video(operation: Int, arguments: List<Any?>): Any?
    @JvmStatic external fun control(operation: Int, arguments: List<Any?>): Any?
    @JvmStatic external fun bonjour(operation: Int, arguments: List<Any?>): Any?
    @JvmStatic external fun probeCreate(): Long
    @JvmStatic external fun probeRetryCreate(): LongArray
    @JvmStatic external fun probeRetry(token: LongArray, event: Int, closed: Boolean): Long
    @JvmStatic external fun probeFeed(handle: Long, byte: Int): List<ByteArray?>?
    @JvmStatic external fun probeDestroy(handle: Long)
    @JvmStatic external fun availabilityCreate(): LongArray
    @JvmStatic external fun availability(token: LongArray, event: Int, value: Boolean): Int
    @JvmStatic external fun cornerMask(value: Any?, depth: Int): ByteArray?
}

internal object AirPlayCommands {
    fun videoAllowed(allowed: Boolean) = command(0, allowed)
    fun stopUi(uuid: String) = command(1, uuid)
    fun showUi(uuid: String, url: String) = command(2, uuid, url)
    fun keyFrame(uuid: String? = null) = command(3, uuid)
    fun zoom(zoomIn: Boolean) = command(4, zoomIn)
    fun siri(action: Int) = command(5, action)
    fun navigationExit(claim: Boolean) = command(6, claim)
    fun iap(data: ByteArray) = command(7, data)
    fun night(night: Boolean) = command(8, night)
    fun hid(uid: Int, report: ByteArray) = command(9, uid, report)
    fun remoteControl(data: ByteArray) = command(10, data)
    fun setup(timingPort: Int, eventPort: Int, keepAlivePort: Int?, features: List<String>) =
        command(11, timingPort, eventPort, keepAlivePort, features)
    fun needsKeepAlive(value: Any?): Boolean = NativeAirPlayProtocol.control(12, listOf(value)) as Boolean
    fun screen(kind: Int, port: Int) = command(13, kind, port)
    fun streams(streams: List<Any?>) = command(14, streams)
    data class Incoming(val noisy: Boolean, val duck: Boolean?, val ui: Int, val navigationEntity: Int?)
    fun incoming(type: String, params: Map<String, Any?>): Incoming {
        val values = NativeAirPlayProtocol.control(15, listOf(type, params)) as List<*>
        return Incoming(values[0] as Boolean, values[1] as Boolean?, values[2] as Int, values[3] as Int?)
    }
    fun hidDevice(uid: Int, name: String, descriptor: ByteArray, displayUuid: String) =
        command(16, uid, name, descriptor, displayUuid)
    fun eventHeader(sequence: Int, bodySize: Int, streamId: Long?): String =
        NativeAirPlayProtocol.control(17, listOf(sequence, bodySize, streamId)) as String

    @Suppress("UNCHECKED_CAST")
    private fun command(operation: Int, vararg arguments: Any?): Map<String, Any?> =
        NativeAirPlayProtocol.control(operation, arguments.toList()) as Map<String, Any?>
}
