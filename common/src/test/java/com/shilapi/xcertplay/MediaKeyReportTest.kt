package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.mockito.Mockito.*

/** A media key that reaches DiPlay's media session is in the diagnostic report, not only in logcat (#610). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaKeyReportTest {
    private val zone = TimeZone.getDefault()
    private val controller = mock(CarPlayController::class.java)

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        WheelKeyLog.reset()
    }

    @After fun tearDown() {
        CarPlayMediaSession.detach(controller)
        TimeZone.setDefault(zone)
        WheelKeyLog.reset()
    }

    @Test fun anUnacceptedStandardMediaCommandIsReportedAsNotSent() {
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaSession.attach(RuntimeEnvironment.getApplication(), controller)
        CarPlayMediaSession.onMediaAudioChanged(true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val callback = CarPlayMediaSession.javaClass.getDeclaredField("callback").apply { isAccessible = true }
            .get(CarPlayMediaSession) as android.media.session.MediaSession.Callback
        callback.onSkipToNext()
        val report = WheelKeyLog.report(bound = false)
        assertTrue(report.toString(), report.any {
            it.startsWith("Wheel keys: ") && it.endsWith("media key next -> CarPlay ${CarPlayMediaButton.NEXT} sent=false")
        })
    }
}
