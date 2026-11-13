package com.shihab.diplay.transport

import com.shihab.diplay.iap2.message.Iap2CarPlayMessages
import com.shihab.diplay.iap2.message.Iap2WirelessMessages
import com.shihab.diplay.iap2.message.Iap2WirelessSessionParameters
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.iap2.wire.Iap2Frame
import com.shihab.diplay.mfi.Iap2MfiAuthenticationClient

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        vehicleStatusProvider: VehicleStatusProvider? = null,
        onReady: () -> Unit = {},
        beforeStartSession: () -> Unit = {},
        onStartSessionSent: (Iap2StartSessionSent) -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        // Evaluated only in the authenticated control loop when its original deadline expires.
        // The caller must prove that the current CarPlay session is usable before keeping it alive.
        keepAliveAfterDeadline: () -> Boolean = { false },
        // How long an authenticated iPhone may take to ask for the Wi-Fi transport. A phone that
        // answers WirelessCarPlayUpdate available=false never does, and without this the bootstrap
        // keeps polling for the whole control-loop budget while the screen says it is connecting.
        // Disabled by default: the runtime tunnel loop starts after the transport already exists.
        transportWaitMillis: Long = NO_TRANSPORT_WAIT_MILLIS,
        // Bluetooth bootstrap must not borrow the long-lived runtime control-loop budget.
        // Null preserves the existing tunnel caller contract.
        handshakeTimeoutMillis: Long? = null,
        onProgress: (String) -> Unit = {},
        // Structured progress checkpoints for the bootstrap watchdog. The runtime tunnel loop,
        // which starts after the transport already exists, leaves this at the default.
        onBootstrapPhase: (Iap2BootstrapPhase) -> Unit = {},
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires an Iap2IdentificationConfig with wireless transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }
        require(transportWaitMillis == NO_TRANSPORT_WAIT_MILLIS || transportWaitMillis in 1..MAX_TIMEOUT_MILLIS) {
            "transportWaitMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TRANSPORT_WAIT_MILLIS"
        }

        require(handshakeTimeoutMillis == null || handshakeTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        val deadline = WirelessControlDeadline(timeoutMillis)
        val handshakeDeadline = handshakeTimeoutMillis?.let(::WirelessControlDeadline) ?: deadline
        fun handshakeRemaining(): Long = minOf(deadline.requireRemaining(), handshakeDeadline.requireRemaining())
        val identified = identification.withVehicleStatusFrom(vehicleStatusProvider)
        if (identified.vehicleStatusEnabled != identification.vehicleStatusEnabled) {
            onProgress("iap2 no battery reading: not declaring an electric vehicle")
        }
        Iap2IdentificationClient(session).identify(
            identified,
            handshakeRemaining(),
            onProgress,
            onLinkReady = { onBootstrapPhase(Iap2BootstrapPhase.CSM_ESTABLISHED) },
        )
        onProgress("iap2 identification accepted")
        val core = Iap2HandshakeCore(3)
        setup@ while (true) when (core.setup) {
            Iap2HandshakeCore.Setup.AUTHENTICATE -> {
                mfi.run(session, handshakeRemaining(), onProgress)
                onBootstrapPhase(Iap2BootstrapPhase.MFi_AUTHENTICATED)
                core.authenticated()
                onProgress("iap2 authentication accepted")
            }
            Iap2HandshakeCore.Setup.SUBSCRIBE -> {
                for (subscription in Iap2WiredControlClient.subscriptions()) session.send(subscription, handshakeRemaining())
                core.subscribed()
                onProgress("iap2 subscriptions sent")
                onReady()
            }
            Iap2HandshakeCore.Setup.COMPLETE -> break@setup
            Iap2HandshakeCore.Setup.POWER -> error("Wireless iAP2 does not advertise wired power")
        }

        fun sendControl(frame: Iap2Frame) {
            session.send(frame, deadline.requireRemaining(keepAliveAfterDeadline))
        }

        val rxLog = Iap2RxLog()
        fun result(terminal: Iap2WirelessControlTerminal) = Iap2WirelessControlResult(
            terminal, Iap2WirelessControlStage.entries[core.stage], core.forwarded, core.wifi, core.starts,
            core.transportSeen, core.postWifi, core.availableSeen, core.unavailableSeen,
        )
        val transportWait = WirelessTransportWait(transportWaitMillis)
        val location = Iap2LocationReporter(locationProvider, onProgress)
        val vehicleStatus = Iap2VehicleStatusReporter(vehicleStatusProvider, onProgress)
        try {
            while (true) {
                val remaining = deadline.remainingMillis(keepAliveAfterDeadline)
                if (remaining == 0L) {
                    return result(Iap2WirelessControlTerminal.TIMED_OUT)
                }
                // The iPhone authenticated but never asked for the Wi-Fi transport. Polling longer
                // cannot change its mind, so end the attempt and let the caller name the reason.
                if (transportWait.expired(core.starts, core.transportSeen)) {
                    onProgress(
                        "iap2 no wireless transport after ${transportWaitMillis}ms: " +
                            "stage=${Iap2WirelessControlStage.entries[core.stage]} wirelessCarPlayAvailable=${core.availableSeen} " +
                            "reportedUnavailable=${core.unavailableSeen} " +
                            "wifiConfigs=${core.wifi}",
                    )
                    return result(Iap2WirelessControlTerminal.NO_WIRELESS_TRANSPORT)
                }
                location.tick(::sendControl)
                vehicleStatus.tick(::sendControl)
                val pollTimeout = transportWait.boundPollTimeout(
                    vehicleStatus.pollTimeout(location.pollTimeout(remaining)),
                    core.starts,
                    core.transportSeen,
                )
                val incoming = session.recv(pollTimeout)
                if (incoming == null) {
                    if (session.isClosed) {
                        return result(Iap2WirelessControlTerminal.CHANNEL_CLOSED)
                    }
                    if (deadline.remainingMillis(keepAliveAfterDeadline) == 0L) {
                        return result(Iap2WirelessControlTerminal.TIMED_OUT)
                    }
                    continue
                }

                when (core.message(incoming.messageId)) {
                    Iap2HandshakeCore.Action.WIFI_REQUEST -> {
                        onProgress("iap2 rx=0x5702 request-wifi-configuration")
                        val plan = core.wifiPlan()
                        if (plan == 0) {
                            onProgress("iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached")
                        } else {
                            sendControl(accessoryWiFiConfiguration(endpoint))
                            core.wifiSent(plan)
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                        }
                    }

                    Iap2HandshakeCore.Action.CARPLAY_AVAILABLE -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        onProgress(carPlayAvailabilityDiagnostic(incoming))
                        beforeStartSession()
                        sendStartSession(endpoint, ::sendControl, onStartSessionSent)
                        core.startSent()
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                        onBootstrapPhase(Iap2BootstrapPhase.START_SESSION_SENT)
                    }

                    Iap2HandshakeCore.Action.WIRELESS_AVAILABILITY -> {
                        val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                        core.availability(available)
                        onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                        // Only the positive availability is bootstrap progress; "unavailable" is an answer.
                        if (available) onBootstrapPhase(Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE)
                    }

                    Iap2HandshakeCore.Action.TRANSPORT -> {
                        core.transportReceived()
                        val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                        core.transportParsed()
                        onBootstrapPhase(Iap2BootstrapPhase.TRANSPORT_IDENTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "resending 0x5703",
                        )
                        val plan = core.wifiPlan()
                        if (plan == 0) {
                            onProgress("iap2 post-transport 0x5703 ignored: maximum Wi-Fi configuration sends reached")
                        } else {
                            sendControl(accessoryWiFiConfiguration(endpoint))
                            core.wifiSent(plan)
                            onProgress("iap2 tx=0x5703 post-transport accessory-wifi-configuration")
                        }
                    }

                    Iap2HandshakeCore.Action.VEHICLE -> {
                        vehicleStatus.handle(incoming, ::sendControl)
                    }

                    Iap2HandshakeCore.Action.LOCATION -> {
                        location.handle(incoming, ::sendControl)
                    }

                    else -> {
                        rxLog.line(incoming.messageId)?.let(onProgress)
                        onIncoming(incoming)
                        core.forwarded()
                    }
                }
            }
        } finally {
            locationProvider?.stop()
        }
    }

    companion object {
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_START_SESSION = 0x4301
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        const val NO_TRANSPORT_WAIT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L

        /** Malformed optional availability metadata must not change existing control behavior. */
        internal fun carPlayAvailabilityDiagnostic(frame: Iap2Frame): String = try {
            val value = Iap2CarPlayMessages.availability(frame)
            "iap2 availability wired=${value.wired?.available ?: "unknown"} " +
                "wireless=${value.wireless?.available ?: "unknown"} " +
                "themeAssets=${value.themeAssets?.available ?: "unknown"}"
        } catch (error: Exception) {
            "iap2 availability decode=failed failureClass=${error.javaClass.simpleName}"
        }

        /** Optional AP hint is independent of the AirPlay receiver identity. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
                bssid = endpoint.accessPointBssid,
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    accessPointBssid: ByteArray? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()
    val accessPointBssid: ByteArray? = accessPointBssid?.copyOf()

    init {
        require(accessPointBssid == null || accessPointBssid.size == 6) { "AP address must contain six bytes" }
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal {
    CHANNEL_CLOSED,
    TIMED_OUT,

    /** The iPhone never asked for the Wi-Fi transport, so no wireless session can start. */
    NO_WIRELESS_TRANSPORT,
}

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
    /** The iPhone answered 0x4E0D with "unavailable"; it will not start wireless CarPlay. */
    val wirelessUnavailableReported: Boolean = false,
)

