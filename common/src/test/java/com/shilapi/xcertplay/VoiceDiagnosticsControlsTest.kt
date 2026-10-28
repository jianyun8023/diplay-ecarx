package com.shilapi.xcertplay

import android.app.Activity
import android.media.MediaRecorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceDiagnosticsControlsTest {
    @Test fun levelsHandleSilenceAndMinimumPcmValue() {
        assertEquals("samples=0 peak=0 rms=0.0 zero=0 peakDbfs=-Infinity rmsDbfs=-Infinity", VoiceDiagnosticsControls.levels(shortArrayOf(), 0))
        assertEquals("samples=2 peak=32768 rms=23170.47500592079 zero=1 peakDbfs=0.0 rmsDbfs=-3.0102999566398116", VoiceDiagnosticsControls.levels(shortArrayOf(Short.MIN_VALUE, 0), 2))
        assertEquals("VOICE_COMMUNICATION", VoiceDiagnosticsControls.sourceName(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
    }
    @Test fun closingErasesRetainedPcmAndCancelsWorkerWithoutWaiting() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val controls = VoiceDiagnosticsControls(activity)
        val audio = shortArrayOf(100, -100)
        val field = controls.javaClass.getDeclaredField("pcm").apply { isAccessible = true }
        field.set(controls, audio)
        controls.close()
        assertArrayEquals(shortArrayOf(0, 0), audio)
        assertNull(field.get(controls))
        assertTrue(controls.javaClass.getDeclaredField("cancelled").apply { isAccessible = true }.getBoolean(controls))
        activity.finish()
    }
}
