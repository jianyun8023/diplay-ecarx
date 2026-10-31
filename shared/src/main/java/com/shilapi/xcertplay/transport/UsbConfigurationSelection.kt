package com.shilapi.xcertplay.transport

/**
 * Switches the iPhone to its CarPlay configuration when a head-unit kernel driver holds an
 * interface of the current one.
 *
 * Linux refuses SET_CONFIGURATION while any interface of the active configuration is bound
 * ("usbfs: interface 0 claimed by snd-usb-audio while '…' sets config #6"). An 8227L kernel bound
 * snd-usb-audio to the iPhone's audio configuration, so every switch failed, the claims landed on
 * the wrong configuration and DiPlay reported "could not claim the NCM interface 3" (#518).
 * Android detaches a kernel driver only through a forced claim, so each interface number is
 * claimed with force. Android's releaseInterface() asks the kernel to reattach the driver
 * (USBDEVFS_CONNECT), which undid the detach before the switch in 0.2.19, so the claims are
 * dropped by closing the connection instead: usbfs releases them on close without reattaching.
 */
internal object UsbConfigurationSelection {
    data class Outcome(
        val target: Int,
        val selected: Boolean,
        val before: Int?,
        val after: Int?,
        val detached: List<Int>,
    ) {
        /** Android reported the switch as failed and the iPhone still reads another configuration. */
        val wrongConfiguration: Boolean get() = !selected && after != null && after != target

        val resetFailed: Boolean get() = !selected && before == target

        fun diagnostic(): String =
            "USB configuration select target=$target ok=$selected before=${before ?: "unknown"} " +
                "detached=${detached.joinToString(",").ifEmpty { "none" }} after=${after ?: "unknown"}" +
                if (selected && before == target) " action=reset-active" else ""
    }

    /**
     * Selects [target] before opening a new USBMUX/NCM session, even when it is already active:
     * SET_CONFIGURATION resets old endpoint/alternate-setting state that closing a handle leaves behind.
     * If a driver blocks the request, detaches it before reopening and retrying. A switch from another
     * configuration that reports failure but actually selected [target] still keeps the existing fallback.
     */
    fun select(
        target: Int,
        interfaceIds: List<Int>,
        setConfiguration: () -> Boolean,
        activeConfiguration: () -> Int?,
        detach: (Int) -> Boolean,
        reopen: () -> Unit,
    ): Outcome? {
        val before = activeConfiguration()
        if (setConfiguration()) {
            return if (before == target) Outcome(target, selected = true, before, before, emptyList()) else null
        }
        val after = activeConfiguration()
        if (before != target && after == target) return Outcome(target, selected = false, before, after, emptyList())
        val detached = interfaceIds.distinct().sorted().filter(detach)
        if (detached.isNotEmpty()) reopen()
        val selected = setConfiguration()
        return Outcome(target, selected, before, activeConfiguration(), detached)
    }
}
