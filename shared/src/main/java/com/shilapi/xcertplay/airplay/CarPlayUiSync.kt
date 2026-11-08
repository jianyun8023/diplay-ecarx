package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** OPACK envelopes used by UI Sync and the nested vehicle-state protocol. */
internal object CarPlayOpack {
    fun decode(bytes: ByteArray): Map<Any, Any?> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val seen = mutableListOf<Any?>()
        fun unsigned(width: Int): Long {
            require(width in 1..8 && input.remaining() >= width)
            var result = 0L
            repeat(width) { result = result or ((input.get().toLong() and 255) shl (it * 8)) }
            return result
        }
        fun value(depth: Int = 0): Any? {
            require(depth < 32 && input.hasRemaining()) { "invalid OPACK nesting or truncated value" }
            val tag = input.get().toInt() and 255
            if (tag in 0xa0..0xc4) {
                val index = if (tag <= 0xc0) (tag - 0xa0).toLong() else unsigned(tag - 0xc0)
                require(index in 0 until seen.size.toLong()) { "OPACK reference $index" }
                return seen[index.toInt()]
            }
            if (tag == 1) return true
            if (tag == 2) return false
            if (tag == 4) return null
            if (tag in 8..0x2f) return (tag - 8).toLong()
            if (tag in 0xd0..0xef) {
                val dictionary = tag >= 0xe0
                val count = tag and 15
                val array = mutableListOf<Any?>()
                val map = linkedMapOf<Any, Any?>()
                var n = 0
                while (if (count == 15) {
                    require(input.hasRemaining()) { "unterminated OPACK container" }
                    input.get(input.position()).toInt() != 3
                } else n < count) {
                    val item = value(depth + 1)
                    if (dictionary) {
                        require(item is String || item is Long) { "unsupported OPACK key" }
                        require(!map.containsKey(item)) { "duplicate OPACK key" }
                        map[item] = value(depth + 1)
                    } else array += item
                    n++
                }
                if (count == 15) input.get()
                return if (dictionary) map else array
            }
            val decoded: Any = when (tag) {
                5 -> UUID(input.order(ByteOrder.BIG_ENDIAN).long, input.long).also { input.order(ByteOrder.LITTLE_ENDIAN) }
                in 0x30..0x33 -> {
                    val width = 1 shl (tag - 0x30)
                    val shift = 64 - width * 8
                    (unsigned(width) shl shift) shr shift
                }
                0x35 -> input.float.toDouble()
                0x36 -> input.double
                in 0x40..0x64, in 0x70..0x94 -> {
                    val base = if (tag < 0x70) 0x40 else 0x70
                    val length = if (tag - base <= 32) (tag - base).toLong() else unsigned(tag - base - 32)
                    require(length in 0..input.remaining().toLong()) { "OPACK length $length" }
                    val data = ByteArray(length.toInt()).also(input::get)
                    if (base == 0x40) data.toString(Charsets.UTF_8) else data
                }
                else -> error("unsupported OPACK tag 0x${tag.toString(16)}")
            }
            seen += decoded
            return decoded
        }
        val result = value() as? Map<*, *> ?: error("OPACK envelope must be a dictionary")
        require(!input.hasRemaining()) { "trailing OPACK data" }
        @Suppress("UNCHECKED_CAST")
        return result as Map<Any, Any?>
    }

    fun encode(fields: Map<*, *>): ByteArray {
        val output = ByteArrayOutputStream()
        fun blob(base: Int, bytes: ByteArray) {
            if (bytes.size <= 32) output.write(base + bytes.size) else {
                // iOS UI Sync did not finish reset with an unnecessarily wide length encoding.
                val width = when { bytes.size <= 255 -> 1; bytes.size <= 65535 -> 2; bytes.size <= 16777215 -> 3; else -> 4 }
                output.write(base + 32 + width)
                repeat(width) { output.write(bytes.size ushr (8 * it)) }
            }
            output.write(bytes)
        }
        fun value(v: Any?) {
            when (v) {
                null -> output.write(4)
                is Boolean -> output.write(if (v) 1 else 2)
                is String -> blob(0x40, v.toByteArray(Charsets.UTF_8))
                is ByteArray -> blob(0x70, v)
                is Float, is Double -> {
                    val n = (v as Number).toDouble(); require(n.isFinite())
                    output.write(0x36); output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(n).array())
                }
                is Number -> {
                    val n = v.toLong()
                    if (n in 0..39) output.write(8 + n.toInt()) else {
                        val width = when (n) { in -128..127 -> 1; in -32768..32767 -> 2; in Int.MIN_VALUE..Int.MAX_VALUE -> 4; else -> 8 }
                        output.write(0x30 + Integer.numberOfTrailingZeros(width))
                        repeat(width) { output.write((n ushr (8 * it)).toInt()) }
                    }
                }
                is UUID -> {
                    output.write(5); output.write(ByteBuffer.allocate(16).putLong(v.mostSignificantBits).putLong(v.leastSignificantBits).array())
                }
                is Map<*, *> -> {
                    output.write(0xe0 + minOf(v.size, 15)); v.forEach { (k, item) -> value(k); value(item) }
                    if (v.size >= 15) output.write(3)
                }
                is List<*> -> {
                    output.write(0xd0 + minOf(v.size, 15)); v.forEach(::value)
                    if (v.size >= 15) output.write(3)
                }
                else -> error("unsupported OPACK value")
            }
        }
        value(fields)
        return output.toByteArray()
    }
}

