package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaOutputBackendSettingsTest {
    @Test fun defaultsToUniWithoutChangingMasterSwitch() {
        MediaOutputBackendSettings.releaseDimBarrier()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay_media_output", 0).edit().clear().commit()
        EcarxOutputSettings.setMediaEnabled(context, false)
        assertEquals(MediaOutputBackendSettings.Backend.UNI_MEDIACENTER, MediaOutputBackendSettings.load(context))
        assertFalse(EcarxOutputSettings.mediaEnabled(context))
    }
    @Test fun choiceChangesNextSessionOnlyAndOldOwnerCannotClearNewOwner() {
        MediaOutputBackendSettings.releaseDimBarrier()
        val context = RuntimeEnvironment.getApplication(); val old = Any(); val next = Any()
        MediaOutputBackendSettings.save(context, MediaOutputBackendSettings.Backend.DIM)
        MediaOutputBackendSettings.begin(old, MediaOutputBackendSettings.Backend.DIM)
        MediaOutputBackendSettings.save(context, MediaOutputBackendSettings.Backend.UNI_MEDIACENTER)
        assertTrue(MediaOutputBackendSettings.dimAllowed(context))
        MediaOutputBackendSettings.begin(next, MediaOutputBackendSettings.Backend.UNI_MEDIACENTER)
        MediaOutputBackendSettings.end(old)
        assertFalse(MediaOutputBackendSettings.dimAllowed(context))
        MediaOutputBackendSettings.end(next)
    }
}
