package com.shihab.diplay

import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BroadcastHoldTest {
    private var finishes = 0

    @Test fun releaseFinishesOnceAndCancelsTheTimeout() {
        val hold = BroadcastHold(9_000L) { finishes++ }
        hold.release()
        hold.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, finishes)
    }

    @Test fun timeoutFinishesAChainThatNeverSettlesAndLaterReleaseIsIgnored() {
        val hold = BroadcastHold(9_000L) { finishes++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_999))
        assertEquals(0, finishes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, finishes)
        hold.release()
        assertEquals(1, finishes)
    }

    @Test fun aFailingFinishDoesNotPropagate() {
        val hold = BroadcastHold(9_000L) { finishes++; error("already finished") }
        hold.release()
        assertEquals(1, finishes)
    }
}
