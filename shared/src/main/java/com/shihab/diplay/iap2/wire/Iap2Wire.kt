package com.shihab.diplay.iap2.wire

import com.shihab.diplay.airplay.NativeMedia

import java.io.IOException

/** An iAP2 CSM body parameter: `u16 length | u16 parameterId | payload`. */
class Iap2Parameter(id: Int, payload: ByteArray) {
    val id: Int
    private val bytes: ByteArray

    init {
        require(id in 0..0xffff) { "CSM parameter id must fit in u16" }
        require(payload.size <= Iap2CsmFramer.MAX_PARAM_BYTES - HEADER_BYTES) {
            "CSM parameter payload exceeds ${Iap2CsmFramer.MAX_PARAM_BYTES - HEADER_BYTES} bytes"
        }
        this.id = id
        bytes = payload.copyOf()
    }

    /** A defensive copy of the parameter payload, excluding its header. */
    val payload: ByteArray get() = bytes.copyOf()

    internal fun payloadView(): ByteArray = bytes

    override fun equals(other: Any?): Boolean =
        other is Iap2Parameter && id == other.id && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * id + bytes.contentHashCode()

    override fun toString(): String =
        "Iap2Parameter(id=0x${id.toString(16).padStart(4, '0')}, payload=${bytes.size} bytes)"

    companion object {
        const val HEADER_BYTES = 4
    }
}

/**
 * An ordered CSM parameter collection.
 *
 * A list is intentional: CSM permits repeated parameter IDs and preserves wire order. Callers
 * should use [first] or [all] rather than converting the body to a map.
 */
class Iap2ParameterList private constructor(
    private val parameters: List<Iap2Parameter>,
) {
    val size: Int get() = parameters.size

    fun isEmpty(): Boolean = parameters.isEmpty()

    fun asList(): List<Iap2Parameter> = parameters.toList()

    operator fun get(index: Int): Iap2Parameter = parameters[index]

    fun has(id: Int): Boolean = parameters.any { it.id == id }

    fun first(id: Int): Iap2Parameter? = parameters.firstOrNull { it.id == id }

    fun require(id: Int, description: String = "parameter 0x${id.toString(16).padStart(4, '0')}"): Iap2Parameter =
        first(id) ?: throw Iap2ProtocolException("Missing required $description")

    fun all(id: Int): List<Iap2Parameter> = parameters.filter { it.id == id }

    fun encode(): ByteArray = NativeIap2Body.encode(
        parameters.map { it.id }.toIntArray(),
        parameters.map { it.payloadView() }.toTypedArray(),
    )

    override fun equals(other: Any?): Boolean =
        other is Iap2ParameterList && parameters == other.parameters

    override fun hashCode(): Int = parameters.hashCode()

    companion object {
        val EMPTY: Iap2ParameterList = Iap2ParameterList(emptyList())

        fun of(parameters: Iterable<Iap2Parameter>): Iap2ParameterList =
            Iap2ParameterList(parameters.toList())

        fun of(vararg parameters: Iap2Parameter): Iap2ParameterList =
            of(parameters.asList())

        /** Parses a complete body and rejects truncated headers, invalid lengths, and trailing bytes. */
        fun parse(encoded: ByteArray): Iap2ParameterList =
            Iap2ParameterList(NativeIap2Body.parse(encoded).toList())
    }
}

/** One decoded iAP2 Control Session Message (CSM). */
class Iap2Frame(messageId: Int, payload: ByteArray) {
    val messageId: Int
    private val body: ByteArray

    init {
        require(messageId in 0..0xffff) { "CSM message id must fit in u16" }
        require(payload.size <= Iap2CsmFramer.MAX_BODY_BYTES) {
            "CSM payload exceeds ${Iap2CsmFramer.MAX_BODY_BYTES} bytes"
        }
        this.messageId = messageId
        body = payload.copyOf()
    }

    /** A defensive copy of the body, excluding the CSM header. */
    val payload: ByteArray get() = body.copyOf()

    fun body(): Iap2ParameterList = Iap2ParameterList.parse(body)

    /** A defensive copy of this frame's complete six-byte-header CSM encoding. */
    fun encodedFrame(): ByteArray = Iap2CsmFramer.encodeFrame(messageId, body)

    override fun equals(other: Any?): Boolean =
        other is Iap2Frame && messageId == other.messageId && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * messageId + body.contentHashCode()

    override fun toString(): String =
        "Iap2Frame(messageId=0x${messageId.toString(16).padStart(4, '0')}, payload=${body.size} bytes)"
}

/** A malformed, incomplete, or otherwise invalid iAP2 CSM value. */
open class Iap2ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Native CSM body parameter framing; order and repeated ids are preserved. */
internal object NativeIap2Body {
    init { check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" } }
    @JvmStatic external fun validateBuilder(ids: IntArray, kinds: IntArray, specs: IntArray): IntArray?
    @JvmStatic external fun encode(ids: IntArray, payloads: Array<ByteArray>): ByteArray
    @JvmStatic external fun parse(encoded: ByteArray): Array<Iap2Parameter>
}
