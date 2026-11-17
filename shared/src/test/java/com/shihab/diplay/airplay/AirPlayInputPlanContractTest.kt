package com.shihab.diplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contracts the Rust-owned input plans (Siri action sequence, key press/release bytes) via native. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirPlayInputPlanContractTest {
    @Test
    fun siriSequenceIsTheRequestThenReleaseActions() {
        assertArrayEquals(intArrayOf(2, 3), AirPlayCommands.siriSequence())
    }

    @Test
    fun indexPressReleaseIsTheIndexThenZero() {
        assertArrayEquals(byteArrayOf(5, 0), NativeMedia.hidIndexPressRelease(5))
        assertArrayEquals(byteArrayOf(0, 0), NativeMedia.hidIndexPressRelease(0))
    }
}
