package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class DecoderConfigurationPolicyContractTest {
    @Test fun api28SoftwareFallbackNeedsAnActualHardwareCreateFailure() {
        val policy = DecoderConfigurationPolicy(60, mtk = false, vendorRefused = false)
        val hardware = DecoderAttempt(null, tuned = true)
        val software = DecoderAttempt("OMX.google.avc.decoder", tuned = false)
        policy.begin()
        assertTrue(policy.allows(hardware, software = false, sdk = 28))
        assertFalse(policy.allows(software, software = true, sdk = 28))
        assertTrue(policy.allows(software, software = true, sdk = 29))
        policy.hardwareCreateFailed = true
        assertFalse(policy.allows(hardware, software = false, sdk = 28))
        assertTrue(policy.allows(software, software = true, sdk = 28))
        policy.begin()
        assertFalse(policy.hardwareCreateFailed)
        assertFalse(policy.allows(software, software = true, sdk = 28))
    }

    @Test fun privateParameterFailurePersistsWhileActualConfiguredFactsClearOnRelease() {
        val policy = DecoderConfigurationPolicy(60, mtk = true, vendorRefused = false)
        val qti = videoCodecNameFlags("OMX.qcom.video.decoder.avc")
        assertEquals(14, policy.formatFlags(true, true, true, 28, false, false, qti))
        policy.noteParameters(vendor = true, privateParameters = true)
        policy.noteFeatures(lowLatency = false, pictureOrder = true)
        assertTrue(policy.declineVendor())
        assertFalse(policy.declineVendor())
        assertEquals(0, policy.formatFlags(true, true, true, 28, false, false, qti))
        policy.finish(60, MtkDecoderMode.NONE, MtkDecoderMode.NONE, configured = true, codec2 = false, legacy = false)
        assertTrue(policy.dropsRateAfterFailure(codecFailure = true, rendered = false))
        assertFalse(policy.dropsRateAfterFailure(codecFailure = true, rendered = true))
        policy.released()
        assertEquals(0, policy.configuredRate)
        assertFalse(policy.configuredVendorLowLatency)
        assertTrue(policy.vendorLowLatencyRefused)
        assertEquals(60, policy.operatingRate)
        assertEquals(60, policy.dropRate())
        assertEquals(0, policy.operatingRate)
    }

    @Test fun mtkFallbackCommitAndHardwareProbeBudgetRetainTheirOwnerBoundaries() {
        val policy = DecoderConfigurationPolicy(30, mtk = true, vendorRefused = false)
        assertTrue(policy.finish(30, MtkDecoderMode.LOW_LATENCY, MtkDecoderMode.LOW_LATENCY_NO_REORDER,
            configured = true, codec2 = false, legacy = true))
        assertEquals(MtkDecoderMode.LOW_LATENCY_NO_REORDER, policy.maximumMtkMode)
        policy.acceptMtkFallback()
        assertEquals(MtkDecoderMode.LOW_LATENCY, policy.maximumMtkMode)
        assertEquals(1, policy.dropMtk())
        assertEquals(MtkDecoderMode.NONE, policy.maximumMtkMode)
        for (expected in listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            policy.advanceProbeInterval()
            assertEquals(expected, policy.probeIntervalMillis)
        }
        policy.resetProbeInterval()
        assertEquals(2_000L, policy.probeIntervalMillis)
        policy.finish(30, MtkDecoderMode.NONE, MtkDecoderMode.NONE, configured = true, codec2 = true, legacy = false)
        assertTrue(policy.dropMtkCodec2())
        assertFalse(policy.tryLowLatency)
        assertFalse(policy.tryMtkCodec2)
        assertFalse(policy.dropMtkCodec2())
    }
}
