package com.shihab.diplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun tagIsTheVersionCode() {
        assertEquals(134L, UpdateVersion.parse("v134"))
        assertEquals(134L, UpdateVersion.parse("134"))
    }

    @Test
    fun higherBuildIsAnUpdate() {
        assertTrue(UpdateVersion.isNewer("v137", 136))
    }

    @Test
    fun sameBuildIsNotAnUpdate() {
        // Android would accept it, but it is what is already installed.
        assertFalse(UpdateVersion.isNewer("v136", 136))
    }

    @Test
    fun lowerBuildIsIgnored() {
        // Android refuses a lower versionCode over an installed package.
        assertFalse(UpdateVersion.isNewer("v134", 136))
    }

    @Test
    fun upstreamAndFreeFormTagsAreNeverAnUpdate() {
        assertNull(UpdateVersion.parse("v0.2.22"))
        assertNull(UpdateVersion.parse("v137-beta"))
        assertFalse(UpdateVersion.isNewer("v0.2.22", 1))
        assertFalse(UpdateVersion.isNewer("public-preview", 1))
    }
}
