package com.shihab.diplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contracts the Rust wired bring-up order and abort branches through the real native bridge. */
class WiredBringUpContractTest {
    @Test
    fun orderAndAbortBranchesComeFromTheCore() {
        assertEquals(NativeWiredStack.BRING_UP_OPEN_MUX, NativeWiredStack.advance(NativeWiredStack.BRING_UP_BEGIN, 1))
        assertEquals(NativeWiredStack.BRING_UP_DONE, NativeWiredStack.advance(NativeWiredStack.BRING_UP_BEGIN, 0))
        assertEquals(NativeWiredStack.BRING_UP_ADOPT_MUX, NativeWiredStack.advance(NativeWiredStack.BRING_UP_OPEN_MUX, 0))
        assertEquals(NativeWiredStack.BRING_UP_LOAD_PAIR, NativeWiredStack.advance(NativeWiredStack.BRING_UP_ADOPT_MUX, 1))
        assertEquals(NativeWiredStack.BRING_UP_ABORT_CLOSE_MUX, NativeWiredStack.advance(NativeWiredStack.BRING_UP_ADOPT_MUX, 0))
        assertEquals(NativeWiredStack.BRING_UP_OPEN_CARKIT, NativeWiredStack.advance(NativeWiredStack.BRING_UP_LOAD_PAIR, 0))
        assertEquals(NativeWiredStack.BRING_UP_OPEN_CSM, NativeWiredStack.advance(NativeWiredStack.BRING_UP_OPEN_CARKIT, 0))
        assertEquals(NativeWiredStack.BRING_UP_ATTACH_VPN, NativeWiredStack.advance(NativeWiredStack.BRING_UP_OPEN_CSM, 1))
        assertEquals(NativeWiredStack.BRING_UP_ABORT_CLOSE_CSM, NativeWiredStack.advance(NativeWiredStack.BRING_UP_OPEN_CSM, 0))
        assertEquals(NativeWiredStack.BRING_UP_BUILD_ENDPOINT, NativeWiredStack.advance(NativeWiredStack.BRING_UP_ATTACH_VPN, 1))
        assertEquals(NativeWiredStack.BRING_UP_ABORT_DETACH_VPN, NativeWiredStack.advance(NativeWiredStack.BRING_UP_ATTACH_VPN, 0))
        assertEquals(NativeWiredStack.BRING_UP_RUN_CONTROL, NativeWiredStack.advance(NativeWiredStack.BRING_UP_BUILD_ENDPOINT, 0))
        assertEquals(NativeWiredStack.BRING_UP_DONE, NativeWiredStack.advance(NativeWiredStack.BRING_UP_RUN_CONTROL, 0))
    }
}
