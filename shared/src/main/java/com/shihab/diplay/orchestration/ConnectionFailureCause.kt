package com.shihab.diplay.orchestration

import java.io.IOException

enum class ConnectionFailureCause {
    CHOSEN_IPHONE_NOT_PAIRED, NO_PAIRED_IPHONE, IPHONE_NOT_CHOSEN, WIFI_DIRECT_CHANNEL_UNAVAILABLE,
    CAR_HOTSPOT_OFF, CAR_HOTSPOT_DETAILS_DIFFER, HOTSPOT_INTERFACE_NOT_FOUND, CAR_HOTSPOT_START_TIMED_OUT,
    CAR_HOTSPOT_START_FAILED,

    /** The iPhone answered WirelessCarPlayUpdate with "unavailable"; it refuses wireless CarPlay here. */
    WIRELESS_CARPLAY_UNAVAILABLE,

    /** Bluetooth iAP2 authenticated, but the iPhone never asked for the Wi-Fi transport. */
    WIRELESS_TRANSPORT_NOT_STARTED,

    /** The RFCOMM link connected but the iPhone never opened an iAP2 control session (CSM). */
    WIRELESS_BLUETOOTH_LINK_FAILED,

    /** The iAP2 control session was up but the iPhone stopped answering identification/MFi or the
     * transport exchange — a silent phone rather than a rejected handshake. */
    WIRELESS_PHONE_NOT_RESPONDING,

    /** The iPhone rejected or did not complete MFi authentication over the Bluetooth iAP2 link. */
    WIRELESS_AUTHENTICATION_FAILED,

    /** The iPhone authenticated over Bluetooth but the tunneled iAP2 channel never came up. */
    WIRELESS_TUNNEL_NOT_STARTED,
}

/** Unlike a [com.shihab.diplay.network.WirelessStartupException], it changes what the screen says, not the retries. */
open class ConnectionFailureException(val reason: ConnectionFailureCause, message: String, cause: Throwable? = null) :
    IOException(message, cause)
