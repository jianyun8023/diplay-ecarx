package com.shilapi.xcertplay.iap2.wire

import com.shilapi.xcertplay.airplay.NativeMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native CSM body parameter codec must match the Kotlin contract, including malformed input. */
class NativeIap2BodyDeviceTest {
    private fun parameters() = Iap2ParameterList.of(
        Iap2Parameter(10, byteArrayOf(1, 2, 3)),
        Iap2Parameter(7, byteArrayOf()),
        Iap2Parameter(10, byteArrayOf(9)),
    )

    @Test fun encodeKeepsOrderAndRepeatedIdsLikeKotlin() {
        assertTrue(NativeMedia.available)
        assertArrayEquals(parameters().encodeKotlin(), parameters().encode())
    }

    @Test fun parseMatchesKotlinForEveryParameter() {
        assertTrue(NativeMedia.available)
        val encoded = parameters().encodeKotlin()
        assertEquals(Iap2ParameterList.parseKotlin(encoded).asList(), Iap2ParameterList.parse(encoded).asList())
    }

    @Test fun malformedInputThrowsTheSameExceptionAndMessage() {
        assertTrue(NativeMedia.available)
        for (bytes in listOf(byteArrayOf(0, 4, 0), byteArrayOf(0, 3, 0, 1), byteArrayOf(0, 9, 0, 1, 0))) {
            val kotlin = runCatching { Iap2ParameterList.parseKotlin(bytes) }.exceptionOrNull()
            val native = runCatching { Iap2ParameterList.parse(bytes) }.exceptionOrNull()
            assertEquals(kotlin?.javaClass, native?.javaClass)
            assertEquals(kotlin?.message, native?.message)
        }
    }

    @Test fun encodingKeepsTheExactBodyBoundaryAndRejection() {
        assertTrue(NativeMedia.available)
        for (size in listOf(0, Iap2CsmFramer.MAX_BODY_BYTES - 4, Iap2CsmFramer.MAX_BODY_BYTES - 3)) {
            val value = Iap2ParameterList.of(Iap2Parameter(1, ByteArray(size)))
            val kotlin = runCatching { value.encodeKotlin() }
            val native = runCatching { value.encode() }
            if (kotlin.isSuccess) assertArrayEquals(kotlin.getOrThrow(), native.getOrThrow())
            else {
                assertEquals(kotlin.exceptionOrNull()?.javaClass, native.exceptionOrNull()?.javaClass)
                assertEquals(kotlin.exceptionOrNull()?.message, native.exceptionOrNull()?.message)
            }
        }
    }

    @Test fun standaloneParsingKeepsItsOriginalBudgetAndIndependentPayloadCopies() {
        assertTrue(NativeMedia.available)
        val body = ByteArray(65536) { if (it % 4 == 1) 4 else if (it % 4 == 3) 1 else 0 }
        val parsed = Iap2ParameterList.parse(body)
        assertEquals(Iap2ParameterList.parseKotlin(body).asList(), parsed.asList())
        assertTrue(Iap2ParameterList.parse(byteArrayOf()).isEmpty())
        val encoded = parameters().encodeKotlin()
        val values = Iap2ParameterList.parse(encoded)
        encoded.fill(0)
        val copy = values[0].payload
        copy.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), values[0].payload)
    }
}
