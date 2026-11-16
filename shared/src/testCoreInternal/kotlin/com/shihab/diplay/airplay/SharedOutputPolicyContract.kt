package com.shihab.diplay.airplay

import com.shihab.diplay.media.NativeAudioScheduler
import com.shihab.diplay.media.NativeVideoFlow
import org.junit.Assert.assertEquals

/** Independent reference retained from the minimal Kotlin fix before its Rust migration. */
private object KotlinSharedOutputPolicy {
    fun audioMayStop(otherRegistration: Boolean): Boolean = !otherRegistration
    fun screenMayStop(otherRegistration: Boolean): Boolean = !otherRegistration
    fun duckMayNotify(ducked: Boolean, closing: Boolean, released: Boolean, ownerPresent: Boolean): Boolean =
        ducked || released || (!closing && !ownerPresent)
}

internal object SharedOutputPolicyContract {
    fun verify() {
        NativeMedia.requireAvailable()
        val facts = listOf(false, true)
        for (other in facts) {
            assertEquals("audio otherRegistration=$other", KotlinSharedOutputPolicy.audioMayStop(other),
                NativeMedia.audioOutputMayStop(other))
            assertEquals("screen otherRegistration=$other", KotlinSharedOutputPolicy.screenMayStop(other),
                NativeVideoFlow.screenOutputMayStop(other))
        }
        for (ducked in facts) for (closing in facts) for (released in facts) for (ownerPresent in facts) {
            assertEquals("duck=$ducked closing=$closing released=$released owner=$ownerPresent",
                KotlinSharedOutputPolicy.duckMayNotify(ducked, closing, released, ownerPresent),
                NativeAudioScheduler.duckOutputMayNotify(ducked, closing, released, ownerPresent))
        }
    }
}
