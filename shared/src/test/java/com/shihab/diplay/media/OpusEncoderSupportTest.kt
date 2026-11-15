package com.shihab.diplay.media

import org.junit.Assert.*
import org.junit.Test

class OpusEncoderSupportTest {
    @Test fun bundledEncoderKeepsOpusWhenTheBundledEncoderProducesAPacket() {
        val software = SoftwareOpusEncoder(48_000)
        assertTrue(OpusEncoderSupport.isAvailable(software = { software }))
        assertFalse("the probe releases its encoder", software.available)
    }

    @Test fun unavailableSoftwareInitializationDoesNotAdvertiseAnEncoderOrThrow() {
        val software = SoftwareOpusEncoder(48_000, complexity = 11)
        assertFalse(software.available)
        assertFalse(OpusEncoderSupport.isAvailable(software = { software }))
        assertFalse(OpusEncoderSupport.isAvailable(
            software = { throw IllegalStateException("initialization failed") },
        ))
    }

    @Test fun encoderThatCannotProduceAPacketIsNotAUsableCapability() {
        val software = ProbeEncoder { emptyList() }
        assertFalse(OpusEncoderSupport.isAvailable(software = { software }))
        assertEquals(SoftwareOpusEncoder.FRAME_BYTES, software.frameBytes)
        assertTrue(software.closed)
    }

    @Test fun failedEncodingProbeIsContainedAndClosed() {
        val software = ProbeEncoder { throw IllegalStateException("encode failed") }
        assertFalse(OpusEncoderSupport.isAvailable(software = { software }))
        assertTrue(software.closed)
    }

    private class ProbeEncoder(private val encodeFrame: () -> List<ByteArray>) : MicrophoneOpusEncoder {
        override val implementation = "probe"
        override val available = true
        var closed = false
        var frameBytes = 0
        override fun encode(pcm: ByteArray): List<ByteArray> {
            frameBytes = pcm.size
            return encodeFrame()
        }
        override fun close() { closed = true }
    }
}
