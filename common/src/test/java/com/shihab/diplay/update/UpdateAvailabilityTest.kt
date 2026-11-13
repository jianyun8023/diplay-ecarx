package com.shihab.diplay.update

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 35])
class UpdateAvailabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(context)

    @Test fun onlyTheReleasePackageUpdatesItself() {
        assertTrue(UpdateAvailability.enabled("com.shihab.diplay"))
        assertFalse(UpdateAvailability.enabled("com.shihab.diplay.hudtest"))
        // The test application is not the release package.
        assertFalse(UpdateAvailability.enabled(context))
    }

    @Test fun nonReleasePackageHidesCachedReleasesAndBackgroundChecks() {
        UpdateAvailability.save(context, release("v137"))
        assertNull(UpdateAvailability.available(context, 136))
        UpdateAvailability.saveBackgroundChecksEnabled(context, true)
        assertFalse(UpdateAvailability.backgroundChecksEnabled(context))
    }

    @Test fun cachedReleaseShowsOnlyWhileItIsNewer() {
        val release = release("v137")
        UpdateAvailability.save(context, release)
        assertEquals(release, UpdateAvailability.cached(context, 136))
        // Installing it makes the cached entry stale, and it is dropped.
        assertNull(UpdateAvailability.cached(context, 137))
        assertNull(UpdateAvailability.cached(context, 136))
    }

    @Test fun cachedReleaseKeepsItsNotes() {
        UpdateAvailability.save(context, release("v137").copy(notes = "- Fix reconnect"))
        assertEquals("- Fix reconnect", UpdateAvailability.cached(context, 136)?.notes)
    }

    @Test fun mirrorChoiceDrivesTheRoutes() {
        assertEquals(UpdateMirror.AUTO, UpdateAvailability.mirror(context))
        UpdateAvailability.recordWorkingRoute(context, "https://ghfast.top/")
        assertEquals("https://ghfast.top/", UpdateAvailability.routes(context).first())

        UpdateAvailability.saveMirror(context, "https://mirror.example/")
        assertEquals(listOf("https://mirror.example/"), UpdateAvailability.routes(context))
        // A custom prefix stays offered after the driver goes back to automatic.
        UpdateAvailability.saveMirror(context, UpdateMirror.AUTO)
        assertEquals("https://mirror.example/", UpdateAvailability.customMirror(context))
        assertEquals("https://mirror.example/", UpdateAvailability.routes(context).last())
    }

    @Test fun reportShowsStateWithRelativeAges() {
        assertEquals(
            "Update check: background=off; source=auto (last working none); lastSuccess=never; cachedRelease=none; lastResult=none (never); lastInstall=none (never)",
            UpdateAvailability.report(context, 10_000_000L),
        )
        UpdateAvailability.save(context, release("v137"))
        UpdateAvailability.recordAttempt(context, 10_000_000L - 5 * 60_000)
        UpdateAvailability.recordResult(context, 10_000_000L - 60_000, "no validated network (attempt 2)")
        UpdateAvailability.recordInstall(context, 10_000_000L - 2 * 60_000, "chooser uri=public installers=2")
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        assertEquals(
            "Update check: background=off; source=auto (last working none); lastSuccess=5 min ago; cachedRelease=v137; " +
                "lastResult=no validated network (attempt 2) (1 min ago); " +
                "lastInstall=chooser uri=public installers=2 (2 min ago)",
            UpdateAvailability.report(context, 10_000_000L),
        )
    }

    @Test fun checksAtMostOncePerDayButRecoversFromAClockReset() {
        val now = 10L * UpdateAvailability.CHECK_INTERVAL_MILLIS
        assertTrue(UpdateAvailability.shouldCheck(context, now))
        UpdateAvailability.recordAttempt(context, now)

        assertFalse(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS - 1))
        assertTrue(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS))
        assertTrue(UpdateAvailability.shouldCheck(context, now - 1))
    }

    @Test fun installedNotesBelongToTheBuildThatReadThem() {
        assertNull(UpdateAvailability.installedNotes(context, 143))
        UpdateAvailability.saveInstalledNotes(context, release("v143").copy(notes = "• Fix reconnect"))
        assertEquals("• Fix reconnect", UpdateAvailability.installedNotes(context, 143))
        // After the upgrade they are the old build's notes, so they are read again.
        assertNull(UpdateAvailability.installedNotes(context, 144))
        // Clearing the offered update keeps them.
        UpdateAvailability.clear(context)
        assertEquals("• Fix reconnect", UpdateAvailability.installedNotes(context, 143))
    }

    private fun release(tag: String) = UpdateRelease(
        tagName = tag,
        apkName = "DiPlay-$tag.apk",
        apkUrl = "https://example.com/DiPlay-$tag.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )
}
