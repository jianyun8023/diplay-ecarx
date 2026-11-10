package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.WirelessStartupFailure

/** Phone-side pairing outcomes only; Android USB access and transport failures are not trust refusals. */
enum class UsbPairingFailure { TRUST_REFUSED, PHONE_LOCKED, TIMED_OUT, CANCELLED }

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data class IphoneNotListed(val nonHubDeviceListed: Boolean) : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object WaitingForIphoneTrust : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null,
        val knownCause: ConnectionFailureCause? = null,
        val usbPairingFailure: UsbPairingFailure? = null,
        val automaticRetryAllowed: Boolean = true) : CarPlayStatus()
}

/** One line per status for the connection debug log: `STEP <phase>/<step>: …`, or `ERROR …` for a failure. */
internal fun CarPlayStatus.debugLogMessage(): String = when (this) {
    CarPlayStatus.DiscoveringMfi ->
        "STEP mfi/start: preparing the configured MFi authentication provider"
    CarPlayStatus.WaitingForMfi ->
        "STEP mfi/wait: MFi coprocessor not present; polling"
    CarPlayStatus.RequestingMfiPermission ->
        "STEP mfi/permission: requesting CH341 USB access"
    CarPlayStatus.MfiReady ->
        "STEP mfi/ready: MFi authentication provider is ready"
    CarPlayStatus.StartingHotspot ->
        "STEP wifi/ap: starting the wireless CarPlay access point"
    is CarPlayStatus.HotspotReady ->
        "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
            "channel=$channel bssid=$bssid address=$address"
    CarPlayStatus.WaitingForPairedIphone ->
        "STEP bt/select: waiting for a paired or connected iPhone"
    CarPlayStatus.ConnectingBluetooth ->
        "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
    CarPlayStatus.RunningWireless ->
        "STEP iap2/wireless: Bluetooth control loop running"
    CarPlayStatus.WirelessActive ->
        "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
    CarPlayStatus.WirelessActiveFallback ->
        "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap preserved"
    CarPlayStatus.DiscoveringIphone ->
        "STEP usb/discover: searching for an iPhone USB device"
    CarPlayStatus.WaitingForIphone ->
        "STEP usb/wait: iPhone USB device not present; polling"
    is CarPlayStatus.IphoneNotListed ->
        "STEP usb/wait: wired iPhone not listed after ${CarPlayController.IPHONE_NOT_LISTED_AFTER_MILLIS / 1_000} s; " +
            "other USB devices=$nonHubDeviceListed"
    CarPlayStatus.RequestingIphonePermission ->
        "STEP usb/permission: requesting USB access to the iPhone"
    CarPlayStatus.WaitingForReenumeration ->
        "STEP usb/reenum: waiting for the CarPlay USB configuration"
    CarPlayStatus.SelectingConfiguration ->
        "STEP usb/config: selecting the iPhone CarPlay configuration"
    CarPlayStatus.OpeningDataPaths ->
        "STEP usb/data: opening iAP2 and NCM USB data paths"
    CarPlayStatus.Pairing ->
        "STEP lockdown/pair: loading or creating the pairing record"
    CarPlayStatus.WaitingForIphoneTrust ->
        "STEP lockdown/trust: waiting for the iPhone trust response"
    CarPlayStatus.ConnectingControl ->
        "STEP lockdown/carkit: opening com.apple.carkit.service"
    CarPlayStatus.AttachingNetwork ->
        "STEP network/attach: attaching the AirPlay network transport"
    CarPlayStatus.RunningControl ->
        "STEP iap2/wired: wired iAP2 control loop running"
    CarPlayStatus.ControlEnded ->
        "STEP control/end: the control window ended"
    is CarPlayStatus.Failed ->
        "ERROR $message"
}
