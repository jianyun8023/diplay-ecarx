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
            assertEquals(1, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-a", "key-a"))
            NativeMicrophone.uplinkConfigCommit(handoff, "cfg-a", "key-a")
            // The committed effective configuration is reused, so a same-id start does not restart.
            assertEquals(0, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-a", "key-a"))
            // Different configuration but the same key: replace while preserving the ChaCha nonce.
            assertEquals(2, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-b", "key-a"))
            // A different key replaces with a fresh nonce.
            assertEquals(1, NativeMicrophone.uplinkConfigDecide(handoff, "cfg-c", "key-b"))
        } finally {
            NativeMicrophone.uplinkConfigDestroy(handoff)
        }
    }
}
