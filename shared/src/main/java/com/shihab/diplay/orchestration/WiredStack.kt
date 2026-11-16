package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia

/** Wired bring-up policy owned by the Rust core; the host only executes the returned action. */
internal object NativeWiredStack {
    init { NativeMedia.requireAvailable() }

    /** 1 = clear the saved pair record and pair again; 0 = rethrow the original failure. */
    @JvmStatic external fun carkitErrorAction(hasSaved: Boolean, rejection: Int): Int

    /** 1 = the control channel failed; 0 = it ended normally. Codes mirror Iap2WiredControlTerminal. */
    @JvmStatic external fun terminalOutcome(terminal: Int): Int
}
