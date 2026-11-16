// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import com.shihab.diplay.orchestration.HostUsbRecovery

/**
 * Decides whether an Apple USB insertion may switch a running session from wireless CarPlay to the
 * wired transport. The pure selection is delegated to Rust.
 *
 * The saved wireless preference is the driver's choice and is never rewritten here. A USB attach
 * takes over only when:
 *  - the driver opted in ([usbAutoTakeover]), or
 *  - the driver's default connection mode is USB, or
 *  - wireless is not the saved preference (for example "Connect with USB" saved wired), or
 *  - no iPhone address has been saved, so plugging in is the only configured way to start CarPlay.
 *
 * In every other case the attach may only be recorded; it must not restart the session or request
 * VPN consent.
 */
internal object UsbTakeoverPolicy {
    fun shouldTakeOver(
        savedWireless: Boolean,
        wirelessSetupPresent: Boolean,
        usbAutoTakeover: Boolean,
        defaultModeUsb: Boolean,
    ): Boolean = HostUsbRecovery.shouldTakeOver(savedWireless, wirelessSetupPresent, usbAutoTakeover, defaultModeUsb)
}
