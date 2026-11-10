package com.shilapi.xcertplay

import android.os.Looper
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

internal fun awaitSettingsWork(done: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!done() && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        Thread.sleep(5)
    }
    assertTrue("Settings background work did not finish", done())
}

internal fun DiPlayActivity.settingsIndexForTest(): List<DiPlayActivity.SettingsSearchResult> {
    val index = lifecycleScope.async { buildSettingsSearchIndex() }
    awaitSettingsWork { index.isCompleted }
    return runBlocking { index.await() }
}
