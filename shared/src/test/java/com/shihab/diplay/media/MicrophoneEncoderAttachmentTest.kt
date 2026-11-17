package com.shihab.diplay.media

import android.Manifest
import android.media.AudioRecord
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MicrophoneEncoderAttachmentTest {
    private val diagnostics = CopyOnWriteArrayList<String>()
    private var uplink: MicrophoneUplink? = null

    @Before fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        uplink?.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun realNativePipelineStartsAndReleasesItsRecorder() {
        val next = MicrophoneUplink(config(), onDiagnostic = diagnostics::add)
        uplink = next
        assertTrue(next.start())
        val recorder = field(next, "recorder") as AudioRecord
        assertNotNull(field(next, "nativeSender"))
        assertTrue(diagnostics.any { it.contains("implementation=native encoder=libopus") })
        next.close()
        assertReleased(next, recorder)
    }

    @Test fun missingNativePipelineFailsWithoutStartingTheRecorder() {
        lateinit var recorder: AudioRecord
        val next = MicrophoneUplink(config(), onDiagnostic = diagnostics::add, nativeSenderFactory = { _, _ ->
            recorder = field(uplink!!, "recorder") as AudioRecord
            null
        })
        uplink = next
        assertFalse(next.start())
        assertTrue(diagnostics.any { it.contains("stage=NATIVE_PIPELINE") })
        assertFalse(diagnostics.any { it.startsWith("Microphone: start") })
        assertReleased(next, recorder)
    }

    @Test fun rejectedNativeConfigurationCannotSelectAJavaSender() {
        val next = MicrophoneUplink(config().copy(key = ByteArray(1)), onDiagnostic = diagnostics::add)
        uplink = next
        assertFalse(next.start())
        assertTrue(diagnostics.any { it.contains("stage=NATIVE_PIPELINE") })
        assertNull(field(next, "recorder"))
        assertNull(field(next, "nativeSender"))
    }

    @Test fun senderFactoryExceptionReleasesSdkResourcesAndPreservesTheFailure() {
        val failure = IllegalStateException("sender creation failed")
        lateinit var recorder: AudioRecord
        val next = MicrophoneUplink(config(), nativeSenderFactory = { _, _ ->
            recorder = field(uplink!!, "recorder") as AudioRecord
            throw failure
        })
        uplink = next
        assertSame(failure, assertThrows(IllegalStateException::class.java) { next.start() })
        assertReleased(next, recorder)
    }

    private fun assertReleased(next: MicrophoneUplink, recorder: AudioRecord) {
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        assertNull(field(next, "recorder"))
        assertNull(field(next, "nativeSender"))
        next.close()
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun config() = MicrophoneConfig(
        audioType = "media", sampleRate = 48_000, channels = 1, payloadType = 100, frameMillis = 20,
        host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32), codec = AudioCodecKind.OPUS,
    )
}