/** 仅在 StartSession 发送成功后产生，不通过日志驱动超时。 */
data class Iap2StartSessionSent(val sentAtNanos: Long)

internal fun sendStartSession(
    endpoint: Iap2WirelessCarPlayEndpoint,
    send: (Iap2Frame) -> Unit,
    onSent: (Iap2StartSessionSent) -> Unit,
) {
    send(Iap2WirelessControlClient.carPlayStartSession(endpoint))
    onSent(Iap2StartSessionSent(System.nanoTime()))
}

/**
 * How long an authenticated iPhone may take to ask for the Wi-Fi transport.
 *
 * An iPhone that will start wireless CarPlay asks within about a second. One that answered
 * WirelessCarPlayUpdate with "unavailable" never asks at all, and waiting out the whole control-loop
 * budget only leaves the screen saying it is connecting.
 */
internal class WirelessTransportWait(
    waitMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val core = Iap2TransportWaitCore(waitMillis, nowNanos())

    fun expired(startRequestsSent: Int, transportNotified: Boolean): Boolean = core.expired(
        startRequestsSent, transportNotified,
        if (core.needsClock(startRequestsSent, transportNotified)) nowNanos() else 0L,
    )

    fun boundPollTimeout(requestedMillis: Long, startRequestsSent: Int, transportNotified: Boolean): Long = core.bound(
        requestedMillis, startRequestsSent, transportNotified,
        if (core.needsClock(startRequestsSent, transportNotified)) nowNanos() else 0L,
    )
}

/** A bounded bring-up deadline that a proven live session can extend for ongoing control updates. */
internal class WirelessControlDeadline(timeoutMillis: Long, nowNanos: () -> Long = System::nanoTime) {
    private val core = Iap2HandshakeDeadline(2, timeoutMillis, nowNanos)
    fun remainingMillis(keepAlive: () -> Boolean = { false }): Long = core.remaining(keepAlive)

    fun requireRemaining(keepAlive: () -> Boolean = { false }): Long = remainingMillis(keepAlive).also {
        if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wireless iAP2 control bring-up")
    }
}
