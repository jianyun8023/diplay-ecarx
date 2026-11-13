@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.iap2.wire

import com.shihab.diplay.iap2.wire.reference.KotlinIap2CsmFramer
import com.shihab.diplay.iap2.wire.reference.KotlinIap2WireCodec
import java.io.ByteArrayOutputStream

/** Independent pre-migration algorithm; included only by unit/instrumentation test source sets. */
internal fun Iap2ParameterList.encodeKotlin(): ByteArray {
    val encoded = ByteArrayOutputStream()
    for (parameter in asList()) {
        val next = KotlinIap2CsmFramer.encodeParam(parameter.id, parameter.payload)
        require(next.size <= Iap2CsmFramer.MAX_BODY_BYTES - encoded.size()) {
            "Encoded CSM parameters exceed the ${Iap2CsmFramer.MAX_BODY_BYTES}-byte message body limit"
        }
        encoded.write(next)
    }
    return encoded.toByteArray()
}

internal fun Iap2ParameterList.Companion.parseKotlin(encoded: ByteArray): Iap2ParameterList {
    val parameters = ArrayList<Iap2Parameter>()
    var offset = 0
    while (offset < encoded.size) {
        if (encoded.size - offset < Iap2Parameter.HEADER_BYTES) {
            throw Iap2ProtocolException("Truncated CSM parameter header")
        }
        val length = KotlinIap2WireCodec.readU16(encoded, offset)
        if (length < Iap2Parameter.HEADER_BYTES || length > encoded.size - offset) {
            throw Iap2ProtocolException("Invalid CSM parameter length $length")
        }
        parameters += Iap2Parameter(
            id = KotlinIap2WireCodec.readU16(encoded, offset + 2),
            payload = encoded.copyOfRange(offset + Iap2Parameter.HEADER_BYTES, offset + length),
        )
        offset += length
    }
    return Iap2ParameterList.of(parameters)
}
