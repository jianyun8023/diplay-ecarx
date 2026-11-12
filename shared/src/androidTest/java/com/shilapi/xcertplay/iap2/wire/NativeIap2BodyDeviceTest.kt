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
}
