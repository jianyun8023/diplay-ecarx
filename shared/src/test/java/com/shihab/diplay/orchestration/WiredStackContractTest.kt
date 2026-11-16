package com.shihab.diplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contracts the Rust wired-stack owner's retry decision through the real native bridge. */
class WiredStackContractTest {
    @Test
    fun onlyASavedRecordRejectedAsInvalidPairOrHostIdRetries() {
        assertEquals(1, NativeWiredStack.carkitErrorAction(hasSaved = true, rejection = 1))
        assertEquals(1, NativeWiredStack.carkitErrorAction(hasSaved = true, rejection = 2))
        assertEquals(0, NativeWiredStack.carkitErrorAction(hasSaved = true, rejection = 0))
        assertEquals(0, NativeWiredStack.carkitErrorAction(hasSaved = true, rejection = 99))
        assertEquals(0, NativeWiredStack.carkitErrorAction(hasSaved = false, rejection = 1))
        assertEquals(0, NativeWiredStack.carkitErrorAction(hasSaved = false, rejection = 0))
    }
}
