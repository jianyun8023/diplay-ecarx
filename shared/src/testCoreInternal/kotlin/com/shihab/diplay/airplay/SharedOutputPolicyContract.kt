package com.shihab.diplay.airplay

import com.shihab.diplay.media.NativeAudioScheduler
import org.junit.Assert.assertEquals

/** Independent reference retained from the minimal Kotlin fix before its Rust migration. */
private object KotlinSharedOutputPolicy {
    fun audioMayStop(otherRegistration: Boolean): Boolean = !otherRegistration
    fun screenMayStop(otherRegistration: Boolean): Boolean = !otherRegistration
    fun duckMayNotify(ducked: Boolean, closing: Boolean, released: Boolean, ownerPresent: Boolean): Boolean =
        ducked || released || (!closing && !ownerPresent)
}

internal object SharedOutputPolicyContract {
    private const val HOLDER_A = 1L
    private const val HOLDER_B = 2L
    private val epochSource = java.util.concurrent.atomic.AtomicLong(1_000_000)

    fun verify() {
        NativeMedia.requireAvailable()
        val facts = listOf(false, true)
        for (other in facts) {
            // The last release of an output identity is the only time the platform may stop it.
            val audioEpoch = epochSource.incrementAndGet()
            NativeMedia.outputLeaseAcquire(audioEpoch, HOLDER_A, NativeMedia.LEASE_AUDIO, 100, "media")
            if (other) NativeMedia.outputLeaseAcquire(audioEpoch, HOLDER_B, NativeMedia.LEASE_AUDIO, 100, "media")
            assertEquals("audio otherRegistration=$other", KotlinSharedOutputPolicy.audioMayStop(other),
                NativeMedia.outputLeaseRelease(audioEpoch, HOLDER_A, NativeMedia.LEASE_AUDIO, 100, "media"))

            val screenEpoch = epochSource.incrementAndGet()
            NativeMedia.outputLeaseAcquire(screenEpoch, HOLDER_A, NativeMedia.LEASE_SCREEN, 110, "")
            if (other) NativeMedia.outputLeaseAcquire(screenEpoch, HOLDER_B, NativeMedia.LEASE_SCREEN, 110, "")
            assertEquals("screen otherRegistration=$other", KotlinSharedOutputPolicy.screenMayStop(other),
                NativeMedia.outputLeaseRelease(screenEpoch, HOLDER_A, NativeMedia.LEASE_SCREEN, 110, ""))
        }
        for (ducked in facts) for (closing in facts) for (released in facts) for (ownerPresent in facts) {
            assertEquals("duck=$ducked closing=$closing released=$released owner=$ownerPresent",
                KotlinSharedOutputPolicy.duckMayNotify(ducked, closing, released, ownerPresent),
                NativeAudioScheduler.duckOutputMayNotify(ducked, closing, released, ownerPresent))
        }
    }
}
