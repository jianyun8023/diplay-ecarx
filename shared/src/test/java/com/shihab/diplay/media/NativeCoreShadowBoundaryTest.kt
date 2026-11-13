package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the global host-core binding; only the three Android SDK sandboxes are simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
class NativeCoreShadowBoundaryTest {
    @Test fun theGlobalBindingPreservesAccountingResetAndCloseAcrossAndroidSandboxes() {
        assertTrue(NativeMedia.available)
        val progress = AudioBufferProgress(4)
        val handle = AudioBufferProgress::class.java.getDeclaredField("handle").apply { isAccessible = true }
        assertNotEquals(0L, handle.getLong(progress))
        try {
            progress.written(4000)
            assertEquals(3600L, progress.queuedBytes(100))
            progress.reset()
            assertEquals(0L, progress.queuedBytes(0))
        } finally {
            progress.close()
        }
        assertEquals(0L, handle.getLong(progress))
        progress.close()
        assertEquals(0L, handle.getLong(progress))
    }
}