/** shortcut: fixed bench layout; process region changes before supporting other dashboard layouts. */
internal class CarPlayUiSync(
    private val panel: String,
    private val layout: CarPlayUltraExperiment.Layout,
    private val log: (String) -> Unit,
) {
    private var session = 0L
    private var sequence = 1L
    private var acknowledged = 0L
    private var ready = false
    private var waiting = false
    private var identity: Any? = null
    private var version = 2L

    fun handle(bytes: ByteArray): List<ByteArray> {
        val envelope = CarPlayOpack.decode(bytes)
        require(envelope["displayUUID"] == panel) { "unexpected UI Sync panel ${envelope["displayUUID"]}" }
        val header = BplistCodec.decode(envelope["data"] as? ByteArray ?: error("UI Sync data missing")) as? Map<*, *>
            ?: error("UI Sync header must be a dictionary")
        val type = (header["type"] as? Number)?.toInt() ?: error("UI Sync type missing")
        if (type <= 1) log("UI Sync rx header=${header.filterKeys { it != "data" }} envelope=${envelope.keys}")
        val replies = mutableListOf<ByteArray>()
        fun send(type: Int, command: Map<String, Any?>? = null, extra: Map<String, Any?> = emptyMap()) {
            val response = linkedMapOf<String, Any?>("type" to type, "ssn" to session,
                "psn" to sequence++, "asn" to acknowledged)
            response.putAll(extra)
            if (command != null) response["data"] = BplistCodec.encode(command)
            replies += CarPlayOpack.encode(envelope + ("data" to BplistCodec.encode(response)))
            if (type != 3) log("UI Sync tx header=${response.filterKeys { it != "data" }} command=${command?.get("type")}")
        }
        fun command(type: Int, data: Map<String, Any?>) = send(2, mapOf("type" to type, "data" to data))
        fun focus() = command(7, mapOf("tokenID" to 4711))
        if (type == 0) {
            if (waiting) return emptyList()
            session++
            sequence = 1
            acknowledged = 0
            ready = false
            waiting = true
            identity = header["id"]
            send(0, extra = mapOf("version" to 2) + (identity?.let { mapOf("id" to it) } ?: emptyMap()))
        } else if ((header["ssn"] as? Number)?.toLong() == session) {
            acknowledged = (header["psn"] as? Number)?.toLong() ?: acknowledged
            when (type) {
                1 -> if (waiting) {
                    waiting = false
                    ready = true
                    version = ((header["version"] as? Number)?.toLong() ?: 1L).coerceAtMost(2)
                    send(1, extra = mapOf("version" to version) + (identity?.takeIf { version >= 2 }?.let { mapOf("id" to it) } ?: emptyMap()))
                    command(0x11, mapOf("allow" to true))
                    command(5, mapOf("layoutID" to layout.id, "paletteID" to layout.palette,
                        "appearanceMode" to 1, "fadeInNewLayout" to true, "fadeOutOldLayout" to true,
                        "activeComponents" to layout.activeComponents))
                    log("UI Sync requested layout=${layout.id} palette=${layout.palette} components=${layout.activeComponents.size}")
                    focus()
                }
                2 -> if (ready) {
                    val data = header["data"] as? ByteArray
                    val incoming = data?.let(BplistCodec::decode) as? Map<*, *>
                    if ((incoming?.get("type") as? Number)?.toInt() != 6) log("UI Sync phone command=$incoming")
                    if (header["noAck"] != true) send(3)
                    if ((incoming?.get("type") as? Number)?.toInt() == 8) focus()
                }
            }
        }
        return replies
    }

    companion object {
        const val UUID = "07D9F906-8D64-4B54-A808-A20BCA2C51C2"
    }
}
