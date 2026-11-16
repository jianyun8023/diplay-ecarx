package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia

/** Wired bring-up policy owned by the Rust core; the host only executes the returned action. */
internal object NativeWiredStack {
    init { NativeMedia.requireAvailable() }

    /** 1 = clear the saved pair record and pair again; 0 = rethrow the original failure. */
    @JvmStatic external fun carkitErrorAction(hasSaved: Boolean, rejection: Int): Int

    /** 1 = the control channel failed; 0 = it ended normally. Codes mirror Iap2WiredControlTerminal. */
    @JvmStatic external fun terminalOutcome(terminal: Int): Int

    /** Wired bring-up action codes, shared with session::wired_stack::advance. */
    const val BRING_UP_BEGIN = 0
    const val BRING_UP_OPEN_MUX = 1
    const val BRING_UP_ADOPT_MUX = 2
    const val BRING_UP_LOAD_PAIR = 3
    const val BRING_UP_OPEN_CARKIT = 4
    const val BRING_UP_OPEN_CSM = 5
    const val BRING_UP_ATTACH_VPN = 6
    const val BRING_UP_BUILD_ENDPOINT = 7
    const val BRING_UP_RUN_CONTROL = 8
    const val BRING_UP_DONE = 9
    const val BRING_UP_ABORT_CLOSE_MUX = 10
    const val BRING_UP_ABORT_CLOSE_CSM = 11
    const val BRING_UP_ABORT_DETACH_VPN = 12

    /** Next bring-up action from (current action, approved gate fact); the core owns the order. */
    @JvmStatic external fun advance(action: Int, approved: Int): Int

    /** Wired teardown plan phases/actions, shared with session::wired_stack::teardown_plan. */
    const val TEARDOWN_CATCH = 0
    const val TEARDOWN_FINALLY = 1
    const val TD_CLOSE_CSM = 0
    const val TD_CLOSE_MUX = 1
    const val TD_CLOSE_NCM_CATCH = 2
    const val TD_DETACH_VPN = 3
    const val TD_TERMINATE_WATCHDOG = 4
    const val TD_CLOSE_OWNED_IF_STALE = 5
    const val TD_CLOSE_USB_SESSION = 6
    const val TD_CLOSE_NCM_FINALLY = 7

    /** Packed ordered teardown plan (nibbles, action+1, 0 = end); the core owns the release order. */
    @JvmStatic external fun teardownPlan(phase: Int, facts: Int): Long
}
