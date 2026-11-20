package com.shihab.diplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.format.DateFormat
import com.shihab.diplay.glance.CarPlayGlance
import com.shihab.diplay.host.R
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterGuidanceStripTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun arrivalText(epochSeconds: Long): String =
        app.getString(R.string.turn_card_info_arrival, DateFormat.getTimeFormat(app).format(Date(epochSeconds * 1000)))

    @Test fun aRouteWhosePhoneHasSentNoDistanceOrArrivalSaysNothing() {
        assertNull(ClusterGuidanceStrip.content(app, CarPlayGlance.Snapshot(connected = true, maneuverType = 1, road = "无名道路")))
    }

    @Test fun aClosedSessionSaysNothingEvenWithLeftoverNumbers() {
        val snapshot = CarPlayGlance.Snapshot(
            connected = false,
            arrivalEpochSeconds = 1_800_000_000,
            remainingSeconds = 180,
            remainingMeters = 938,
        )
        assertNull(ClusterGuidanceStrip.content(app, snapshot))
    }

    @Test fun arrivalDurationAndDistanceAreSpeltLikeTheFactoryStrip() {
        val snapshot = CarPlayGlance.Snapshot(
            connected = true,
            maneuverType = 1,
            arrivalEpochSeconds = 1_800_000_000,
            remainingSeconds = 180,
            remainingMeters = 938,
        )
        val line = ClusterGuidanceStrip.content(app, snapshot)!!
        assertEquals(arrivalText(1_800_000_000), line.arrival)
        assertEquals("3 min · 938 m", line.detail)
    }

    @Test fun aPhoneThatSendsOnlySomeOfTheNumbersStillGetsAStrip() {
        val onlyDistance = ClusterGuidanceStrip.content(app, CarPlayGlance.Snapshot(connected = true, remainingMeters = 1_240))!!
        assertEquals("1.2 km", onlyDistance.text)
        assertEquals("", onlyDistance.arrival)

        val onlyArrival = ClusterGuidanceStrip.content(app, CarPlayGlance.Snapshot(connected = true, arrivalEpochSeconds = 1_800_000_000))!!
        assertEquals(arrivalText(1_800_000_000), onlyArrival.text)
    }

    @Test fun longRoutesCountInHoursAndKilometres() {
        val line = ClusterGuidanceStrip.content(
            app,
            CarPlayGlance.Snapshot(connected = true, remainingSeconds = 5_400, remainingMeters = 120_000),
        )!!
        assertEquals("1 h 30 min · 120 km", line.text)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theStripDrawsInTheBottomRightCornerAndNothingWithoutARoute() {
        val view = ClusterGuidanceStripView(app)
        view.layout(0, 0, PANEL_WIDTH, PANEL_HEIGHT)
        val blank = render(view)
        assertTrue("no route draws nothing", blank.isBlank())

        view.content = ClusterGuidanceStrip.Line("22:34 到达", "3 min · 938 m")
        val drawn = render(view)
        assertTrue("the strip is painted", !drawn.isBlank())
        assertTrue("the strip stays out of the upper half", drawn.topHalfIsBlank())
        assertTrue("the strip stays in the right part of the panel", drawn.leftHalfIsBlank())
    }

    private fun render(view: ClusterGuidanceStripView): Bitmap {
        val bitmap = Bitmap.createBitmap(PANEL_WIDTH, PANEL_HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun Bitmap.isBlank(): Boolean = (0 until height step 8).all { y ->
        (0 until width step 8).none { x -> getPixel(x, y) != 0 }
    }

    private fun Bitmap.topHalfIsBlank(): Boolean = (0 until height / 2 step 8).all { y ->
        (0 until width step 8).none { x -> getPixel(x, y) != 0 }
    }

    private fun Bitmap.leftHalfIsBlank(): Boolean = (0 until height step 8).all { y ->
        (0 until width / 2 step 8).none { x -> getPixel(x, y) != 0 }
    }

    private companion object {
        const val PANEL_WIDTH = 1920
        const val PANEL_HEIGHT = 720
    }
}
