package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import org.junit.Assert.*
import org.junit.Test

/** Existing scalar API distinguishes checked writers from bit-preserving readers. */
class Iap2ScalarRemainingContractTest {
    @Test fun unsignedWritersRejectNegativeValuesWhileReadersPreserveAllLongBits() {
        assertThrows(IllegalArgumentException::class.java) { Iap2WireCodec.u32(-1) }
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), Iap2WireCodec.i32(-1))
        assertEquals(0xffff_ffffL, Iap2WireCodec.readU32(byteArrayOf(0, -1, -1, -1, -1), 1))
        assertEquals(-1L, Iap2WireCodec.readU64(ByteArray(8) { -1 }))
        assertThrows(IllegalArgumentException::class.java) { Iap2WireCodec.u64(-1) }
        assertTrue(Iap2WireCodec.readBool(byteArrayOf(2)))
        assertFalse(Iap2WireCodec.readBool(byteArrayOf(2), nonzeroIsTrue = false))
    }

    @Test fun scalarStringReadingPreservesInteriorNulButStringListSeparatesEmptyFields() {
        assertEquals("A\u0000B", Iap2WireCodec.readString(byteArrayOf(65, 0, 66, 0)))
        assertEquals(listOf("A", "", "B"), Iap2WireCodec.readStrings(byteArrayOf(65, 0, 0, 66, 0)))
        assertEquals(listOf("", ""), Iap2WireCodec.readStrings(byteArrayOf(0, 0)))
        assertEquals(emptyList<String>(), Iap2WireCodec.readStrings(byteArrayOf()))
        assertEquals("CSM string is not NUL terminated", assertThrows(Iap2ProtocolException::class.java) {
            Iap2WireCodec.readString(byteArrayOf())
        }.message)
        assertThrows(IllegalArgumentException::class.java) { Iap2WireCodec.string("A\u0000B") }
    }

    @Test fun malformedUtf8AndUtf16FollowTheExistingPlatformCharsetBoundary() {
        val malformed = byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())
        assertEquals(malformed.decodeToString(), Iap2WireCodec.readString(malformed + byteArrayOf(0)))
        val malformedUtf16 = "x\ud800"
        assertArrayEquals(malformedUtf16.encodeToByteArray() + byteArrayOf(0), Iap2WireCodec.string(malformedUtf16))
        assertEquals("CSM u16[] payload has odd length 1", assertThrows(Iap2ProtocolException::class.java) {
            Iap2WireCodec.readU16List(byteArrayOf(1))
        }.message)
    }
}
