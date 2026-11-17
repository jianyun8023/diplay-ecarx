package com.shihab.diplay.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contracts the Rust-owned microphone effective-configuration handoff through the real native bridge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneConfigHandoffContractTest {
    @Test
    fun reuseOnlyAfterACommittedConfigurationAndReplaceOtherwise() {
        val handoff = NativeMicrophone.uplinkConfigCreate()
        try {
            // Nothing committed yet: a first configuration must replace (start a fresh sender).
            assertEquals(1, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-a"))
            NativeMicrophone.uplinkConfigCommit(handoff, "cfg-a")
            // The committed effective configuration is reused, so a same-id start does not restart.
            assertEquals(0, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-a"))
            // Any change (port, key or format) replaces the previous sender.
            assertEquals(1, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-b"))
        } finally {
            NativeMicrophone.uplinkConfigDestroy(handoff)
        }
    }
}
