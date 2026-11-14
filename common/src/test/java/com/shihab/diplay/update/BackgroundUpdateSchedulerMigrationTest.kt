package com.shihab.diplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #100 package rename moved BackgroundUpdateWorker from com.shilapi.xcertplay.update to
 * com.shihab.diplay.update. WorkManager persists the worker class name with each work row, so a row
 * written by a pre-rename build keeps pointing at the removed class and Existing*Policy.KEEP would
 * preserve it forever; neither check would ever run again. The scheduler must retire the pre-rename
 * unique names and enqueue a fresh generation so the rows are rebuilt with the current class.
 */
class BackgroundUpdateSchedulerMigrationTest {
    @Test fun enabledPlanRetiresPreRenameNamesButKeepsCurrentGeneration() {
        val plan = BackgroundUpdateScheduler.plan(enabled = true)

        assertTrue("enabled builds must still schedule the checks", plan.enqueue)
        assertEquals(
            setOf("diplay-periodic-update-check", "diplay-open-update-check"),
            plan.cancel.toSet(),
        )
        // The current generation is never cancelled, so KEEP does not discard live scheduled work.
        assertFalse(BackgroundUpdateScheduler.PERIODIC_WORK in plan.cancel)
        assertFalse(BackgroundUpdateScheduler.OPEN_WORK in plan.cancel)
    }

    @Test fun currentGenerationNamesDoNotCollideWithPreRenameNames() {
        // If the names matched, enqueueUnique*Work(KEEP) would keep the stale pre-rename row and the
        // worker would never be rebuilt with the current class.
        assertNotEquals("diplay-periodic-update-check", BackgroundUpdateScheduler.PERIODIC_WORK)
        assertNotEquals("diplay-open-update-check", BackgroundUpdateScheduler.OPEN_WORK)
        assertFalse(BackgroundUpdateScheduler.PERIODIC_WORK in BackgroundUpdateScheduler.RETIRED_WORK)
        assertFalse(BackgroundUpdateScheduler.OPEN_WORK in BackgroundUpdateScheduler.RETIRED_WORK)
    }

    @Test fun disabledPlanCancelsEveryGenerationAndEnqueuesNothing() {
        val plan = BackgroundUpdateScheduler.plan(enabled = false)

        assertFalse(plan.enqueue)
        assertEquals(
            setOf(
                "diplay-periodic-update-check",
                "diplay-open-update-check",
                BackgroundUpdateScheduler.PERIODIC_WORK,
                BackgroundUpdateScheduler.OPEN_WORK,
            ),
            plan.cancel.toSet(),
        )
    }
}
