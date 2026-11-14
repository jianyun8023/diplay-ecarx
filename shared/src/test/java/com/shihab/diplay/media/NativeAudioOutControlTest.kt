package com.shihab.diplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NativeAudioOutControlTest {
    @Test fun closedOutputCannotReportStartOrPauseSuccess() {
        val constructor = NativeAudioOut::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java,
        ).apply { isAccessible = true }
        val out = constructor.newInstance(0L, 4, null)
        val playing = NativeAudioOut::class.java.getDeclaredField("playing").apply { isAccessible = true }
        for (wasPlaying in listOf(false, true)) {
            playing.setBoolean(out, wasPlaying)
            for (action in listOf<() -> Unit>({ out.play() }, { out.pause() }, { out.flush() })) {
                try {
                    action()
                    fail("Closed output must not report success")
                } catch (_: IllegalStateException) {
                    assertEquals(wasPlaying, playing.getBoolean(out))
                }
            }
        }
    }

    @Test fun successAllowsTheCallerToCommitPlaybackState() {
        for (operation in listOf("start", "pause", "flush")) {
            requireAudioControlSuccess(operation, 0)
        }
    }

    @Test fun failureThrowsBeforeTheCallerCanCommitPlaybackState() {
        for (operation in listOf("start", "pause", "flush")) {
            var schedulerCommits = 0
            try {
                requireAudioControlSuccess(operation, -899)
                schedulerCommits++
                fail("AAudio failure must not be reported as success")
            } catch (error: IllegalStateException) {
                assertEquals("AAudio $operation failed: -899", error.message)
            }
            assertEquals(0, schedulerCommits)
        }
    }
}
