package com.shilapi.xcertplay

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.MediaOutputBackendSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaOutputBackendControlsTest {
    private class Backend : MediaOutputBackendControls.Backend {
        var value = MediaOutputBackendSettings.Backend.UNI_MEDIACENTER
        var saves = 0
        var probes = 0
        var queued = 0
        var accept = true
        var work: (() -> Unit)? = null
        override fun load() = value
        override fun save(value: MediaOutputBackendSettings.Backend) { this.value = value; saves++ }
        override fun diagnostics() = "IDLE"
        override fun probe(): String { probes++; return "READ_ONLY permission=false" }
        override fun executeProbe(block: () -> Unit): Boolean {
            if (!accept) return false
            queued++; work = block; return true
        }
    }
    private class Fixture {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply {
            setTheme(android.R.style.Theme_Material_NoActionBar)
        }
        val backend = Backend()
        var reconnect = 0
        val controls = MediaOutputBackendControls(activity, { reconnect++ }, backend)
        var root = controls.build()
        fun button(id: Int) = views(root).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(id))
        }
        fun click(id: Int) { button(id).performClick(); shadowOf(Looper.getMainLooper()).idle() }
        fun choose(index: Int) {
            click(R.string.settings_media_backend_choice)
            val list = ShadowAlertDialog.getLatestAlertDialog().listView
            list.performItemClick(list.getChildAt(index), index, index.toLong())
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun finishProbe() { checkNotNull(backend.work).invoke(); shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun selectionOffersUniDimAndStandardControls() {
        val f = Fixture()
        f.click(R.string.settings_media_backend_choice)
        val list = ShadowAlertDialog.getLatestAlertDialog().listView
        assertEquals(3, list.adapter.count)
        assertEquals(f.activity.getString(R.string.settings_media_backend_uni), list.adapter.getItem(0))
        assertEquals(f.activity.getString(R.string.settings_media_backend_dim), list.adapter.getItem(1))
        assertEquals(f.activity.getString(R.string.settings_media_backend_standard), list.adapter.getItem(2))
        f.controls.close()
    }
    @Test fun choosingDimOnlySavesAndMarksNextConnection() {
        val f = Fixture(); f.choose(1)
        assertEquals(MediaOutputBackendSettings.Backend.DIM, f.backend.value)
        assertEquals(1, f.backend.saves); assertEquals(1, f.reconnect)
        assertEquals(0, f.backend.probes); assertEquals(0, f.backend.queued)
        f.controls.close()
    }
    @Test fun choosingCurrentBackendDoesNotRequireReconnect() {
        val f = Fixture(); f.choose(0)
        assertEquals(0, f.backend.saves); assertEquals(0, f.reconnect)
        f.controls.close()
    }
    @Test fun choosingStandardOnlySavesAndMarksNextConnection() {
        val f = Fixture(); f.choose(2)
        assertEquals(MediaOutputBackendSettings.Backend.STANDARD, f.backend.value)
        assertEquals(1, f.backend.saves); assertEquals(1, f.reconnect)
        assertEquals(0, f.backend.probes); assertEquals(0, f.backend.queued)
        f.choose(2)
        assertEquals(1, f.backend.saves); assertEquals(1, f.reconnect)
        f.controls.close()
    }
    @Test fun renderAndRefreshDoNotProbeOrChangePreferences() {
        val f = Fixture(); f.root = f.controls.build(); f.click(R.string.settings_media_backend_refresh)
        assertEquals(0, f.backend.saves); assertEquals(0, f.backend.probes); assertEquals(0, f.backend.queued)
        f.controls.close()
    }
    @Test fun explicitReadOnlyProbeRunsOnceOffTheClickPath() {
        val f = Fixture(); f.click(R.string.settings_media_backend_probe)
        assertEquals(1, f.backend.queued); assertEquals(0, f.backend.probes)
        assertFalse(f.button(R.string.settings_media_backend_probe).isEnabled)
        f.click(R.string.settings_media_backend_probe)
        assertEquals(1, f.backend.queued)
        f.finishProbe()
        assertEquals(1, f.backend.probes); assertEquals(0, f.backend.saves); assertEquals(0, f.reconnect)
        assertTrue(views(f.root).filterIsInstance<TextView>().any { it.text.contains("READ_ONLY permission=false") })
        f.controls.close()
    }
    @Test fun rejectedProbeDoesNotRemainBusyOrSaveAnything() {
        val f = Fixture(); f.backend.accept = false; f.click(R.string.settings_media_backend_probe)
        assertTrue(f.button(R.string.settings_media_backend_probe).isEnabled)
        assertEquals(0, f.backend.probes); assertEquals(0, f.backend.saves)
        f.backend.accept = true; f.click(R.string.settings_media_backend_probe)
        assertEquals(1, f.backend.queued); f.finishProbe(); f.controls.close()
    }
    @Test fun closingViewIgnoresLateProbeResultAndDoesNotChangeBackend() {
        val f = Fixture(); f.click(R.string.settings_media_backend_probe); f.controls.close(); f.finishProbe()
        assertFalse(views(f.root).filterIsInstance<TextView>().any { it.text.contains("READ_ONLY permission=false") })
        assertEquals(0, f.backend.saves); assertEquals(0, f.reconnect)
    }
    @Test fun backendCardHasOnlyOneAdvancedDestination() {
        val destinations = SettingsInformationArchitecture.sectionsByCategory.filterValues {
            SettingsSection.MEDIA_OUTPUT_BACKEND in it
        }.keys
        assertEquals(setOf(SettingsCategory.ADVANCED), destinations)
    }
    companion object {
        private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    }
}
