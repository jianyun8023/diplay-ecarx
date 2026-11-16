package com.shihab.diplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contracts the Rust wired teardown release order/branch through the real native bridge. */
class WiredTeardownPlanContractTest {
    private fun pack(vararg actions: Int): Long {
        var p = 0L
        actions.forEachIndexed { i, a -> p = p or ((a.toLong() + 1) shl (4 * i)) }
        return p
    }

    @Test
    fun catchPlanClosesCsmThenMuxThenNcmOrDetachesVpn() {
        assertEquals(
            pack(NativeWiredStack.TD_CLOSE_CSM, NativeWiredStack.TD_CLOSE_MUX, NativeWiredStack.TD_CLOSE_NCM_CATCH),
            NativeWiredStack.teardownPlan(NativeWiredStack.TEARDOWN_CATCH, 2),
        )
        assertEquals(
            pack(NativeWiredStack.TD_CLOSE_CSM, NativeWiredStack.TD_CLOSE_MUX, NativeWiredStack.TD_DETACH_VPN),
            NativeWiredStack.teardownPlan(NativeWiredStack.TEARDOWN_CATCH, 0),
        )
    }

    @Test
    fun finallyPlanReflectsStaleAndOwnershipFacts() {
        assertEquals(
            pack(NativeWiredStack.TD_TERMINATE_WATCHDOG),
            NativeWiredStack.teardownPlan(NativeWiredStack.TEARDOWN_FINALLY, 0),
        )
        assertEquals(
            pack(
                NativeWiredStack.TD_TERMINATE_WATCHDOG,
                NativeWiredStack.TD_CLOSE_OWNED_IF_STALE,
                NativeWiredStack.TD_CLOSE_USB_SESSION,
                NativeWiredStack.TD_CLOSE_NCM_FINALLY,
            ),
            NativeWiredStack.teardownPlan(NativeWiredStack.TEARDOWN_FINALLY, 1 or 2 or 4),
        )
    }
}
