package com.shihab.diplay

import android.app.ActivityManager
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppTask
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayExitTest {
    @Suppress("DEPRECATION") // API 28 exposes the task ID as persistentId.
    @Test fun exitRemovesBothTheProjectionAndLauncherTasks() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val projection = ShadowAppTask.newInstance()
        val launcher = ShadowAppTask.newInstance()
        shadowOf(projection).setTaskInfo(ActivityManager.RecentTaskInfo().apply { persistentId = host.taskId })
        shadowOf(launcher).setTaskInfo(ActivityManager.RecentTaskInfo().apply { persistentId = host.taskId + 1 })
        shadowOf(host.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(projection, launcher))

        ReflectionHelpers.callInstanceMethod<Unit>(host, "exitApplication")

        assertTrue(shadowOf(projection).isFinishedAndRemoved)
        assertTrue(shadowOf(launcher).isFinishedAndRemoved)
        val teardown = ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor")
        assertTrue(teardown.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }
}
