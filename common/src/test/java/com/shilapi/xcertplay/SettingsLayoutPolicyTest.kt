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

    @Test fun railFitsTheHeightItGets() {
        val count = SettingsCategory.entries.size
        // Tall enough: the normal spacing.
        assertEquals(SettingsLayoutPolicy.RailSpacing(52, 8), SettingsLayoutPolicy.railSpacing(700f, count))
        // The 1920x1080 head unit at 150%: 944 px above its dock leaves the rail 794 px (529 dp).
        val car = SettingsLayoutPolicy.railSpacing(529f, count)
        assertEquals(SettingsLayoutPolicy.RailSpacing(47, 2), car)
        assertTrue(count * car.itemDp + (count - 1) * car.gapDp + SettingsLayoutPolicy.RAIL_FRAME_DP <= 529)
        // Gaps give way before destinations.
        assertEquals(SettingsLayoutPolicy.RailSpacing(52, 7), SettingsLayoutPolicy.railSpacing(620f, count))
        // Too short even at the minimums: keep them and scroll.
        assertEquals(SettingsLayoutPolicy.RailSpacing(40, 2), SettingsLayoutPolicy.railSpacing(300f, count))
    }

    @Test fun everySettingsSectionHasExactlyOneDestination() {
        assertEquals(SettingsCategory.entries.toSet(), SettingsInformationArchitecture.sectionsByCategory.keys)
        val assignments = SettingsInformationArchitecture.sectionsByCategory.values.flatten()
        assertEquals(SettingsSection.entries.toSet(), assignments.toSet())
        assertEquals(assignments.toSet().size, assignments.size)
    }
}
