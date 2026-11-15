package com.shihab.diplay.hud
import org.junit.Assert.*

internal object HudPublicationContract {
    fun nativeSnapshotsRetriesAndCache() {
        HudPublicationState().use { state ->
            state.startGuidance(true, false)
            assertEquals(ClusterMapIntent.SHOWN, state.mapIntent(false, true))
            state.acceptPresentation(true)
            assertEquals(2, state.presentationDue(false, true))
            assertEquals(0, state.presentationAttempts)
            state.presentationFinished(false, 2, false)
            assertEquals(1, state.presentationAttempts)
            assertEquals(0, state.presentationDue(false, true))
            assertEquals(2, state.presentationDue(false, true))
            state.presentationAttempts = 30
            state.presentationCountdown = 0
            assertEquals(10, state.presentationDue(false, true))
            val lanes = listOf(EcarxDimApi.Lane(1, 7), EcarxDimApi.Lane(2, 8))
            for (index in 1..40) state.cacheLanes(index, lanes, 1, 1)
            assertEquals(32, state.cachedLaneIndices.size)
            assertTrue(1 in state.cachedLaneIndices)
            assertNull(state.lanePlan(false, 1, true, true, 1, true).publish)
            state.hudActive = true
            val plan = state.lanePlan(false, 1, true, true, 1, true)
            assertEquals(lanes, plan.publish)
            state.lastLanePushed = plan.publish
            assertEquals(lanes, state.lastLanePushed)
            assertNull(state.lanePlan(false, 1, true, true, 1, true).publish)
            val guidance = EcarxDimApi.Guidance(1, 12, 1000, 99, 2, 3, 4, 5, "路\uD800名")
            assertTrue(state.guidanceChanged(false, guidance))
            state.lastPushed = guidance
            assertEquals(guidance, state.lastPushed)
            assertFalse(state.guidanceChanged(false, guidance))
            state.lastDestination = "终点\uD800"
            assertEquals("终点\uD800", state.lastDestination)
            assertFalse(state.destinationChanged("终点\uD800"))
            state.hudActive = true
            assertFalse(state.stopPlan(true, true, true).needed)
            state.hudOutputTouched = true
            assertTrue(state.stopPlan(true, true, true).needed)
            state.stopped()
            assertNull(state.lastPushed)
            assertFalse(state.hudActive)
            state.reset()
            assertEquals(0, state.cachedLaneIndices.size)
            assertEquals(ClusterMapIntent.NO_ROUTE, state.mapIntent(false, true))
        }
        val closed = HudPublicationState()
        closed.close(); closed.close()
        assertThrows(IllegalStateException::class.java) { closed.mapIntent(false, true) }
    }
}
