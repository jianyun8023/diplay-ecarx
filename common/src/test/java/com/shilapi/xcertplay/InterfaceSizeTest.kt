package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class InterfaceSizeTest {
    private fun screen() = Configuration().apply {
        screenWidthDp = 1920
        screenHeightDp = 1080
        smallestScreenWidthDp = 1080
        densityDpi = 160
    }

    @Test
    fun autoScalesTheCurrentHeadUnitToTheTargetWidth() {
        val override = InterfaceSize.override(screen(), InterfaceSize.AUTO)!!

        assertEquals(720, override.smallestScreenWidthDp)
        assertEquals(1280, override.screenWidthDp)
        assertEquals(240, override.densityDpi)
    }

    @Test
    fun originalSizeLeavesTheCurrentHeadUnitAlone() {
        assertNull(InterfaceSize.override(screen(), 100))
    }

    @Test
    fun aFixedChoiceScalesBySelf() {
        val override = InterfaceSize.override(screen(), 150)!!

        assertEquals(240, override.densityDpi)
        assertEquals(1280, override.screenWidthDp)
        assertEquals(720, override.smallestScreenWidthDp)
    }

    @Test
    fun enforceRewritesMetricsThatAFrameworkPutBack() {
        val resources = org.robolectric.RuntimeEnvironment.getApplication().resources
        val override = InterfaceSize.override(screen(), InterfaceSize.AUTO)!!

        assertEquals(true, InterfaceSize.enforce(resources, override))
        assertEquals(240, resources.displayMetrics.densityDpi)
        assertEquals(240 / 160f, resources.displayMetrics.density)
        assertEquals(720, resources.configuration.smallestScreenWidthDp)
        assertEquals(false, InterfaceSize.enforce(resources, override))
    }

    @Test
    fun enforceAlsoRewritesDimensionsWhenOnlyTheyWereReset() {
        val resources = org.robolectric.RuntimeEnvironment.getApplication().resources
        val override = InterfaceSize.override(screen(), InterfaceSize.AUTO)!!
        InterfaceSize.enforce(resources, override)
        resources.configuration.screenWidthDp = 1920

        assertEquals(true, InterfaceSize.enforce(resources, override))
        assertEquals(1280, resources.configuration.screenWidthDp)
    }

    @Test
    fun onlyADensityChangeNeedsANewActivity() {
        val applied = InterfaceSize.override(screen(), 150)
        assertEquals(false, InterfaceSize.needsRecreate(applied, InterfaceSize.override(screen(), 150)))
        assertEquals(true, InterfaceSize.needsRecreate(applied, InterfaceSize.override(screen(), 125)))
        assertEquals(true, InterfaceSize.needsRecreate(null, applied))
        assertEquals(false, InterfaceSize.needsRecreate(null, null))
    }

    @Test
    fun contextOverrideKeepsTheCurrentHeadUnitDimensionsFresh() {
        val scaled = InterfaceSize.override(screen(), 150)!!
        val reported = screen().apply { updateFrom(InterfaceSize.contextOverride(scaled)) }
        assertEquals(240, reported.densityDpi)
        assertEquals(1920, reported.screenWidthDp)
        assertEquals(1080, reported.screenHeightDp)
        val next = InterfaceSize.configurationChange(reported, 160, 150)!!
        assertEquals(1280, next.screenWidthDp)
        assertEquals(720, next.screenHeightDp)
        assertEquals(false, InterfaceSize.needsRecreate(scaled, next))
    }

    @Test
    fun theOverrideKeepsTheBaseLanguage() {
        val base = screen().apply { setLocales(android.os.LocaleList(java.util.Locale.SIMPLIFIED_CHINESE)) }
        val override = InterfaceSize.override(base, InterfaceSize.AUTO)!!
        assertEquals("zh-CN", override.locales[0].toLanguageTag())
    }
}
