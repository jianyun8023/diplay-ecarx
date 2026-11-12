package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class BplistCodecContractTest {
    @Test fun unsignedBigIntegersUseZeroPaddedWidthsAndDecodeAsLongBits() {
        val cases = listOf(
            BigInteger.valueOf(65536) to "1200010000",
            BigInteger.ONE.shiftLeft(40) to "130000010000000000",
            BigInteger.ONE.shiftLeft(63) to "138000000000000000",
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE) to "13ffffffffffffffff",
        )
        for ((value, hex) in cases) {
            val encoded = BplistCodec.encode(value)
            assertArrayEquals(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), encoded.copyOfRange(8, 8 + hex.length / 2))
            assertEquals(value.toLong(), BplistCodec.decode(encoded))
        }
    }

    @Test fun scalarTypesAndNegativeNumberEncodingStayUnchanged() {
        for (value in listOf<Any>(0.toByte(), 256.toShort(), 65536, Long.MAX_VALUE)) {
            assertEquals((value as Number).toLong(), BplistCodec.decode(BplistCodec.encode(value)))
        }
        for (value in listOf<Any>(-1.toByte(), (-256).toShort(), -65536, Long.MIN_VALUE, 1.25f, -0.0f, Double.NEGATIVE_INFINITY)) {
            val decoded = BplistCodec.decode(BplistCodec.encode(value))
            assertTrue(decoded is Double)
            assertEquals((value as Number).toDouble().toRawBits(), (decoded as Double).toRawBits())
        }
    }

    @Test fun dictionaryKeysUseToStringAndInsertionOrder() {
        val root = linkedMapOf<Any?, Any>(null to true, 7 to "seven", "name\u0000😀" to byteArrayOf(0, -1))
        val decoded = BplistCodec.decode(BplistCodec.encode(root)) as Map<*, *>
        assertEquals(listOf("null", "7", "name\u0000😀"), decoded.keys.toList())
        assertArrayEquals(byteArrayOf(0, -1), decoded["name\u0000😀"] as ByteArray)
    }

    @Test fun textUsesTheHostCharsetOracleForMalformedUtf16() {
        // The desktop JDK may consume malformed high-surrogate + ASCII differently from Android.
        for (value in listOf("", "a\u0000b", "Bülli😀", "\ufeffA", "\ud800", "\udc00", "\ud800A", "\ud800\ud801")) {
            val encoded = BplistCodec.encode(value)
            val charset = if (value.all { it.code <= 127 }) Charsets.US_ASCII else Charsets.UTF_16BE
            assertEquals(String(value.toByteArray(charset), charset), BplistCodec.decode(encoded))
        }
    }

    @Test fun unsupportedValuesAndUnsignedBoundsRemainIllegalArguments() {
        for (value in listOf(null, Date(0), 1.toBigDecimal(), 'x')) {
            val error = assertThrows(IllegalArgumentException::class.java) { BplistCodec.encode(value) }
            assertEquals("bplist: unsupported value $value", error.message)
        }
        assertEquals("bplist: negative integers are not supported", assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.encode(BigInteger.valueOf(-1))
        }.message)
        assertEquals("bplist: integer exceeds 64 bits", assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.encode(BigInteger.ONE.shiftLeft(64))
        }.message)
    }

    @Test fun encoderPreservesDeeperThanDecodeTreesButRejectsCyclesAt128() {
        var root: Any? = true
        repeat(128) { root = listOf(root) }
        assertTrue(BplistCodec.encode(root).isNotEmpty())
        assertEquals("bplist: nested too deep to encode", assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.encode(listOf(root))
        }.message)
        val cycle = ArrayList<Any?>().apply { add(this) }
        assertEquals("bplist: nested too deep to encode", assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.encode(cycle)
        }.message)
    }

    @Test fun bigIntegerCallbackExceptionsPropagateWithoutReplacement() {
        val throwing = object : BigInteger("7") {
            override fun toByte(): Byte = 7
            override fun toShort(): Short = 7
            override fun toLong(): Long = throw IllegalStateException("plist BigInteger\u0000failed")
        }
        assertEquals("plist BigInteger\u0000failed", assertThrows(IllegalStateException::class.java) {
            BplistCodec.encode(throwing)
        }.message)
    }

    @Test fun fixedReferenceWireVectorKeepsTheExistingOrderAndByteLayout() {
        // LIVI fixture, pinned at 6ac88bab7351a39b0cffac20c5dcf92ca848319b; no runtime import.
        val root = linkedMapOf(
            "type" to "hidSendReport", "uuid" to "2a2a2a2a", "hidReport" to byteArrayOf(0, 1, 2, 3),
            "n0" to 0, "n255" to 255, "n256" to 256, "n65536" to 65536, "big" to (1L shl 40),
            "neg" to -1.0, "frac" to 0.5, "t" to true, "f" to false, "uni" to "Bülli",
            "list" to listOf(1, "two", listOf(3)), "empty" to emptyMap<String, Any>(),
            "long" to "x".repeat(20), "many" to (0 until 20).toList(),
        )
        val encoded = BplistCodec.encode(root)
        assertEquals(458, encoded.size)
        assertEquals("b4e3e93557bb97d6207978c92e3a52f533a60cd3f0e9034a4253c080b83e872a",
            MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) })
    }
}
