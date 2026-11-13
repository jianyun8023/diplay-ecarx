package com.shihab.diplay.transport

/**
 * Progress checkpoints of the wireless iAP2 bootstrap handshake, lowest to highest.
 *
 * The wireless bootstrap runs over two transports: the Bluetooth RFCOMM link carries the iAP2
 * control channel (CSM) until the iPhone hands voice/data control to the Wi-Fi AirPlay tunnel. A
 * stall at a checkpoint names a different failure — the radio link never became a real iAP2
 * session, the phone never answered identification/MFi, or the phone accepted the handshake but
 * never brought the tunnel up — which a single whole-handshake timeout cannot tell apart.
 *
 * The order is the order a healthy iPhone passes them. [WIRELESS_CARPLAY_AVAILABLE],
 * [TRANSPORT_IDENTIFIED] and [START_SESSION_SENT] arrive close together and, depending on the
 * phone, not always in this exact order, so callers treat "reached" as the highest ordinal seen.
 */
enum class Iap2BootstrapPhase {
    /** The RFCOMM socket to the iPhone's iAP2 service is connected. */
    RFCOMM_CONNECTED,

    /** The iAP2 control session (CSM) negotiated and is writable. */
    CSM_ESTABLISHED,

    /** The iPhone accepted the accessory's MFi authentication. */
    MFi_AUTHENTICATED,

    /** The iPhone reported `WirelessCarPlayUpdate` available=true (0x4E0D). */
    WIRELESS_CARPLAY_AVAILABLE,

    /** The iPhone sent its `DeviceTransportIdentifier` notification (0x4E0E). */
    TRANSPORT_IDENTIFIED,

    /** DiPlay sent CarPlay `StartSession` (0x4301) carrying the Wi-Fi endpoint. */
    START_SESSION_SENT,

    /** The tunneled iAP2 channel over AirPlay/Wi-Fi is ready; the bootstrap handed off. */
    TUNNEL_ESTABLISHED,
}
