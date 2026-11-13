package com.shihab.diplay.transport

import org.junit.Assert.*
import org.junit.Test

/** Pure production JNI owners; USB/TUN device acceptance remains a separate Android contract. */
class NcmDefaultCoreContractTest {
    @Test fun codecsMatchTheIndependentKotlinReferenceIncludingRangesAndUnchangedPackets() {
        for (size in listOf(1, 20, 484, 996, 65_507)) {
            val frame = ByteArray(size) { (it * 17).toByte() }
            val block = Ntb16Codec.build(frame, 0xffff)
            assertArrayEquals(KotlinNtb16Codec.build(frame, 0xffff), block)
            val surrounding = ByteArray(11) + block + ByteArray(3)
            assertArrayEquals(frame, Ntb16Codec.parse(surrounding, 11, block.size).single())
        }
        // A nonzero source offset historically treats an initial zero NDP differently from offset zero.
        val malformed = Ntb16Codec.build(byteArrayOf(1, 2), 12).also { it[10] = 0; it[11] = 0 }
        val surrounding = ByteArray(11) + malformed
        val expected = KotlinNtb16Codec.parse(surrounding, 11, malformed.size)
        val actual = Ntb16Codec.parse(surrounding, 11, malformed.size)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertArrayEquals(left, right) }
        val host = byteArrayOf(2, 0, 0, 0, 0, 1)
        val ordinary = ByteArray(40).also { it[0] = 0x60 }
        assertSame(ordinary, EthernetIpv6Codec.addNeighborAdvertisementTargetMac(ordinary, host))
        assertArrayEquals(KotlinEthernetIpv6Codec.build(host, host, ordinary), EthernetIpv6Codec.build(host, host, ordinary))
    }

    @Test fun partialFirstWriteMarksTheLinkButDoesNotCountACompleteSentFrame() {
        val tx = NcmTransmitCore()
        assertNull(tx.openedAt)
        assertEquals(20, tx.timeout(2_000))
        assertEquals(5, tx.timeout(5))
        assertArrayEquals(longArrayOf(0, 1, 1), tx.begin(-1))
        assertArrayEquals(longArrayOf(0, 0, 2), tx.begin(0))
        assertArrayEquals(longArrayOf(2, 0, 2), tx.begin(1))
        tx.opened(0)
        assertEquals(0L, tx.openedAt)
        assertEquals(-1, tx.finish(1, 100))
        assertEquals(2_000, tx.timeout(2_000))
        assertArrayEquals(longArrayOf(1, 0, 2), tx.begin(100))
        assertEquals(1, tx.finish(100, 100))
        assertEquals(0, tx.finish(100, 100))
        assertEquals(0, tx.nextSequence())
        repeat(65_535) { tx.nextSequence() }
        assertEquals(0, tx.nextSequence())
    }

    @Test fun resyncRequestsClockOnlyAtEpisodeStartAndReportsBeforeTheNextEpisode() {
        NcmReceiveCore(64).use { rx ->
            val block = Ntb16Codec.build(byteArrayOf(1, 2, 3), 0)
            val bytes = byteArrayOf(0x55) + block + byteArrayOf(0x66) + block
            rx.append(bytes, bytes.size)
            val detail = arrayOfNulls<String>(1)
            assertEquals(1, rx.step(null, detail))
            assertEquals(2, rx.step(100, detail))
            assertEquals("NCM resynchronized after NCM read buffer does not begin with an NTB16 header; skipped=1 resyncs=1", detail[0])
            assertEquals(1, rx.step(null, detail))
            assertEquals(2, rx.step(101, detail))
            assertTrue(requireNotNull(detail[0]).endsWith("skipped=1 resyncs=2"))
            assertEquals(0, rx.step(null, detail))
            assertArrayEquals(byteArrayOf(1, 2, 3), rx.poll())
            assertArrayEquals(byteArrayOf(1, 2, 3), rx.poll())
            assertNull(rx.poll())
        }
    }

    @Test fun queueBoundsAndNativeLifetimeAreStillEnforcedWithoutAJavaFrameQueue() {
        val rx = NcmReceiveCore(512)
        try {
            repeat(257) { rx.append(Ntb16Codec.build(byteArrayOf(1), it), 29) }
            val detail = arrayOfNulls<String>(1)
            assertEquals(-1, rx.step(null, detail))
            assertEquals("NCM frame queue exceeded its bounds", detail[0])
        } finally { rx.close() }
        rx.close()
        try { rx.poll(); fail("closed core was used") }
        catch (error: IphoneUsbException.DeviceUnavailable) { assertEquals("NCM bridge is closed", error.message) }
    }

    @Test fun sourceMacLearningAndMulticastSelectionHaveOneNativeState() {
        val route = NcmIpv6Route()
        val detail = LongArray(1)
        val ipv6 = ByteArray(40).also { it[0] = 0x60 }
        assertNull(route.destination(ipv6, detail))
        val host = byteArrayOf(2, 0, 0, 0, 0, 1)
        val peer = byteArrayOf(2, 0, 0, 0, 0, 2)
        assertNotNull(route.inbound(EthernetIpv6Codec.build(peer, host, ipv6)))
        assertArrayEquals(peer, route.destination(ipv6, detail))
        assertEquals(0L, detail[0])
        ipv6[24] = 0xff.toByte(); ipv6[39] = 1
        assertArrayEquals(byteArrayOf(0x33, 0x33, 0, 0, 0, 1), route.destination(ipv6, detail))
        assertEquals(1L, detail[0])
    }
}
