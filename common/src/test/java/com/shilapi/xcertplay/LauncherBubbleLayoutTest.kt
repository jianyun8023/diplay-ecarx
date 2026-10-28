package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the bubble goes and what a touch means, without an Android window. */
class LauncherBubbleLayoutTest {

    @Test fun aBubbleIsAShareOfTheScreenHeightAndTheIconGivesTheWidth() {
        assertEquals(
            LauncherBubbleLayout.Box(189, 108, 1707, 486),
            LauncherBubbleLayout.initialBox(1920, 1080, 10, 1.75f, 1f),
        )
    }

    @Test fun aRememberedPlaceStaysOnTheScreen() {
        assertEquals(
            LauncherBubbleLayout.Box(200, 100, 0, 980),
            LauncherBubbleLayout.clamp(LauncherBubbleLayout.Box(200, 100, -50, 1200), 1920, 1080),
        )
        // A bubble larger than the screen still has a defined place.
        assertEquals(
            LauncherBubbleLayout.Box(2000, 1200, 0, 0),
            LauncherBubbleLayout.clamp(LauncherBubbleLayout.Box(2000, 1200, 100, 100), 1920, 1080),
        )
    }

    @Test fun theHoldPromptSitsAboveTheBubbleAndCentresOnIt() {
        assertEquals(
            LauncherBubbleLayout.Box(200, 40, 1720, 276),
            LauncherBubbleLayout.labelBox(LauncherBubbleLayout.Box(126, 72, 1770, 324), 200, 40, 8, 1920),
        )
    }

    @Test fun theHoldPromptGoesBelowWhenThereIsNoRoomAbove() {
        assertEquals(
            LauncherBubbleLayout.Box(200, 40, 63, 100),
            LauncherBubbleLayout.labelBox(LauncherBubbleLayout.Box(126, 72, 100, 20), 200, 40, 8, 1920),
        )
    }

    @Test fun aMoveIsOnlyADragPastTheSlop() {
        assertFalse(LauncherBubbleLayout.isDrag(6, 0, 6))
        assertFalse(LauncherBubbleLayout.isDrag(-6, 6, 6))
        assertTrue(LauncherBubbleLayout.isDrag(7, 0, 6))
        assertTrue(LauncherBubbleLayout.isDrag(0, -7, 6))
    }

    @Test fun theSizeSettingOnlyOffersItsOwnSteps() {
        assertEquals(listOf(8, 10, 12, 16), LauncherBubbleLayout.sizePercents)
        assertEquals(10, LauncherBubbleLayout.snapSizePercent(10))
        assertEquals(12, LauncherBubbleLayout.snapSizePercent(12))
        // A tie goes to the smaller step, and anything outside the list goes to either end.
        assertEquals(8, LauncherBubbleLayout.snapSizePercent(9))
        assertEquals(10, LauncherBubbleLayout.snapSizePercent(11))
        assertEquals(16, LauncherBubbleLayout.snapSizePercent(15))
        assertEquals(8, LauncherBubbleLayout.snapSizePercent(0))
        assertEquals(16, LauncherBubbleLayout.snapSizePercent(1000))
        assertEquals(8, LauncherBubbleLayout.snapSizePercent(-4))
    }
}
