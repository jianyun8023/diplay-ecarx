// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTakeoverPolicyTest {
    @Test fun configuredWirelessIsProtectedByDefault() {
        assertFalse(policy(savedWireless = true, setup = true, auto = false, defaultUsb = false))
    }

    @Test fun optInAllowsTakeover() {
        assertTrue(policy(savedWireless = true, setup = true, auto = true, defaultUsb = false))
    }

    @Test fun explicitWiredPreferenceAllowsTakeover() {
        assertTrue(policy(savedWireless = false, setup = true, auto = false, defaultUsb = false))
    }

    @Test fun freshInstallWithoutWirelessSetupAllowsTakeover() {
        assertTrue(policy(savedWireless = true, setup = false, auto = false, defaultUsb = false))
    }

    @Test fun usbDefaultModeAllowsTakeover() {
        assertTrue(policy(savedWireless = true, setup = true, auto = false, defaultUsb = true))
    }

    @Test fun optedOutDoesNotOverrideExplicitWired() {
        assertTrue(policy(savedWireless = false, setup = true, auto = false, defaultUsb = false))
    }

    private fun policy(savedWireless: Boolean, setup: Boolean, auto: Boolean, defaultUsb: Boolean) =
        UsbTakeoverPolicy.shouldTakeOver(
            savedWireless = savedWireless,
            wirelessSetupPresent = setup,
            usbAutoTakeover = auto,
            defaultModeUsb = defaultUsb,
        )
}
