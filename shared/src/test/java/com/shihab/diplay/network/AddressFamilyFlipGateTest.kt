package com.shihab.diplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressFamilyFlipGateTest {

    @Test fun flipIsAllowedOnlyWhenPhoneIsOnHotspotAndFamilyIsReachable() {
        assertTrue(AddressFamilyFlipGate.mayFlip(phoneOnHotspot = true, familyReachable = true))
        assertNull(AddressFamilyFlipGate.withholdReason(phoneOnHotspot = true, familyReachable = true))
    }

    @Test fun timeoutWithNoAssociatedIphoneNeverFlipsTheFamily() {
        assertFalse(AddressFamilyFlipGate.mayFlip(phoneOnHotspot = false, familyReachable = true))
        assertEquals(
            "no associated iPhone on the hotspot",
            AddressFamilyFlipGate.withholdReason(phoneOnHotspot = false, familyReachable = true),
        )
        assertFalse(AddressFamilyFlipGate.mayFlip(phoneOnHotspot = false, familyReachable = false))
    }

    @Test fun associatedIphoneWithoutReachableEvidenceDoesNotFlip() {
        assertFalse(AddressFamilyFlipGate.mayFlip(phoneOnHotspot = true, familyReachable = false))
        assertEquals(
            "current address family not yet proven reachable",
            AddressFamilyFlipGate.withholdReason(phoneOnHotspot = true, familyReachable = false),
        )
    }
}
