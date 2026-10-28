package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutPolicyTest {
    @Test fun currentHeadUnitFitsTheRailAndOverviewColumns() {
        // 1920×1080 pixels at the app's automatic 150% interface scale.
        assertTrue(SettingsLayoutPolicy.isExpanded(1280, 720, 1f))
        assertTrue(SettingsLayoutPolicy.overviewHasTwoColumns(1280, 1f))
        assertEquals(240, SettingsLayoutPolicy.railWidthDp(1f))
    }

    @Test fun everySettingsSectionHasExactlyOneDestination() {
        assertEquals(SettingsCategory.entries.toSet(), SettingsInformationArchitecture.sectionsByCategory.keys)
        val assignments = SettingsInformationArchitecture.sectionsByCategory.values.flatten()
        assertEquals(SettingsSection.entries.toSet(), assignments.toSet())
        assertEquals(assignments.toSet().size, assignments.size)
    }
}
