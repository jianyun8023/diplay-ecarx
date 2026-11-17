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

    sealed interface SetupPlan {
        class Streams(val entries: List<*>) : SetupPlan
        data object Peer : SetupPlan
    }
    class RemoteCommandPlan(val streamId: Long, val data: ByteArray)

    fun setupPlan(body: Map<String, Any?>): SetupPlan =
        setupStreams(body)?.let { SetupPlan.Streams(it) } ?: SetupPlan.Peer

    fun remoteCommandPlan(header: String?, params: Map<String, Any?>): RemoteCommandPlan? {
        val values = commandEnvelope(header, params) ?: return null
        return RemoteCommandPlan(values[0] as Long, values[1] as ByteArray)
    }

    @JvmStatic private external fun setupStreams(body: Map<String, Any?>): List<*>?
    @JvmStatic private external fun commandEnvelope(header: String?, params: Map<String, Any?>): Array<Any?>?

    /**
     * SETUP interpretation and reply layout behind `protocols::airplay::setup`. [audioSetupPlan]
     * reads the stream fields, derives the DataStream keys and plans the microphone; a null
     * output key aborts the SETUP. [setupReply] builds the reply dict in the protocol's field
     * order with the unsigned connection representation.
     */
    const val REPLY_AUDIO = 0
    const val REPLY_REMOTE_CONTROL = 1
    const val REPLY_DATA = 2
    private const val REPLY_BUFFERED = 3

    enum class BufferedSetupFailure { NONE, KEY, FORMAT, PARAMETERS }
    class BufferedSetupPlan(val failure: BufferedSetupFailure, val key: ByteArray?, val formatBits: Long)

    fun bufferedSetupPlan(stream: Map<String, Any?>): BufferedSetupPlan {
        val plan = bufferedSetup(stream)
        return BufferedSetupPlan(BufferedSetupFailure.entries[plan["reason"] as Int],
            plan["key"] as ByteArray?, plan["formatBits"] as Long)
    }

    fun bufferedSetupReply(port: Int): Map<String, Any?> =
        checkNotNull(setupReply(REPLY_BUFFERED, 103, 0L, port, 0, null))

    class AudioSetupPlan(
        val outputKey: ByteArray?,
        val formatBits: Long,
        val micPort: Int,
        val latencyMs: Int,
        val microphone: MicrophonePlan?,
    )

    class MicrophonePlan(
        val inputKey: ByteArray?,
        val port: Int,
        val frameMillis: Int,
        val bitrate: Int?,
        val opusClockRate: Int,
        val waitDownlink: Boolean,
    )

    class DataStreamSetupPlan(val controlType: Int, val seed: String?, val connectionIdDecimal: String?)

    fun audioSetupPlan(
        type: Int,
        audioType: String,
        stream: Map<String, Any?>,
        sharedSecret: ByteArray?,
        microphoneEnabled: Boolean,
    ): AudioSetupPlan? {
        val plan = audioSetup(type, audioType, stream, sharedSecret, microphoneEnabled) ?: return null
        val mic = plan["mic"] as Map<*, *>?
        return AudioSetupPlan(
            outputKey = plan["outputKey"] as ByteArray?,
            formatBits = plan["formatBits"] as Long,
            micPort = plan["micPort"] as Int,
            latencyMs = plan["latencyMs"] as Int,
            microphone = mic?.let {
                MicrophonePlan(
                    inputKey = it["inputKey"] as ByteArray?,
                    port = it["port"] as Int,
                    frameMillis = it["frameMillis"] as Int,
                    bitrate = it["bitrate"] as Int?,
                    opusClockRate = it["opusClockRate"] as Int,
                    waitDownlink = it["waitDownlink"] as Boolean,
                )
            },
        )
    }

    fun dataStreamSetupPlan(stream: Map<String, Any?>): DataStreamSetupPlan? {
        val plan = dataStreamSetup(stream) ?: return null
        return DataStreamSetupPlan(
            controlType = plan["controlType"] as Int,
            seed = plan["seed"] as String?,
            connectionIdDecimal = plan["connectionIdDecimal"] as String?,
        )
    }

    // Keep the JNI wire maps private; only this boundary interprets their field layout.
    @JvmStatic private external fun audioSetup(
        type: Int,
        audioType: String,
        stream: Map<String, Any?>,
        sharedSecret: ByteArray?,
        microphoneEnabled: Boolean,
    ): Map<String, Any?>?
    @JvmStatic private external fun bufferedSetup(stream: Map<String, Any?>): Map<String, Any?>
    @JvmStatic private external fun dataStreamSetup(stream: Map<String, Any?>): Map<String, Any?>?
    @JvmStatic external fun dataStreamKey(sharedSecret: ByteArray, seed: String, label: String): ByteArray?
    @JvmStatic external fun setupReply(
        operation: Int,
        type: Int,
        streamId: Long,
        dataPort: Int,
        controlPort: Int,
        connectionId: Any?,
    ): Map<String, Any?>?
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
