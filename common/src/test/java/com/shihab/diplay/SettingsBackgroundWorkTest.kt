package com.shihab.diplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
class SettingsBackgroundWorkTest {
    @Test fun blockedDecoderQueryLeavesMainResponsiveAndAddsItsControlInOrder() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        activity.selectedMtkDecoderName = {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "OMX.MTK.VIDEO.DECODER.AVC"
        }
        try {
            ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
            val panel = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, panel))
            var mainResponded = false
            Handler(Looper.getMainLooper()).post { mainResponded = true }
            awaitSettingsWork { mainResponded && entered.count == 0L }
            release.countDown()
            val title = activity.getString(R.string.settings_mtk_decoder_tuning)
            awaitSettingsWork { texts(panel).any { it.contentDescription == title } }
            assertTrue(texts(panel).any { it.text.contains("OMX.MTK.VIDEO.DECODER.AVC") })
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun indexYieldsToMainAndKeepsTheLiveSettingsPage() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.OVERVIEW)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<View>(android.R.id.content)
            val readiness = ReflectionHelpers.getField<View>(activity, "readinessCard")
            var mainResponded = false
            Handler(Looper.getMainLooper()).post { mainResponded = true }
            val index = activity.settingsIndexForTest()
            assertTrue(mainResponded)
            assertSame(root, activity.findViewById(android.R.id.content))
            assertSame(readiness, ReflectionHelpers.getField(activity, "readinessCard"))
            assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
            assertTrue(index.any { it.title == activity.getString(R.string.carplay_night_mode) })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun diagnosticReportRejectsMainThreadGeneration() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        assertThrows(IllegalStateException::class.java) {
            diagnosticReportText(activity.applicationContext, setupReady = true)
        }
    }

    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
}
