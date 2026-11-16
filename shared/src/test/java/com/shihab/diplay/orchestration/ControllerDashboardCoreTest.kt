package com.shihab.diplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ControllerDashboardCoreTest {
    @Test fun agreesWithIndependentControllerReference() = ControllerDashboardContract.verifyTransitions()

    @Test fun routeReadsDoNotWaitForTheMutationMonitor() {
        val core = ControllerDashboardCore(null)
        val session = Any()
        core.setPhysicalVisible(true, false)
        val finished = CountDownLatch(1)
        var epoch: Int? = null
        val reader = synchronized(core) {
            thread {
                epoch = core.routeEpoch(session, 1, "maps:/car/instrumentcluster/map", false)
                finished.countDown()
            }.also { assertTrue(finished.await(1, TimeUnit.SECONDS)) }
        }
        reader.join(3_000)
        assertNotNull(epoch)
    }

    @Test fun physicalOutputUpdatesCannotOverwriteAcceptedSelection() {
        val core = ControllerDashboardCore(null)
        val start = CountDownLatch(1)
        val writer = thread {
            start.await()
            repeat(500) { core.commitSelection("content:$it", true, true) }
        }
        val surface = thread {
            start.await()
            repeat(500) { core.setPhysicalVisible(it % 2 == 0, false) }
        }
        start.countDown()
        writer.join(3_000); surface.join(3_000)
        assertFalse(writer.isAlive); assertFalse(surface.isAlive)
        assertEquals("content:499", core.restorePlan(Any(), 1)?.url)
    }
}
