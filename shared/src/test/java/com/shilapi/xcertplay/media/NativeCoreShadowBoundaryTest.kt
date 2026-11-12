package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.reference.AudioSchedulerReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** This probes test-only binding replacement, not native acceptance. The facade remains production code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE,
    shadows = [NativeCoreShadowBoundaryTest.NativeLoaded::class, NativeCoreShadowBoundaryTest.Scheduler::class])
class NativeCoreShadowBoundaryTest {
    @Test fun theRealFacadeCanUseATestOnlyBindingAcrossIndependentAndroidSandboxes() {
        assertTrue(NativeMedia.available)
        val progress = AudioBufferProgress(4)
        try {
            assertEquals(1, Scheduler.created)
            progress.written(4000)
            assertEquals(3600L, progress.queuedBytes(100))
            progress.reset()
            assertEquals(0L, progress.queuedBytes(0))
            assertEquals(1, Scheduler.resets)
        } finally {
            progress.close()
        }
        assertEquals(1, Scheduler.destroyed)
        assertTrue(Scheduler.owners.isEmpty())
    }

    @Implements(className = "com.shilapi.xcertplay.airplay.NativeMedia", isInAndroidSdk = false)
    class NativeLoaded {
        @Implementation fun getAvailable(): Boolean = true
    }

    @Implements(className = "com.shilapi.xcertplay.media.NativeAudioScheduler", isInAndroidSdk = false)
    class Scheduler {
        companion object {
            val owners = mutableMapOf<Long, AudioSchedulerReference>()
            var created = 0
            var destroyed = 0
            var resets = 0

            @Implementation @JvmStatic fun create(frameBytes: Int): Long {
                val handle = (++created).toLong()
                owners[handle] = AudioSchedulerReference(frameBytes)
                return handle
            }

            @Implementation @JvmStatic fun written(handle: Long, bytes: Int) { owners.getValue(handle).written(bytes) }
            @Implementation @JvmStatic fun queuedBytes(handle: Long, head: Int): Long = owners.getValue(handle).queuedBytes(head)
            @Implementation @JvmStatic fun reset(handle: Long) { owners.getValue(handle).reset(); resets++ }
            @Implementation @JvmStatic fun destroy(handle: Long) { owners.remove(handle); destroyed++ }

            @Resetter @JvmStatic fun clear() {
                owners.clear()
                created = 0
                destroyed = 0
                resets = 0
            }
        }
    }
}
