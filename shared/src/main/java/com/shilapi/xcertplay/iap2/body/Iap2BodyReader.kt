package com.shilapi.xcertplay.iap2.body

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import com.shilapi.xcertplay.iap2.wire.NativeIap2Wire

/**
 * Typed reader over an ordered CSM body.
 *
 * The reader does not discard unknown or repeated parameters. Callers can use the scalar helpers
 * for known fields and [raw] or [all] for forward-compatible values.
 */
class Iap2BodyReader private constructor(
    private val parameters: Iap2ParameterList,
) {
    val size: Int get() = parameters.size

    fun isEmpty(): Boolean = parameters.isEmpty()

    fun has(id: Int): Boolean = parameters.has(id)

    fun first(id: Int): Iap2Parameter? = parameters.first(id)

    fun require(id: Int, description: String = defaultDescription(id)): Iap2Parameter =
        parameters.require(id, description)

    fun all(id: Int): List<Iap2Parameter> = parameters.all(id)

    fun raw(id: Int): ByteArray = require(id).payload

    fun optionalRaw(id: Int): ByteArray? = first(id)?.payload

    fun void(id: Int): Boolean {
        val parameter = require(id)
        NativeIap2Wire.requireVoid(id, parameter.payload)
        return true
    }

    fun optionalVoid(id: Int): Boolean = first(id)?.let {
        NativeIap2Wire.requireVoid(id, it.payload)
        true
    } ?: false

    fun u8(id: Int): Int = scalar(id, NativeIap2Wire.U8).toInt()
    fun optionalU8(id: Int): Int? = optionalScalar(id, NativeIap2Wire.U8)?.toInt()

    fun i8(id: Int): Int = scalar(id, NativeIap2Wire.I8).toInt()
    fun optionalI8(id: Int): Int? = optionalScalar(id, NativeIap2Wire.I8)?.toInt()

    fun u16(id: Int): Int = scalar(id, NativeIap2Wire.U16).toInt()
    fun optionalU16(id: Int): Int? = optionalScalar(id, NativeIap2Wire.U16)?.toInt()

    fun i16(id: Int): Int = scalar(id, NativeIap2Wire.I16).toInt()
    fun optionalI16(id: Int): Int? = optionalScalar(id, NativeIap2Wire.I16)?.toInt()

    fun u32(id: Int): Long = scalar(id, NativeIap2Wire.U32)
    fun optionalU32(id: Int): Long? = optionalScalar(id, NativeIap2Wire.U32)

    fun i32(id: Int): Int = scalar(id, NativeIap2Wire.I32).toInt()
    fun optionalI32(id: Int): Int? = optionalScalar(id, NativeIap2Wire.I32)?.toInt()

    fun u64(id: Int): Long = scalar(id, NativeIap2Wire.U64)
    fun optionalU64(id: Int): Long? = optionalScalar(id, NativeIap2Wire.U64)

    fun bool(id: Int): Boolean = scalar(id, NativeIap2Wire.BOOL_NONZERO) != 0L
    fun optionalBool(id: Int): Boolean? = optionalScalar(id, NativeIap2Wire.BOOL_NONZERO)?.let { it != 0L }

    fun bytes(id: Int): ByteArray = raw(id)

    fun optionalBytes(id: Int): ByteArray? = optionalRaw(id)

    fun string(id: Int): String = try {
        Iap2WireCodec.readString(raw(id))
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalString(id: Int): String? = first(id)?.let {
        try {
            Iap2WireCodec.readString(it.payload)
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun strings(id: Int): List<String> = try {
        Iap2WireCodec.readStrings(raw(id))
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalStrings(id: Int): List<String>? = first(id)?.let {
        try {
            Iap2WireCodec.readStrings(it.payload)
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun u16List(id: Int): List<Int> = try {
        Iap2WireCodec.readU16List(raw(id))
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalU16List(id: Int): List<Int>? = first(id)?.let {
        try {
            Iap2WireCodec.readU16List(it.payload)
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun group(id: Int): Iap2BodyReader =
        Iap2BodyReader(Iap2ParameterList.parse(raw(id)))

    fun optionalGroup(id: Int): Iap2BodyReader? = optionalRaw(id)?.let {
        Iap2BodyReader(Iap2ParameterList.parse(it))
    }

    fun list(): List<Iap2Parameter> = parameters.asList()

    private fun scalar(id: Int, kind: Int): Long =
        NativeIap2Wire.readScalar(kind, require(id).payload, 0, id)

    private fun optionalScalar(id: Int, kind: Int): Long? = first(id)?.let {
        NativeIap2Wire.readScalar(kind, it.payload, 0, id)
    }

    private fun malformed(id: Int, failure: Iap2ProtocolException): Iap2ProtocolException =
        Iap2ProtocolException("Malformed parameter 0x${hex(id)}", failure)

    private fun defaultDescription(id: Int): String = "parameter 0x${hex(id)}"

    private fun hex(id: Int): String = id.toString(16).padStart(4, '0')

    companion object {
        fun of(frame: Iap2Frame): Iap2BodyReader =
            Iap2BodyReader(Iap2ParameterList.parse(frame.payload))

        fun of(body: Iap2ParameterList): Iap2BodyReader = Iap2BodyReader(body)

        /** Validates the endpoint name without requiring a caller-side cast or map lookup. */
        fun of(endpoint: Iap2Endpoint, frame: Iap2Frame): Iap2BodyReader {
            require(frame.messageId == endpoint.id) {
                "Frame 0x${frame.messageId.toString(16)} is not ${endpoint.name}"
            }
            return of(frame)
        }
    }
}
