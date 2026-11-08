package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UltraExperimentPersistenceTest {
    @Test fun onlyDebugBuildsReadTheOptInAndDeletingItRestoresNormalMode() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "ultra-experiment.txt")
        file.writeText("THEME0001:7:19.0\nCenter_Display\nCluster_Display\n")
        val simulation = File(context.filesDir, "ultra-bench.enabled").apply { writeText("") }
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertNull(AirPlayPersistence.loadUltraExperiment(context))
        assertNull(AirPlayPersistence.loadSimulatedVehicle(context))
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertEquals("THEME0001", AirPlayPersistence.loadUltraExperiment(context)?.asset?.assetId)
        assertNotNull(AirPlayPersistence.loadSimulatedVehicle(context))
        assertTrue(simulation.delete())
        assertNull(AirPlayPersistence.loadSimulatedVehicle(context))
        file.writeText("invalid")
        assertNull(AirPlayPersistence.loadUltraExperiment(context))
        assertTrue(file.delete())
        assertNull(AirPlayPersistence.loadUltraExperiment(context))
    }
}
