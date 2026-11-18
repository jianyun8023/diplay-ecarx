package com.shihab.diplay.media

import android.car.Car
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxCarPlayAudioHandshakeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sent = mutableListOf<Intent>()
    private val reports = mutableListOf<String>()
    private val volumes = mutableListOf(20, 10, 14, 10)
    private val restored = mutableListOf<Int>()
    private var volumeReadable = true
    private var volumeClosed = false
    private var currentGroup = 0
    private var savedGroup = -1
    private var savedVolume = 0
    private var parkingWarning = false
    private var alternateReadable = true
    private var alternateAlreadyOn = false
    private var alternateSupported = true
    private var deliverUnduck = true
    private var unduckComplete: (() -> Unit)? = null

    @Before fun allowFocus() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun isAlternateOn(): Boolean? {
        if (!alternateReadable) return null
        val shadow = shadowOf(audio)
        val request = shadow.lastAudioFocusRequest?.audioFocusRequest
        return alternateAlreadyOn || alternateSupported && request?.audioAttributes?.contentType == 33 &&
            request !== shadow.lastAbandonedAudioFocusRequest
    }

    private fun handshake() = EcarxCarPlayAudioHandshake(
        app, report = reports::add, alternateAudioOn = ::isAlternateOn,
        connectVolume = {
            object : EcarxCarAudioGroups {
                override fun markableNavigationVolume() = volumes[1].takeIf { volumeReadable && volumes.all { it > 0 } }
                override fun navigationVolume() = volumes[1].takeIf { volumeReadable }
                override fun restoreNavigationVolume(value: Int) {
                    restored += value
                    volumes[1] = value
                }
                override fun close() { volumeClosed = true }
            }
        },
        broadcast = { intent, complete ->
            sent += intent
            assertEquals(0, intent.getIntExtra("volume", -1))
            if (!parkingWarning) {
                if (intent.getStringExtra("command") == "duckAudio") {
                    savedGroup = currentGroup
                    savedVolume = volumes[currentGroup]
                    volumes[currentGroup] = maxOf(1, savedVolume)
                } else if (savedGroup >= 0) {
                    volumes[savedGroup] = savedVolume
                    savedGroup = -1
                }
            }
            if (deliverUnduck) complete?.invoke() else unduckComplete = complete
        },
    )

    private fun commands() = sent.map { it.getStringExtra("command") }

    @Test
    @Config(sdk = [25, 33])
    fun versionsWithoutTheVendorContentTypeStayOnStandardRouting() {
        val handshake = EcarxCarPlayAudioHandshake(
            app,
            alternateAudioOn = { false },
            connectVolume = { error("unsupported mode cannot connect car volume") },
        )
        try {
            assertFalse(handshake.probe())
            assertFalse(handshake.onPrompt(true))
            assertNull(shadowOf(audio).lastAudioFocusRequest)
        } finally {
            handshake.close()
        }
    }

    @Test fun carAudioVolumeChecksTheFirmwareGroupsAndRestoresOnlyNavigation() {
        Car.reset()
        Car.available = true
        shadowOf(app).grantPermissions("android.car.permission.CAR_CONTROL_AUDIO_VOLUME")
        val groups = EcarxCarAudioVolume(app, Handler(Looper.getMainLooper()), reports::add)
        try {
            val settings = Car.last.audio.volumes
            settings.putAll(mapOf(0 to 20, 1 to 10, 2 to 14, 3 to 0, 4 to 0))
            assertNull(groups.markableNavigationVolume())
            settings[4] = 12
            assertEquals(10, groups.markableNavigationVolume())
            groups.restoreNavigationVolume(0)
            assertEquals(0, groups.navigationVolume())
            assertEquals(20, settings[0])
        } finally {
            groups.close()
            assertTrue(Car.last.disconnected)
            Car.reset()
        }
    }

    @Test fun probeVerifiesBothTransitionsWithoutBroadcastAndTheModeBelongsToEachPrompt() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        assertEquals(false, isAlternateOn())
        assertTrue(sent.isEmpty())
        assertTrue(handshake.onPrompt(true))
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(33, request.audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(true, isAlternateOn())
        repeat(3) { assertTrue(handshake.onPrompt(true)) }
        assertEquals(listOf("duckAudio"), commands())
        handshake.onPrompt(false)
        assertEquals(false, isAlternateOn())
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertEquals(listOf("duckAudio", "unduckAudio"), commands())
        assertTrue(handshake.onPrompt(true))
        assertEquals(true, isAlternateOn())
        handshake.close()
        assertEquals(false, isAlternateOn())
    }

    @Test fun initiallyZeroMediaIsNotRaisedWhenFocusMovesToNavigation() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        volumes[0] = 0
        assertFalse(handshake.onPrompt(true))
        currentGroup = 1
        handshake.onPrompt(false)
        assertEquals(listOf(0, 10, 14, 10), volumes)
        assertTrue(sent.isEmpty())
        assertEquals(false, isAlternateOn())
        handshake.close()
    }

    @Test fun zeroNavigationAndUnreadableGroupsAlsoSkipTheMarker() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        volumes[1] = 0
        assertFalse(handshake.onPrompt(true))
        volumes[1] = 10
        volumeReadable = false
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        volumeReadable = true
        assertTrue(handshake.onPrompt(true))
        handshake.close()
    }

    @Test fun unduckDoesNotReplaceTheSnapshotWhenTheFocusGroupChanges() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        assertTrue(handshake.onPrompt(true))
        currentGroup = 1
        volumes[1] = 0
        handshake.onPrompt(false)
        assertEquals(listOf(20, 0, 14, 10), volumes)
        assertEquals(listOf("duckAudio", "unduckAudio"), commands())
        assertTrue(restored.isEmpty())
        handshake.close()
    }

    @Test fun unduckPreservesAChangeToTheGroupTheFirmwareActuallyRestored() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        currentGroup = 1
        assertTrue(handshake.onPrompt(true))
        volumes[1] = 0
        deliverUnduck = false
        handshake.close()
        assertEquals(10, volumes[1])
        assertFalse(volumeClosed)
        unduckComplete!!.invoke()
        assertEquals(0, volumes[1])
        assertEquals(listOf(0), restored)
        assertTrue(volumeClosed)
    }

    @Test fun parkingWarningMayIgnoreUnduckButCannotKeepOurAlternateModeOpen() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        assertTrue(handshake.onPrompt(true))
        assertTrue(savedGroup >= 0 && isAlternateOn() == true)
        parkingWarning = true
        volumes[0] = 2
        deliverUnduck = false
        handshake.onPrompt(false)
        assertTrue(savedGroup >= 0) // The receiver discarded the broadcast.
        assertEquals(false, isAlternateOn())
        parkingWarning = false
        volumes[0] = 20 // The parking policy restores media after the discarded unduck.
        unduckComplete!!.invoke()
        assertEquals(20, volumes[0])
        assertTrue(restored.isEmpty())
        deliverUnduck = true
        assertTrue(handshake.onPrompt(true))
        handshake.onPrompt(false)
        assertEquals(-1, savedGroup)
        assertEquals(false, isAlternateOn())
        handshake.close()
    }

    @Test fun quietCloseWaitsForCleanupAndLateAudioCannotReopenTheMode() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        assertTrue(handshake.onPrompt(true))
        deliverUnduck = false
        handshake.onPrompt(false)
        assertFalse(handshake.onPrompt(true))
        handshake.close()
        assertFalse(handshake.onPrompt(true))
        assertEquals(listOf("duckAudio", "unduckAudio"), commands())
        unduckComplete!!.invoke()
        assertTrue(volumeClosed)
    }

    @Test fun anAlreadyOpenModeIsLeftWithItsExistingOwner() {
        alternateAlreadyOn = true
        val handshake = handshake()
        assertFalse(handshake.probe())
        assertFalse(handshake.onPrompt(true))
        handshake.close()
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertTrue(sent.isEmpty())
    }

    @Test fun anUnreadableProbeIsLatchedForTheSinkWithoutAttemptingAToggle() {
        alternateReadable = false
        val handshake = handshake()
        assertFalse(handshake.probe())
        alternateReadable = true
        assertFalse(handshake.probe())
        assertFalse(handshake.onPrompt(true))
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertTrue(sent.isEmpty())
        assertEquals(1, reports.count { "modeProbe=unsupported" in it })
        handshake.close()
    }

    @Test fun aRejectedRequestDoesNotSendAMarker() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val handshake = handshake()
        assertFalse(handshake.probe())
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        handshake.close()
    }

    @Test fun aGrantedRequestWithoutAChangeStillReturnsTheProbeRequest() {
        alternateSupported = false
        val handshake = handshake()
        assertFalse(handshake.probe())
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        handshake.close()
    }

    @Test fun aVerifiedModeBecomingTemporarilyUnreadableDoesNotChangeCapability() {
        val handshake = handshake()
        assertTrue(handshake.probe())
        alternateReadable = false
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        assertTrue(handshake.state().contains("modeProbe=verified"))
        alternateReadable = true
        assertTrue(handshake.onPrompt(true))
        handshake.close()
    }
}
