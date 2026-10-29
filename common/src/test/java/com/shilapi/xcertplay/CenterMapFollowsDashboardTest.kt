package com.shilapi.xcertplay

import android.os.Handler
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
class CenterMapFollowsDashboardTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
    }

    @Test fun defaultKeepsHomeScreenMapInSync() {
        assertTrue(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
    }

    @Test fun separateDisplayCanBeSaved() {
        AirPlayPersistence.saveCenterMapFollowsDashboard(context, false)
        assertFalse(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
        AirPlayPersistence.saveCenterMapFollowsDashboard(context, true)
        assertTrue(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
    }

    @Test fun navigationSettingsEnableAndDisableTheDesktopMap() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ShadowSettings.setCanDrawOverlays(true)
        val card = LinearLayout(activity)
        activity.javaClass.getDeclaredMethod("ecarxNavigationOutputs", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, card)
        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
        }
        val toggle = views(card).filterIsInstance<Switch>().single {
            it.contentDescription == activity.getString(R.string.center_map_follows_dashboard)
        }
        assertFalse(toggle.isChecked)
        toggle.isChecked = true
        assertTrue(AirPlayPersistence.loadCenterMapOverlay(context))
        assertTrue(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
        toggle.isChecked = false
        assertFalse(AirPlayPersistence.loadCenterMapOverlay(context))
        assertFalse(AirPlayPersistence.loadCenterMapFollowsDashboard(context))
        CenterMapOverlay.onDiPlayScreenShown()
        activity.finish()
    }

    @Test fun liveClusterStreamShowsTheCardWithoutTheObsoleteClusterPreference() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val sink = mock(AndroidMediaSink::class.java)
        ReflectionHelpers.setField(host, "sink", sink)
        ShadowSettings.setCanDrawOverlays(true)
        AirPlayPersistence.saveCenterMapOverlay(context, true)
        AirPlayPersistence.saveCenterMapAutoHide(context, false)
        val show = host.javaClass.getDeclaredMethod("showCenterMap").apply { isAccessible = true }
        val streams = ReflectionHelpers.getField<MutableSet<Int>>(host, "activeScreenStreamTypes")
        try {
            assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
            show.invoke(host)
            assertFalse("No blank card before the phone starts its map stream", CenterMapOverlay.shown)
            streams.add(111)
            show.invoke(host)
            assertTrue(CenterMapOverlay.shown)
            val mirror = ReflectionHelpers.getField<(String, Surface?) -> Unit>(host, "mirrorSink")
            val surface = mock(Surface::class.java)
            mirror(MapMirrors.CARD, surface)
            verify(sink).setMirrorSurface(111, MapMirrors.CARD, surface)
            CenterMapOverlay.hide()
            AirPlayPersistence.saveCenterMapFollowsDashboard(context, false)
            show.invoke(host)
            assertFalse("Disabling sync keeps the desktop card hidden", CenterMapOverlay.shown)
            assertEquals(setOf(111), streams)
        } finally {
            CenterMapOverlay.onDiPlayScreenShown()
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
    }
}
