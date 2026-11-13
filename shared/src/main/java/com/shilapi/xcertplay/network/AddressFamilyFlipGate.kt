package com.shilapi.xcertplay.network

/**
 * Decides whether a wireless first-TCP timeout is allowed to persist a switch of the preferred
 * hotspot address family (IPv4 <-> IPv6).
 *
 * A first-TCP timeout on its own is ambiguous: the iPhone may not be on the hotspot at all (still
 * in the house, Bluetooth off, Wi-Fi blocked). In that case the current family was never given a
 * fair chance, so flipping the preference would only poison the next, healthy connection. A flip is
 * a correction, so it needs two pieces of evidence from this generation:
 *
 * 1. The iPhone is actually on this hotspot — a Bonjour `_airplay._tcp` announcement proves it
 *    joined the AP and is answering mDNS.
 * 2. The current address family is reachable at all — the iPhone responded on it (Bonjour
 *    discovery resolved, iAP2 tunnel came up, or an AirPlay session was seen). Without this the
 *    "this family is broken" verdict rests on no positive signal.
 *
 * Pure and dependency-free so the policy is testable without a controller, a hotspot or a radio.
 */
internal object AddressFamilyFlipGate {

    /**
     * @param phoneOnHotspot true when a Bonjour announcement from the iPhone was observed for this run
     * @param familyReachable true when the iPhone answered on the current address family
     */
    fun mayFlip(phoneOnHotspot: Boolean, familyReachable: Boolean): Boolean =
        NativeHotspotAddress.flipGate(phoneOnHotspot, familyReachable) == 0

    /** Human-readable reason for logs when [mayFlip] says no; `null` when a flip is allowed. */
    fun withholdReason(phoneOnHotspot: Boolean, familyReachable: Boolean): String? = when (
        NativeHotspotAddress.flipGate(phoneOnHotspot, familyReachable)) {
        1 -> "no associated iPhone on the hotspot"
        2 -> "current address family not yet proven reachable"
        else -> null
    }
}
