// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

/**
 * Decides whether an Apple USB insertion may switch a running session from wireless CarPlay to the
 * wired transport. Kept as a pure function so the edge cases are covered without an Activity.
 *
 * The saved wireless preference is the driver's choice and is never rewritten here. A USB attach
 * takes over only when:
 *  - the driver opted in ([usbAutoTakeover]), or
 *  - the driver's default connection mode is USB, or
 *  - wireless is not the saved preference (for example "Connect with USB" saved wired), or
 *  - wireless has never been set up (no iPhone chosen / no hotspot), so plugging in is the only
 *    way to start CarPlay.
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
    ): Boolean = usbAutoTakeover || defaultModeUsb || !savedWireless || !wirelessSetupPresent
}
