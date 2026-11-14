package com.shihab.diplay.media

import org.junit.Assert.*
import org.junit.Test

class DecoderConfigurationPolicyContractTest {
    @Test fun api28SoftwareFallbackNeedsAnActualHardwareCreateFailure() {
        val policy = DecoderConfigurationPolicy(60, mtk = false, vendorRefused = false)
        val software = "OMX.google.avc.decoder"
        policy.begin(60, software, null, MtkDecoderMode.NONE, 28)
        assertNull(policy.next(software, null)?.codecName)
        policy.hardwareCreateFailed = true
        assertEquals(software, policy.next(software, null)?.codecName)
        assertEquals(1, policy.result(true, false))
        assertNull(policy.next(software, null))
        policy.begin(60, software, null, MtkDecoderMode.NONE, 28)
        assertFalse(policy.hardwareCreateFailed)
        while (true) {
            val attempt = policy.next(software, null) ?: break
            assertNull(attempt.codecName)
            assertEquals(0, policy.result(false, false))
        }
        policy.begin(60, software, null, MtkDecoderMode.NONE, 29)
        var last: DecoderAttempt? = null
        while (true) { val attempt = policy.next(software, null) ?: break; last = attempt }
        assertEquals(software, last?.codecName)
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
        assertTrue(policy.dropsRateAfterFailure(codecFailure = true))
        policy.firstFrame()
        assertFalse(policy.dropsRateAfterFailure(codecFailure = true))
        policy.finishedAttempt()
        assertTrue(policy.dropsRateAfterFailure(codecFailure = true))
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
        assertEquals(MtkDecoderMode.LOW_LATENCY_NO_REORDER, policy.requestedMtkMode(true))
        policy.acceptMtkFallback()
        assertEquals(MtkDecoderMode.LOW_LATENCY, policy.requestedMtkMode(true))
        assertEquals(1, policy.dropMtk())
        assertEquals(MtkDecoderMode.NONE, policy.requestedMtkMode(true))
        for (expected in listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            policy.advanceProbeInterval()
            assertEquals(expected, policy.probeIntervalMillis)
        }
        assertFalse(policy.probesHardware(hardware = true, software = false, preferred = false, sameHardware = true))
        assertEquals(2_000L, policy.probeIntervalMillis)
        policy.finish(30, MtkDecoderMode.NONE, MtkDecoderMode.NONE, configured = true, codec2 = true, legacy = false)
        assertTrue(policy.dropMtkCodec2())
        assertFalse(policy.tryLowLatency)
        assertFalse(policy.tryMtkCodec2)
        assertFalse(policy.dropMtkCodec2())
    }
}
