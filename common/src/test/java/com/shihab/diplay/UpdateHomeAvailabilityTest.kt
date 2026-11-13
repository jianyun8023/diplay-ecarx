package com.shihab.diplay

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shihab.diplay.host.R
import com.shihab.diplay.update.UpdateAvailability
import com.shihab.diplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
class UpdateHomeAvailabilityTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(application)

    @Test fun cachedUpstreamUpdateDoesNotAppearOnHome() {
        val release = release()
        UpdateAvailability.save(application, release)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val text = activity.getString(R.string.update_available_home)
        assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == text })
        controller.pause().stop().destroy()
    }

    @Test fun cachedUpstreamUpdateStaysHiddenWhenHomeReturns() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val text = activity.getString(R.string.update_available_home)
        fun shown() = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == text }
        assertFalse(shown())

        // A release cached before checks were disabled must stay hidden on resume.
        controller.pause().stop()
        UpdateAvailability.save(application, release())
        controller.start().resume()

        assertFalse(shown())
        controller.pause().stop().destroy()
    }

    private fun release() = UpdateRelease(
        tagName = "v99.0.0",
        apkName = "DiPlay.apk",
        apkUrl = "https://example.com/DiPlay.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
