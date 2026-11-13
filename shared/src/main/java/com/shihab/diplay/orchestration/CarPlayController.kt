package com.shihab.diplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shihab.diplay.airplay.AirPlayListenerIdentity
import com.shihab.diplay.airplay.AirPlayTcpAccepted
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayContact
import com.shihab.diplay.airplay.AirPlayKnobState
import com.shihab.diplay.airplay.AirPlayDeviceInfo
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.airplay.VideoInCar
import com.shihab.diplay.airplay.VideoPlaybackDelivery
import com.shihab.diplay.bluetooth.BluetoothAdapterRestore
import com.shihab.diplay.bluetooth.CarBluetoothProfileGuard
import com.shihab.diplay.hud.NavigationOutputs
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.logging.XCertplayTrace
import com.shihab.diplay.mfi.Iap2MfiAuthenticationClient
import com.shihab.diplay.mfi.MfiAuthenticationClient
import com.shihab.diplay.mfi.RemoteMfiAuthenticationClient
import com.shihab.diplay.mfi.LocalMfiAuthenticationClient
import com.shihab.diplay.network.AddressFamilyFlipGate
import com.shihab.diplay.network.CarPlayBonjour
import com.shihab.diplay.network.CarPlayBonjourEvent
import com.shihab.diplay.network.diagnosticSummary
import com.shihab.diplay.network.CarPlayVpnService
import com.shihab.diplay.network.LocalOnlyHotspotManager
import com.shihab.diplay.network.ManualHotspotManager
import com.shihab.diplay.network.ExistingWifiManager
import com.shihab.diplay.network.HotspotAddressPolicy
import com.shihab.diplay.network.HotspotAddressRecovery
import com.shihab.diplay.network.WifiP2pGroupManager
import com.shihab.diplay.network.WifiScanPause
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.network.WirelessHotspotBackend
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.network.WirelessInterfaceDiagnostics
import com.shihab.diplay.network.WirelessReceiveDiagnostics
import com.shihab.diplay.network.WirelessStartupPolicy
import com.shihab.diplay.network.WirelessStartupException
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.network.WirelessStartupDiagnostics
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.BluetoothLinkCheck
import com.shihab.diplay.transport.BluetoothRadioCheck
import com.shihab.diplay.transport.BluetoothRfcommDuplexStream
import com.shihab.diplay.transport.Ch341DeviceMatcher
import com.shihab.diplay.transport.Ch341I2cTransport
import com.shihab.diplay.transport.Ch341UsbHost
import com.shihab.diplay.transport.Ch341UsbSession
import com.shihab.diplay.transport.Iap2BootstrapPhase
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2LocationProvider
import com.shihab.diplay.transport.Iap2UsbMuxHost
import com.shihab.diplay.transport.Iap2UsbSession
import com.shihab.diplay.transport.Iap2WiredCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WiredControlClient
import com.shihab.diplay.transport.Iap2WiredControlTerminal
import com.shihab.diplay.transport.Iap2WirelessCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WirelessControlClient
import com.shihab.diplay.transport.Iap2WirelessControlTerminal
import com.shihab.diplay.transport.Iap2WirelessIdentification
import com.shihab.diplay.transport.Iap2WirelessLinkRole
import com.shihab.diplay.transport.forWirelessLink
import com.shihab.diplay.transport.I2cTransport
import com.shihab.diplay.transport.I2cTransportException
import com.shihab.diplay.transport.IphoneCarPlayConfiguration
import com.shihab.diplay.transport.IphoneUsbGrant
import com.shihab.diplay.transport.IphoneUsbException
import com.shihab.diplay.transport.IphoneUsbHost
import com.shihab.diplay.transport.IphoneUsbMatcher
import com.shihab.diplay.transport.LinuxI2cTransport
import com.shihab.diplay.transport.LockdownCarKitClient
import com.shihab.diplay.transport.LockdownPairingException
import com.shihab.diplay.transport.LockdownPairingClient
import com.shihab.diplay.transport.LockdownPairRecord
import com.shihab.diplay.transport.NcmFunctionDiscovery
import com.shihab.diplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = ControllerLifecycleCore.handoff(handoffRequested, tunnelActive, sessionActive)

/** An expected CSM EOF after an authenticated Wi-Fi tunnel is not a failed bootstrap. */
internal fun isExpectedHandoffControlClose(
    stage: WirelessBootstrapRecovery.Stage,
    error: Throwable,
    wirelessActive: Boolean,
    tunnelReady: Boolean,
): Boolean = ControllerLifecycleCore.expectedControlClose(stage.ordinal,
    (if (wirelessActive) 1 else 0) or (if (tunnelReady) 2 else 0) or
        (if (error is IOException) 4 else 0) or (if (error is java.io.InterruptedIOException) 8 else 0) or
        (if (error is ConnectionFailureException) 16 else 0) or (if (error is WirelessStartupException) 32 else 0))

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shihab.diplay.transport.VehicleStatusProvider? = null,
) : Closeable {
    private val lifecycle: ControllerLifecycleCore
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        // Load the required core before registering any platform observers/resources.
        lifecycle = ControllerLifecycleCore(config.transport == CarPlayTransport.WIRELESS, config.locationReportingEnabled)
        WifiScanPause.restoreIfNeeded(context.applicationContext)
        NavigationOutputs.start(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun: Int get() = lifecycle.diagnosticRun
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val iphoneHost by lazy {
        IphoneUsbHost(
            appContext,
            requireUsbManager(),
            if (config.iphoneDevices.isNotEmpty()) {
                IphoneUsbMatcher(config.iphoneDevices)
            } else {
                IphoneUsbMatcher.appleVendor()
            },
            onDiagnostic = ::connectionDiagnostic,
        )
    }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val availabilityPollGeneration: Int get() = lifecycle.availabilityGeneration
    private val permissionPollGeneration: Int get() = lifecycle.permissionGeneration
    private val reenumerationAttempts: Int get() = lifecycle.reenumerationAttempts
    @Volatile private var wiredDeviceName: String? = null
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    private val closed: Boolean get() = lifecycle.closed
    private val phase: Phase get() = Phase.entries[lifecycle.phase]
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shihab.diplay.media.CarPlayPlaybackStatus()

    private val albumReplayLock = Any()
    private var initialArtworkReplay: com.shihab.diplay.transport.Iap2ArtworkTransfer? = null

    fun nowPlayingSnapshot(): com.shihab.diplay.media.CarPlayNowPlaying =
        synchronized(playbackStatus) { playbackStatus.nowPlaying }

    /** One bounded payload, never a nearest-cover guess when a reference is absent. */
    fun matchingArtworkSnapshot(id: Int?): com.shihab.diplay.transport.Iap2ArtworkTransfer? =
        synchronized(albumReplayLock) { initialArtworkReplay?.takeIf { id != null && it.id == id } }

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shihab.diplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var wifiScanPause: WifiScanPause? = null
    private val wirelessResourceLock = Any()
    @Volatile private var wirelessRun: WirelessRun? = null
    @Volatile private var wiredStartWatchdog: WiredStartWatchdog? = null
    private val wiredSessionActive: Boolean get() = lifecycle.wiredSessionActive
    private val wiredStartSentAtNanos: Long? get() = lifecycle.wiredStartSentAtNanos
    @Volatile private var wiredVpnOwner: CarPlayVpnService.WiredOwner? = null
    private data class WiredResources(val csm: Iap2Session?, val mux: Iap2UsbMuxHost?, val vpn: CarPlayVpnService.WiredOwner?)

    private fun takeOwnedWiredResources(expected: WiredResources): WiredResources = synchronized(wirelessResourceLock) {
        val csm = this.csm?.takeIf { it === expected.csm }
        val mux = this.mux?.takeIf { it === expected.mux }
        val vpn = wiredVpnOwner?.takeIf { it === expected.vpn }
        if (csm != null) this.csm = null
        if (mux != null) this.mux = null
        if (vpn != null) wiredVpnOwner = null
        WiredResources(csm, mux, vpn)
    }

    private fun closeWiredResources(resources: WiredResources) {
        resources.csm?.let { closeBestEffort("CSM") { it.close() } }
        resources.mux?.let { closeBestEffort("USBMUX") { it.close() } }
        resources.vpn?.let { closeBestEffort("VPN/NCM") { vpnService?.detachWired(it) } }
    }
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "diplay-first-tcp-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile private var vpnService: CarPlayVpnService? = null
    /** The next wireless run follows the car turning its hotspot off and may wait for its radios to return. */
    private val wirelessGeneration: Int get() = lifecycle.wirelessGeneration

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var lastCh341Listing: String? = null
    private var lastIphoneListing: String? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private data class SessionCornerMask(val session: AirPlaySession, val png: ByteArray, val width: Int)
    @Volatile private var currentCornerMask: SessionCornerMask? = null

    /** The live session for a host adopting the running cluster stream. */
    fun activeAirPlaySession(): AirPlaySession? = activeSession?.takeIf { !closed && !it.isClosed }

    @Volatile private var clusterOutputStateListener: (() -> Unit)? = null
    private var clusterRenderedSession: AirPlaySession? = null

    /** Session-owned output must receive events even while no Activity is attached. */
    fun setClusterOutputStateListener(listener: (() -> Unit)?) {
        clusterOutputStateListener = listener
        listener?.invoke()
    }

    fun clearClusterOutputStateListener(expected: () -> Unit) {
        if (clusterOutputStateListener === expected) clusterOutputStateListener = null
    }

    /** The video session can outlive its Activity; the new host must be able to replay the mask. */
    fun activeIphoneCornerMask(): Triple<AirPlaySession, ByteArray, Int>? {
        val current = currentCornerMask ?: return null
        if (closed || activeSession !== current.session || current.session.isClosed) return null
        return Triple(current.session, current.png.copyOf(), current.width)
    }

    private val commandLogLimiter = AirPlayCommandLogLimiter()

    private val sessionListener = object : AirPlaySessionListener {
        // Trace lines reach logcat and the UI; build them only for `setprop log.tag.DiPlayTrace VERBOSE` or a UI that wants them.
        override val debugLogsEnabled: Boolean get() = uiListener?.debugLogsEnabled == true
        override val wantsTrace: Boolean
            get() = uiListener?.wantsTrace == true || Log.isLoggable(TRACE_LOG_TAG, Log.VERBOSE)

        override fun onVideoFrameRendered(session: AirPlaySession) {
            if (!closed && !session.isClosed && activeSession === session) {
                if (clusterRenderedSession !== session) {
                    clusterRenderedSession = session
                    clusterOutputStateListener?.invoke()
                }
                uiListener?.onVideoFrameRendered(session)
            }
        }

        override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
            if (closed || session.isClosed) return
            if (activeSession != null && activeSession !== session) return
            currentCornerMask = SessionCornerMask(session, png.copyOf(), displayWidth)
            uiListener?.onCornerMask(session, png, displayWidth)
        }
        override fun onSessionConnecting(session: AirPlaySession) {
            if (!closed && !session.isClosed && activeSession == null) uiListener?.onSessionConnecting(session)
        }
        override fun onSessionActive(session: AirPlaySession) {
            if (closed || session.isClosed) return
            lifecycle.sessionActive(true)
            wiredStartWatchdog?.sessionActive()
            if (currentCornerMask?.session !== session) currentCornerMask = null
            val previousSession = activeSession
            val replacement = previousSession !== session
            if (replacement) {
                NavigationOutputs.start(appContext)
                com.shihab.diplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            com.shihab.diplay.network.CarRadioOutage.clear()
            NavigationOutputs.onSessionActive(debugLogs = { uiListener?.debugLogsEnabled == true }, requestExit = ::requestNavigationExit, newSession = replacement, replacePrevious = previousSession != null && replacement) { !closed && activeSession === session && !session.isClosed }
            clusterOutputStateListener?.invoke()
            if (replacement) restoreDashboardContent(session)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onClusterControlReady(session: AirPlaySession) {
            if (!closed && activeSession === session && !session.isClosed) clusterOutputStateListener?.invoke()
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (currentCornerMask?.session === session) currentCornerMask = null
            if (activeSession === session) {
                activeSession = null
                clusterRenderedSession = null
                clusterOutputStateListener?.invoke()
                NavigationOutputs.endNow()
                synchronized(albumReplayLock) { initialArtworkReplay = null }
                com.shihab.diplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            // Never drop a command: suppress only repetitive diagnostic formatting.
            // Detailed parameters are available with the explicit trace switch.
            if (XCertplayTrace.enabled) {
                val rendered = StringBuilder()
                for ((key, value) in params) {
                    if (rendered.isNotEmpty()) rendered.append(',')
                    rendered.append(key).append('=').append(value)
                }
                debugLog("AirPlay command type=" + type + " params=" + rendered)
            } else if (commandLogLimiter.shouldLog(session, type, params)) {
                if (type == "modesChanged") {
                    debugLog("AirPlay command type=modesChanged appStates=" + params["appStates"] +
                        " resources=" + params["resources"])
                } else {
                    debugLog("AirPlay command type=" + type + " keys=" + params.keys.sorted())
                }
            }
            // The parameters arrive from a plist, so they may be integer or real.
            val duckDurationMs = (params["durationMs"] as? Number)?.toInt()
            val duckVolume = (params["volume"] as? Number)?.toFloat()
            // The iPhone sends duckAudio/unduckAudio right before/after a navigation prompt. Acting
            // on them directly avoids the 2-3 s lag of Android's focus callback on 820A (the prompt
            // finished before the media ducked); the focus callback remains as a backstop.
            when (type) {
                "duckAudio" -> media.onDuckAudio(session, true, duckDurationMs, duckVolume)
                "unduckAudio" -> media.onDuckAudio(session, false, duckDurationMs, duckVolume)
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            // AirPlaySession already wrote this line to logcat.
            try {
                uiListener?.onDebugLog(message)
            } catch (error: Exception) {
                Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
            }
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /**
     * True once the current wireless attempt has handed the iPhone a CarPlayStartSession over
     * Bluetooth. From then on the iPhone is joining this listener; tearing it down leaves the
     * phone waiting on an endpoint that no longer exists (07:04 car test: no AirPlay TCP for the
     * next four attempts until the head unit restarted). False again once that attempt has failed,
     * for example on the first-TCP timeout: a failed attempt has nothing left to keep.
     */
    fun startSessionDispatched(): Boolean = wirelessRun?.startSessionDispatched() == true

    /** Nonblocking identity for wheel controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        if (config.transport == CarPlayTransport.WIRELESS) {
            sampleWirelessStart(CarPlayStartupTimeline.Stage.CONTROLLER_START, wirelessGeneration)
        }
        if (!hasRequiredUsbService()) return
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        // MFi discovery replaces the whole stack. Retire the live wireless run now so its callbacks
        // and status posts stop with the session; startWireless releases its resources afterwards.
        synchronized(wirelessResourceLock) { wirelessRun?.retire() }
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            wirelessRun?.takeIf { it.acceptsProgress() }?.let(::restartWireless) ?: startWireless()
        } else {
            startIphone()
        }
    }

    /** A queued move that a newer move of the same fingers may still overwrite. */
    private class PendingMove(val session: AirPlaySession, var contacts: List<AirPlayContact>)

    private val touchLock = Any()
    private var lastTouch: List<AirPlayContact>? = null
    private var pendingMove: PendingMove? = null

    /**
     * A move keeps the same fingers down. Behind a slow write it replaces the move still waiting in
     * the queue, so only the newest position is sent. Presses and releases are never merged.
     */
    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            synchronized(touchLock) {
                val isMove = contacts.isNotEmpty() && contacts.all { it.down } &&
                    lastTouch?.filter { it.down }?.map { it.id }?.toSet() == contacts.map { it.id }.toSet()
                lastTouch = contacts
                val pending = pendingMove
                if (isMove && pending != null && pending.session === session) {
                    pending.contacts = contacts
                    return true
                }
                if (!isMove) {
                    pendingMove = null
                    touchExecutor.execute { session.sendTouch(contacts) }
                } else {
                    val move = PendingMove(session, contacts)
                    touchExecutor.execute {
                        val latest = synchronized(touchLock) {
                            if (pendingMove === move) pendingMove = null
                            move.contacts
                        }
                        session.sendTouch(latest)
                    }
                    // Set only after execute() accepted the task, and still under touchLock, which the task needs to start.
                    pendingMove = move
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Moves CarPlay on the main screen to its declared view area [index], for example another dock edge. */
    fun showViewArea(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    debugLog("View area $index sent=${session.showViewArea(index)}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shihab.diplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Release the phone's secondary navigation view when the physical cluster is hidden. */
    fun setClusterOutputVisible(visible: Boolean) {
        val session = activeAirPlaySession() ?: return
        try {
            touchExecutor.execute { if (!closed && activeSession === session && !session.isClosed) applyClusterUi(visible) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also closes the phone session.
        }
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** A visible physical map and its session/stream generation; null for a paused/virtual/turn-card route. */
    fun dashboardMapRoute(): Any? {
        val session = activeSession ?: return null
        val content = session.clusterContentRoute() ?: return null
        val stream = content.first
        val route = session to stream
        val visibility = clusterUiVisibility
        val shown = visibility?.takeIf { it.first == route }?.second ?: true
        if (!DashboardMapEligibility.permits(content.second,
                dashboardMapOutputVisible, stream, shown, closed)) return null
        return Triple(session, stream, dashboardMapEpoch.get() to content.third)
    }

    /** Whether the wheel can currently control the visible dashboard map. */
    fun dashboardMapStreaming(): Boolean = dashboardMapRoute() != null

    /** One zoom step for the dashboard map, as the car's own zoom controls send it. */
    fun zoomDashboardMap(zoomIn: Boolean): Boolean {
        val route = dashboardMapRoute() ?: return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session && dashboardMapRoute() == route) session.changeMapZoomLevel(zoomIn)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Queues navigation arbitration on the command worker; route metadata confirms completion. */
    fun requestNavigationExit(requestId: Long? = null): Boolean {
        val session = activeAirPlaySession() ?: run {
            if (requestId != null) NavigationOutputs.diagnostic("exit claim written=false reason=no-session", requestId)
            return false
        }
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session) session.requestNavigationExit(requestId)
                else if (requestId != null) NavigationOutputs.diagnostic("exit claim written=false reason=session-replaced", requestId)
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (requestId != null) NavigationOutputs.diagnostic("exit claim written=false reason=queue-rejected", requestId)
            false
        }
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone (telephony Hook Switch), as the wheel's call button does. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one (telephony Drop). */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    /** Sends an arbitrary telephony HID report index (Hook Switch=1, Drop=3, etc.). */
    fun sendTelephonyPress(index: Int): Boolean = sendTelephony(index)

    private fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val queuedNs = System.nanoTime()
        return try {
            touchExecutor.execute {
                debugLog("Playback recovery session=${System.identityHashCode(session)} media command dispatch index=$index queuedNs=$queuedNs " +
                    "queueMs=${(System.nanoTime() - queuedNs) / 1_000_000} currentSession=${activeSession === session}")
                session.sendMedia(index)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        val wired = synchronized(wirelessResourceLock) {
            if (!lifecycle.beginClose()) return
            // Order cancellation with address-preference commits and generation replacement.
            wirelessRun?.retire()
            lifecycle.invalidateWireless()
            dashboardMapOutputVisible = false
            // Claim platform references once; an older run's catch must not close replacements.
            WiredResources(csm, mux, wiredVpnOwner).also {
                csm = null
                mux = null
                wiredVpnOwner = null
            }
        }
        clusterRenderedSession = null
        clusterOutputStateListener?.invoke()
        wiredStartWatchdog?.terminate()
        CarBluetoothProfileGuard.restore(appContext) { debugLog(it) }
        startupTimer.shutdownNow()
        wirelessRun?.cancelHotspotWait()
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        NavigationOutputs.endNow()
        com.shihab.diplay.glance.CarPlayGlance.setConnected(false)
        closeReceivers()
        lifecycle.invalidateAvailability()
        lifecycle.invalidatePermission()
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    for (action in lifecycle.teardownPlan()) when (action) {
                        1 -> closeBestEffort("wireless stack") { wirelessRun?.closeWirelessStack(service) }
                        2 -> { closeBestEffort("Wi-Fi scan pause") { wifiScanPause?.close() }; wifiScanPause = null }
                        3 -> closeBestEffort("CSM") { wired.csm?.close() }
                        4 -> closeBestEffort("USBMUX") { wired.mux?.close() }
                        5 -> closeBestEffort("VPN/NCM") { wired.vpn?.let { service?.detachWired(it) } }
                        6 -> { closeBestEffort("MFi") { mfiSession?.close() }; mfiSession = null }
                        7 -> closeBestEffort("location provider") { locationProvider?.close() }
                    }
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    // A cluster stream DiPlay has not paused yet starts with the map drawn.
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The wheel zoom follows what the dashboard shows now, so zoom mode starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
        }
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        val stream = session.clusterStream.takeIf { it > 0 } ?: return@synchronized
        if (clusterUiStream != session to stream) {
            clusterUiStream = session to stream
            clusterUiShown = true
            clusterUiVisibility = (session to stream) to true
        }
        if (shown == clusterUiShown && (!shown || session.clusterUrl() != null)) return@synchronized
        if (session.setClusterUiShown(shown)) {
            clusterUiShown = shown
            clusterUiVisibility = (session to stream) to shown
            dashboardMapEpoch.incrementAndGet()
            debugLog("Cluster map: ${if (shown) "showUI, the cluster shows the map" else "stopUI, the cluster hides the map"}")
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shihab.diplay.iap2.wire.Iap2Frame, source: String) {
        val receivedNs = System.nanoTime()
        NavigationOutputs.onFrame(frame, source)
        com.shihab.diplay.glance.CarPlayGlance.onFrame(frame, sessionListener.debugLogsEnabled)
        synchronized(playbackStatus) {
            val previous = playbackStatus.nowPlaying
            playbackStatus.acceptUpdate(frame)?.let { update ->
                val elapsed = update.elapsedMillis
                val before = previous.elapsedMillis
                // Some music apps put the current lyric line in the title, which changes every few
                // seconds while the same track plays on (v129: 243 of 451 lines). A new item shows in
                // the other fields or as a restart; a few hundred ms of elapsed jitter is not one.
                val itemChanged = update.sourceApp != previous.sourceApp || update.album != previous.album ||
                    update.artist != previous.artist || update.durationMillis != previous.durationMillis
                val restarted = elapsed != null &&
                    (before == null || elapsed < before - PLAYBACK_ELAPSED_JITTER_MILLIS)
                if (update.playing != previous.playing || itemChanged || restarted) {
                    debugLog("Playback recovery session=${System.identityHashCode(activeSession)} nowPlaying source=$source " +
                        "monotonicNs=$receivedNs previousMs=$before elapsedMs=$elapsed " +
                        "playing=${update.playing} previousPlaying=${previous.playing} itemChanged=$itemChanged")
                }
                update to (update.playing != previous.playing)
            }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    enum class InitialAlbumRefreshResult { SENT, FAILED, CLOSED, INACTIVE_SESSION, CANCELLED, CHANNEL_UNAVAILABLE }

    /** Existing metadata channel and worker; the caller owns the single-send budget. */
    fun refreshInitialNowPlaying(
        stillNeeded: () -> Boolean,
        beforeSend: () -> Boolean = { true },
        onFinished: (InitialAlbumRefreshResult) -> Unit = {},
    ) {
        var result = InitialAlbumRefreshResult.CANCELLED
        run {
            try {
                if (closed) { result = InitialAlbumRefreshResult.CLOSED; return@run }
                if (activeSession == null) { result = InitialAlbumRefreshResult.INACTIVE_SESSION; return@run }
                if (!stillNeeded()) return@run
                // The RFCOMM bootstrap is not the runtime metadata channel after wireless handoff.
                val channel = if (config.transport == CarPlayTransport.WIRELESS) wirelessRun?.runtimeChannel() else csm
                if (channel == null) {
                    result = InitialAlbumRefreshResult.CHANNEL_UNAVAILABLE
                    debugLog("album recovery skipped channel=unavailable")
                    return@run
                }
                if (closed) { result = InitialAlbumRefreshResult.CLOSED; return@run }
                if (!stillNeeded() || !beforeSend()) return@run
                // Claim only at the send boundary; preflight skips leave the budget intact.
                result = InitialAlbumRefreshResult.FAILED
                channel.send(com.shihab.diplay.iap2.message.Iap2ControlMessages.startNowPlayingUpdates(), 250)
                result = InitialAlbumRefreshResult.SENT
                debugLog("album recovery subscription sent")
            } catch (error: Exception) {
                debugLog("album recovery subscription failed type=${error.javaClass.simpleName}")
            } finally {
                onFinished(result)
            }
        }
    }

    private fun onArtworkTransfer(transfer: com.shihab.diplay.transport.Iap2ArtworkTransfer) {
        if (closed) return
        synchronized(albumReplayLock) {
            initialArtworkReplay = transfer.takeIf { it.bytes.size <= 1024 * 1024 }
        }
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        if (!hasRequiredUsbService()) return
        lifecycle.invalidateAvailability()
        synchronized(wirelessResourceLock) {
            if (!lifecycle.beginMfi()) return
        }
        onStatus(CarPlayStatus.DiscoveringMfi)
        val offlineDirectory = java.io.File(appContext.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    requireUsbManager(),
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                sampleWirelessStart(CarPlayStartupTimeline.Stage.LOCAL_MFI_READY, wirelessGeneration)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            reportCh341Missing(host)
            waitForMfi()
        } else {
            debugLog("mfi CH341 listed ${Ch341UsbHost.describe(device)}")
            lifecycle.invalidateAvailability()
            requestCh341Permission(device)
        }
    }

    // "MFi coprocessor not present" said only that no configured CH341 was listed. What Android does list shows
    // whether the adapter left the bus or came back under another identity after a session (#792). Each poll
    // reports only a change.
    private fun reportCh341Missing(host: Ch341UsbHost) {
        val listed = runCatching { host.listedDevices().joinToString(", ") }.getOrElse { "unavailable" }
        if (listed == lastCh341Listing) return
        lastCh341Listing = listed
        debugLog("mfi CH341 not listed by Android; USB devices=[$listed]")
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    lifecycle.resetPermissionClaim()
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    lifecycle.resetPermissionClaim()
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!lifecycle.claimPermission()) return
                lifecycle.invalidatePermission()
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                lifecycle.permissionDenied()
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = lifecycle.invalidatePermission()
        val deadlineNanos = ControllerLifecycleCore.permissionDeadline(System.nanoTime())
        val check = object : Runnable {
            override fun run() {
                if (!lifecycle.permissionPollCurrent(generation, mfi = true)) return
                if (requireUsbManager().hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (ControllerLifecycleCore.permissionExpired(deadlineNanos, System.nanoTime())) {
                    if (lifecycle.claimPermission()) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun sampleWirelessStart(stage: CarPlayStartupTimeline.Stage, generation: Int,
                                    attempt: Int? = null) {
        if (config.transport == CarPlayTransport.WIRELESS) {
            CarPlayStartupTimeline.record(appContext, stage, config.wirelessHotspotMode,
                generation = generation, owner = System.identityHashCode(this), attempt = attempt)
        }
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        val (previous, run) = synchronized(wirelessResourceLock) {
            if (!lifecycle.canStartWireless(expectedGeneration, wirelessRun?.hasFailed() == true)) return
            // Retire a live predecessor here so its callbacks stop at once, but release its hotspot,
            // sockets and listener off the lock: a slow SoftAP stop must not stall status posts.
            val previous = wirelessRun?.also { it.retire() }
            val run = WirelessRun(lifecycle.beginWireless()).also {
                wirelessRun = it
                onStatus(CarPlayStatus.StartingHotspot, it.generation)
            }
            previous to run
        }
        // Closing the old socket must unblock its control loop before we queue more work behind it.
        Thread({
            previous?.closeWirelessStack()
            synchronized(wirelessResourceLock) {
                if (run.acceptsProgress()) executor.execute { run.runWireless() }
            }
        }, "xcertplay-wireless-start").apply {
            isDaemon = true
            start()
        }
    }

    private fun restartWireless(expected: WirelessRun) {
        val generation = synchronized(wirelessResourceLock) {
            if (!lifecycle.canRestartWireless(wirelessRun === expected, expected.acceptsProgress())) return
            expected.retire()
            lifecycle.invalidateWireless()
        }
        Thread(
            {
                expected.closeWirelessStack()
                startWireless(generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startIphone() {
        synchronized(wirelessResourceLock) {
            if (!lifecycle.beginIphone()) return
        }
        lifecycle.invalidateAvailability()
        lifecycle.invalidatePermission()
        lifecycle.resetIphoneDiscovery()
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            reportIphoneMissing()
            val now = SystemClock.elapsedRealtime()
            onStatus(
                if (lifecycle.iphoneNotListed(now)) CarPlayStatus.IphoneNotListed(iphoneHost.listsNonHubDevice())
                else CarPlayStatus.WaitingForIphone,
            )
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            lastIphoneListing = null
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            lifecycle.invalidateAvailability()
            requestIphonePermission(device)
        }
    }

    // "iPhone USB device not present" said only that no Apple device was listed. What Android does list shows
    // whether it sees anything on the head unit's port, a USB stick for example, or nothing at all (#763).
    // Each poll reports only a change.
    private fun reportIphoneMissing() {
        val listed = runCatching { iphoneHost.listedDevices().joinToString(", ") }.getOrElse { "unavailable" }
        if (listed == lastIphoneListing) return
        lastIphoneListing = listed
        debugLog("wired iPhone not listed by Android; USB devices=[$listed]")
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice, allowPlatformFallback: Boolean = true) {
        if (closed) return
        if (lifecycle.grantPending(usbPlatformGrantKey(device))) return
        try {
            when (val request = iphoneHost.requestPermission(device, allowPlatformFallback)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    lifecycle.resetPermissionClaim()
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog(
                        "wired iPhone USB permission requested " +
                            "afterMissingUiGrantFailure=${request.afterMissingUiGrantFailure}",
                    )
                    lifecycle.resetPermissionClaim()
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device, request.afterMissingUiGrantFailure)
                }
                is IphoneUsbHost.PermissionRequest.PlatformGrantRequired -> {
                    debugLog("wired iPhone USB permission route=platform; system permission UI unavailable")
                    lifecycle.resetPermissionClaim()
                    lifecycle.invalidatePermission()
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    attemptIphoneUsbGrant(request.device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun usbPlatformGrantKey(device: UsbDevice): String =
        "$phase:$reenumerationAttempts:${device.deviceName}:${device.deviceId}"

    private fun attemptIphoneUsbGrant(device: UsbDevice) {
        val key = usbPlatformGrantKey(device)
        if (!lifecycle.claimPlatformGrant(key)) {
            doRequestIphonePermission(device, allowPlatformFallback = false)
            return
        }
        lifecycle.platformGrantStarted(key)
        val grantPhase = phase
        val grantGeneration = permissionPollGeneration
        connectionDiagnostic(
            "USB platform permission starting phase=$phase reenumerationAttempts=$reenumerationAttempts",
        )
        executor.execute {
            val result = runCatching { IphoneUsbGrant.grant(appContext, device) }.getOrDefault(IphoneUsbGrant.Code.ERROR)
            connectionDiagnostic("USB platform permission result=$result")
            mainHandler.post {
                if (!lifecycle.platformGrantCurrent(grantPhase.ordinal, grantGeneration)) return@post
                // A stale grant must not clear the same key owned by a newer reconnect generation.
                lifecycle.platformGrantFinished(key)
                if (requireUsbManager().hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                } else {
                    doRequestIphonePermission(device, allowPlatformFallback = false)
                }
            }
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!lifecycle.claimPermission()) return
                debugLog("wired iPhone USB permission granted")
                lifecycle.invalidatePermission()
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        val carPlayMode = configuration?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true
                        val sessionAlreadyStarted = WiredSessionMemory.startedOn(result.device.deviceName)
                        val action = WiredConfigurationAction.choose(
                            found = configuration != null,
                            carPlayMode = carPlayMode,
                            transitionsTried = reenumerationAttempts,
                            maximumTransitions = MAXIMUM_REENUMERATION_ATTEMPTS,
                            sessionAlreadyStarted = sessionAlreadyStarted,
                        )
                        connectionDiagnostic(
                            "USB configuration ready=${configuration != null} " +
                                "configurationId=${configuration?.id ?: "none"} " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                (if (sessionAlreadyStarted && carPlayMode) "previousSessionOnThisEnumeration=true " else "") +
                                "action=${action.diagnostic}",
                        )
                        when (action) {
                            WiredConfigurationAction.USE,
                            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET -> openDataPaths(result.device)
                            WiredConfigurationAction.REQUEST_TRANSITION -> beginReenumeration(
                                result.device,
                                WiredConfigurationAction.fallbackAfterRejection(
                                    configuration != null, carPlayMode, sessionAlreadyStarted),
                            )
                            WiredConfigurationAction.REJECT -> fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                lifecycle.permissionDenied()
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice, afterMissingUiGrantFailure: Boolean = false) {
        val generation = lifecycle.invalidatePermission()
        // A custom OEM permission page may still be visible even when the standard AOSP component
        // is absent. Preserve the full user-response window after the platform grant fails.
        val deadlineNanos = ControllerLifecycleCore.permissionDeadline(System.nanoTime())
        val check = object : Runnable {
            override fun run() {
                if (!lifecycle.permissionPollCurrent(generation, mfi = false)) return
                if (requireUsbManager().hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (ControllerLifecycleCore.permissionExpired(deadlineNanos, System.nanoTime())) {
                    if (lifecycle.claimPermission()) {
                        onStatus(
                            CarPlayStatus.Failed(
                                if (afterMissingUiGrantFailure) {
                                    "The head unit's standard USB authorization page is unavailable, and neither " +
                                        "the platform grant nor its system permission request granted iPhone access"
                                } else {
                                    "iPhone USB permission was not granted; tap Reconnect iPhone to retry"
                                },
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    /** [fallbackAfterRejection]: the iPhone already offers USBMUX and NCM, so try that if it refuses. */
    private fun beginReenumeration(device: UsbDevice, fallbackAfterRejection: Boolean = false) {
        lifecycle.beginReenumeration()
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    mainHandler.post { watchReenumeration(device) }
                }
                is IphoneUsbHost.TransitionResult.Failed -> if (fallbackAfterRejection) {
                    connectionDiagnostic(
                        "USB transition rejected; using the configuration the iPhone offers " +
                            "error=${transition.error.message}",
                    )
                    mainHandler.post {
                        if (!closed && phase == Phase.REENUMERATION) openDataPaths(device)
                    }
                } else {
                    fail(transition.error)
                }
            }
        }
    }

    /**
     * Only a USB attach ended the wait for the iPhone to come back in CarPlay mode, and an iPhone that ignores
     * the request never attaches again: after Save and reconnect an iPhone still in the mode of the ended
     * session left wired CarPlay waiting for minutes (#335). Without an attach the iPhone is looked at again,
     * which uses the mode it already offers, as before the request.
     */
    private fun watchReenumeration(device: UsbDevice) {
        val attempt = reenumerationAttempts
        val generation = availabilityPollGeneration
        mainHandler.postDelayed({
            if (!lifecycle.reenumerationCurrent(attempt, generation)) return@postDelayed
            // An iPhone that left the bus is still re-enumerating: its attach ends the wait.
            val listed = iphoneHost.discover().firstOrNull { it.deviceName == device.deviceName } ?: return@postDelayed
            connectionDiagnostic(
                "USB transition unanswered after ${REENUMERATION_WAIT_MILLIS}ms count=$attempt; checking the iPhone again",
            )
            requestIphonePermission(listed)
        }, REENUMERATION_WAIT_MILLIS)
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                lifecycle.invalidateAvailability()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration
        mainHandler.postDelayed(
            {
                if (
                    lifecycle.availabilityPollCurrent(phase.ordinal, generation)
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        val run = synchronized(wirelessResourceLock) {
            lifecycle.beginDataPaths() ?: return
        }
        wiredDeviceName = device.deviceName
        debugLog("wired opening iPhone USB data paths")
        onWiredStatus(CarPlayStatus.SelectingConfiguration, run)
        onWiredStatus(CarPlayStatus.OpeningDataPaths, run)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            onDataPathsOpened(device, run, result)
        }
    }

    private fun isWiredRunCurrent(run: Int): Boolean =
        lifecycle.wiredCurrent(run)

    private fun onDataPathsOpened(device: UsbDevice, run: Int, result: IphoneUsbHost.Iap2SessionResult) {
        if (!isWiredRunCurrent(run)) {
            if (result is IphoneUsbHost.Iap2SessionResult.Connected) {
                closeBestEffort("USBMUX") { result.session.close() }
            }
            return
        }
        when (result) {
            is IphoneUsbHost.Iap2SessionResult.Connected -> {
                try {
                    val ncm = openNcm(device)
                    runStack(result.session, ncm, run)
                } catch (error: Throwable) {
                    result.session.close()
                    failWired(error, run)
                }
            }
            is IphoneUsbHost.Iap2SessionResult.Failed -> failWired(result.error, run)
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = requireUsbManager().openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function, onDiagnostic = ::connectionDiagnostic)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge, run: Int) {
        fun report(status: CarPlayStatus) = onWiredStatus(status, run)
        var usbOwnedLocally = true
        var ncmOwnedLocally = true
        var ownedStartWatchdog: WiredStartWatchdog? = null
        var ownedMux: Iap2UsbMuxHost? = null
        var ownedCsm: Iap2Session? = null
        var ownedVpn: CarPlayVpnService.WiredOwner? = null
        try {
            synchronized(wirelessResourceLock) {
                if (!lifecycle.beginWiredControl(run)) return
            }
            // The factory owns the pipe from here, including when its handshake throws.
            usbOwnedLocally = false
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            var previous: WiredResources? = null
            val adopted = synchronized(wirelessResourceLock) {
                if (!isWiredRunCurrent(run)) false else {
                    previous = WiredResources(this.csm, this.mux, wiredVpnOwner)
                    this.csm = null
                    this.mux = mux
                    wiredVpnOwner = null
                    true
                }
            }
            if (!adopted) {
                closeBestEffort("USBMUX") { mux.close() }
                return
            }
            ownedMux = mux
            previous?.let(::closeWiredResources)
            debugLog("wired USBMUX host opened")
            report(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            report(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            // Lab transport diagnostics: packet headers only, never certificate or challenge data.
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : com.shihab.diplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        debugLog("wired link TX begin ${wireSummary(data)}")
                        // Bound each TLS write while diagnosing the stalled certificate transfer.
                        for (offset in data.indices step 256) {
                            carkit.send(data.copyOfRange(offset, minOf(offset + 256, data.size)))
                        }
                        debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            val csmAdopted = synchronized(wirelessResourceLock) {
                if (!isWiredRunCurrent(run)) false else { this.csm = csm; true }
            }
            if (!csmAdopted) {
                closeBestEffort("stale CSM") { csm.close() }
                return
            }
            ownedCsm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            val vpnOwner = CarPlayVpnService.WiredOwner(run) { lifecycle.wiredCurrent(run) }
            ownedVpn = vpnOwner
            if (!attachVpn(ncm, ncmHostMac, vpnOwner)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (!isWiredRunCurrent(run)) {
                vpnService?.detachWired(vpnOwner)
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = vpnService?.boundPort() ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            report(CarPlayStatus.RunningControl)
            lifecycle.sessionActive(false)
            lifecycle.startSent(null)
            fun startAgain(): Iap2WiredControlClient.StartAgain {
                val opened = ncm.linkOpenedAtNanos
                return WiredStartAgain.decide(
                    nowNanos = System.nanoTime(),
                    startSentAtNanos = wiredStartSentAtNanos ?: System.nanoTime(),
                    linkOpenedAtNanos = opened,
                    sessionActive = wiredSessionActive,
                    connectionAttemptSinceLinkOpened =
                        opened != null && ncm.connectionAttemptSince(endpoint.airPlayPort, opened),
                )
            }
            lateinit var startWatchdog: WiredStartWatchdog
            startWatchdog = WiredStartWatchdog(
                schedule = { delay, action ->
                    val future = runCatching {
                        startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    }.getOrNull()
                    val cancel: () -> Unit = { future?.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (!closed && wiredStartWatchdog === startWatchdog) {
                        val waitedSeconds = wiredStartSentAtNanos?.let {
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - it)
                        }
                        debugLog(
                            "wired start: no AirPlay session $waitedSeconds s " +
                                "after carplay-start-session; NCM ${ncm.trafficSummary(endpoint.airPlayPort)}",
                        )
                        fail(IphoneUsbException.TimedOut("The iPhone did not open CarPlay over USB in time"))
                    }
                },
                secondRequestCanStillGoOut = { startAgain() != Iap2WiredControlClient.StartAgain.DONE },
                onHold = {
                    debugLog(
                        "wired start: no AirPlay session ${WiredStartWatchdog.TIMEOUT_MILLIS / 1000} s " +
                            "after carplay-start-session; holding the limit until " +
                            "${WiredStartWatchdog.HELD_TIMEOUT_MILLIS / 1000} s for the second request; " +
                            "NCM ${ncm.trafficSummary(endpoint.airPlayPort)}",
                    )
                },
            )
            ownedStartWatchdog = startWatchdog
            wiredStartWatchdog = startWatchdog
            debugLog(
                "wired iAP2 runtime control starting " +
                    "location=${config.identification.locationInformationEnabled} " +
                    "vehicleStatus=${config.identification.vehicleStatusEnabled}",
            )
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = { onRouteFrame(it, "wired-usb") },
                onProgress = { message ->
                    if (message == Iap2WiredControlClient.START_SESSION_AGAIN_PROGRESS) {
                        startWatchdog.startSessionSentAgain()
                    } else if (message.startsWith(START_SESSION_PROGRESS)) {
                        if (wiredStartSentAtNanos == null) lifecycle.startSent(System.nanoTime())
                        wiredDeviceName?.let(WiredSessionMemory::started)
                        startWatchdog.startSessionSent()
                    }
                    debugLog("wired $message")
                },
                startSessionAgain = ::startAgain,
            )
            report(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            // Stop control and USBMUX before releasing NCM back to the kernel driver.
            val failed = takeOwnedWiredResources(WiredResources(ownedCsm, ownedMux, ownedVpn))
            closeBestEffort("CSM") { failed.csm?.close() }
            closeBestEffort("USBMUX") { failed.mux?.close() }
            if (ncmOwnedLocally) {
                closeBestEffort("NCM") { ncm.close() }
                ncmOwnedLocally = false
            } else {
                closeBestEffort("VPN/NCM") { failed.vpn?.let { vpnService?.detachWired(it) } }
            }
            failWired(error, run)
        } finally {
            ownedStartWatchdog?.terminate()
            if (!isWiredRunCurrent(run)) {
                closeWiredResources(takeOwnedWiredResources(WiredResources(ownedCsm, ownedMux, ownedVpn)))
            }
            if (usbOwnedLocally) closeBestEffort("USBMUX") { usbSession.close() }
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
            onTrustPending = { onStatus(CarPlayStatus.WaitingForIphoneTrust) },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    // Kept across reconnects within this controller: resuming between attempts would start a scan.
    // close() sets [closed] under this lock, so a lease is either taken first and released by
    // close, or never taken.
    private fun pauseWifiScans(backend: WirelessHotspotBackend) = synchronized(wirelessResourceLock) {
        if (closed || !WifiScanPause.eligible(backend)) return@synchronized
        // Settings → Connection lets a driver keep the car's automatic joining, e.g. for the phone's hotspot (#755).
        if (!appContext.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE)
                .getBoolean("wifi_direct_scan_pause", true)) {
            if (wifiScanPause == null) debugLog("Wi-Fi scan pause off in Settings; the car keeps searching and joining networks")
            return@synchronized
        }
        (wifiScanPause ?: WifiScanPause(appContext, ::debugLog).also { wifiScanPause = it }).pause()
    }

    /** An RFCOMM link that came up too fast to have reached a phone, kept for [unrealBluetoothFailure]. */
    private class InstantBluetoothLink(
        val generation: Int,
        val device: BluetoothDevice,
        val stream: BluetoothRfcommDuplexStream,
        val connectMillis: Long,
        val attempt: WirelessBootstrapRecovery.Attempt,
    )

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }

    private fun controlLoopTimeoutMillis(): Long = lifecycle.controlLoopTimeout()

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray, owner: CarPlayVpnService.WiredOwner): Boolean {
        fun report(status: CarPlayStatus) = onWiredStatus(status, owner.generation)
        synchronized(wirelessResourceLock) {
            if (!lifecycle.wiredCurrent(owner.generation)) return false
            wiredVpnOwner = owner
        }
        report(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
                owner = owner,
            )
        } catch (error: Throwable) {
            ncm.close()
            report(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                if (!lifecycle.wiredCurrent(owner.generation)) {
                    service.detachWired(owner)
                    return false
                }
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            CarPlayVpnService.AttachResult.Superseded -> false
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message} detail=${service.lastAttachFailure}")
                ncm.close()
                report(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (!lifecycle.beginVpnBind()) return
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                lifecycle.vpnBindFailed()
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            lifecycle.vpnBindFailed()
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!lifecycle.beginVpnUnbind()) return
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun requireUsbManager(): UsbManager =
        usbManager ?: throw IOException("USB service is unavailable on this device")

    private fun hasRequiredUsbService(): Boolean {
        if (usbManager != null ||
            config.transport != CarPlayTransport.WIRED && config.mfiTarget != MfiTarget.USB_CH341) return true
        fail(IOException("USB service is unavailable on this device"))
        return false
    }

    private fun fail(error: Throwable, generation: Int? = null) = reportFailure(error, generation, null)

    private fun failWired(error: Throwable, run: Int) = reportFailure(error, null, run)

    private fun reportFailure(error: Throwable, generation: Int?, wiredRun: Int?) {
        synchronized(wirelessResourceLock) {
            if (!lifecycle.failureCurrent(generation)) return
            if (wiredRun != null && !lifecycle.wiredCurrent(wiredRun)) return
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (wireless) {
                if (generation != null && wirelessRun?.acceptsProgress() == false) return
                val reported = wirelessRun?.reportFailure()
                    ?: lifecycle.reportPrerequisiteFailure()
                if (!reported) return
            }
            val causes = generateSequence(error) { it.cause }.toList()
            val status = CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                causes.any { it is com.shihab.diplay.network.P2pResetRequiredException },
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason,
                causes.firstNotNullOfOrNull {
                    (it as? ConnectionFailureException)?.reason ?: (it as? WirelessStartupException)?.knownCause
                },
                when (causes.filterIsInstance<LockdownPairingException>().firstOrNull()) {
                    LockdownPairingException.UserDeniedPairing -> UsbPairingFailure.TRUST_REFUSED
                    LockdownPairingException.PasswordProtected -> UsbPairingFailure.PHONE_LOCKED
                    LockdownPairingException.TimedOut -> UsbPairingFailure.TIMED_OUT
                    LockdownPairingException.Cancelled -> UsbPairingFailure.CANCELLED
                    else -> null
                },
                automaticRetryAllowed = !wireless || !WirelessBootstrapRecovery.requiresUserAction(error))
            if (wiredRun != null) onWiredStatus(status, wiredRun)
            else onStatus(status, if (wireless) wirelessGeneration else null)
        }
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    /**
     * Renders a throwable and its cause chain with the top stack frame of each, so failures that
     * are wrapped (e.g. "USBMUX read failed" hiding the real cause) are diagnosable from the session
     * log alone, without logcat. Bounded in depth; the redactor still runs on the result.
     */
    private fun onStatus(status: CarPlayStatus, generation: Int? = null) =
        postStatus(status, generation) { true }

    private fun onWiredStatus(status: CarPlayStatus, run: Int) =
        postStatus(status, null) { lifecycle.wiredCurrent(run) }

    private fun onBootstrapStatus(status: CarPlayStatus, generation: Int, attempt: WirelessBootstrapRecovery.Attempt) =
        postStatus(status, generation) { attempt.isCurrent }

    private fun postStatus(status: CarPlayStatus, generation: Int?, isCurrent: () -> Boolean) {
        if (closed) return
        mainHandler.post {
            // Keep a terminal phone-side outcome when cleanup reports a second error, including
            // for a later Activity adopting this controller. Manual Retry creates a new controller.
            if ((lastReportedStatus as? CarPlayStatus.Failed)?.usbPairingFailure != null) return@post
            if (!closed && isCurrent() && lifecycle.statusCurrent(generation, current = true,
                    failed = status is CarPlayStatus.Failed, liveRun = wirelessRun?.acceptsProgress() == true,
                    same = status == lastReportedStatus)) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    /** One wireless generation; the controller lock orders replacement and shared-service commits. */
    private inner class WirelessRun(val generation: Int) {
        private val state = WirelessRunState()
        private val wirelessBluetooth = WirelessBluetoothConnection(
            appContext, config.wirelessBluetoothDeviceAddress, ::debugLog, ::connectionDiagnostic,
        )
        @Volatile private var hotspot: WirelessHotspotManager? = null
        @Volatile private var attachedService: CarPlayVpnService? = null
        @Volatile private var bonjour: CarPlayBonjour? = null
        @Volatile private var instantBluetoothLink: InstantBluetoothLink? = null
        @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
        @Volatile private var bootstrapWatchdog: Iap2BootstrapWatchdog? = null
        @Volatile private var wirelessPublishedAddress: InetAddress? = null
        @Volatile private var wirelessAlternateAddress: InetAddress? = null
        @Volatile private var wirelessAddressRecovery: HotspotAddressRecovery? = null
        @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
        @Volatile private var bootstrapAttempt: WirelessBootstrapRecovery.Attempt? = null
        @Volatile private var bluetoothSocket: BluetoothSocket? = null
        @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
        @Volatile private var wirelessTunnelChannel: Iap2Session? = null
        @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
        @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
        private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()
        @Volatile private var csm: Iap2Session? = null

        fun runWireless() {
            // The host rebuilds the whole stack 15 s after the tunnel drops, so a new controller must
            // still know the car turned its radios off.
            val radioReturn = lifecycle.consumeRadioReturn() or com.shihab.diplay.network.CarRadioOutage.active()
            // Until RFCOMM starts, a failure belongs to this bring-up even while the old session winds down.
            var bluetoothLinkStarted = false
            try {
                sampleWirelessStart(CarPlayStartupTimeline.Stage.WIRELESS_RUN_START, generation)
                debugLog("wireless bring-up generation=$generation starting")
                if (!state.running) return

                val listenerIdentity = AirPlayListenerIdentity(generation)
                val watchdog = FirstTcpWatchdog(
                    listener = listenerIdentity,
                    schedule = { delay, action ->
                        val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                        val cancel: () -> Unit = { future.cancel(false); Unit }
                        cancel
                    },
                    onTimeout = {
                        whileRunning {
                            if (shouldFlipAddressFamily()) {
                                flipWirelessAddressFamily()
                            } else {
                                debugLog(
                                    "wireless first-TCP timeout kept address family: " +
                                        "no evidence the iPhone is on the hotspot " +
                                        "(association=${hotspot?.connectionDiagnosticSnapshot() ?: "unknown"} " +
                                        "reachable=${state.addressFamilyReachable})",
                                )
                            }
                            fail(WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                                "No AirPlay TCP after CarPlay StartSession"), generation)
                            Thread({ closeWirelessStack(generation = generation) }, "diplay-startup-cleanup")
                                .apply { isDaemon = true; start() }
                        }
                    },
                    log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
                )
                firstTcpWatchdog = watchdog
                val mfi = mfiSession?.client
                    ?: throw IOException("MFi coprocessor client is unavailable")
                // Only cold-start workers, and the restart after the car turned its hotspot off, may wait
                // for an OEM radio that is still OFF. The observation never enables Bluetooth and remains
                // cancellable. Engine off then the driver door turns hotspot and Bluetooth off together;
                // waking the screen turns them back on, which a run that gave up at once never saw, so
                // CarPlay stayed down until the driver reconnected (v140 car report, 2026-11-08).
                val coldStart = android.os.SystemClock.elapsedRealtime() in
                    0 until BluetoothAdapterRestore.EARLY_BOOT_WINDOW_MILLIS
                fun awaitBluetoothRadio() {
                    CarBluetoothProfileGuard.requireAdapterReadyForConnection(
                        appContext,
                        isCurrent = { state.running },
                        log = { debugLog(it) },
                        passiveWaitMillis = when {
                            radioReturn -> RADIO_RETURN_WAIT_MILLIS
                            coldStart -> BluetoothAdapterRestore.PASSIVE_BOOT_WAIT_MILLIS
                            else -> 0L
                        },
                    )
                    sampleWirelessStart(CarPlayStartupTimeline.Stage.BLUETOOTH_READY, generation)
                }
                // Wireless CarPlay begins over Bluetooth: a radio that is off is found before the Wi-Fi is
                // changed. A cold start is the exception: the boot hotspot is already up, and the head unit
                // restarts its Bluetooth after unlock, which took 6 s while hotspot, listener and Bonjour
                // waited behind it (car test 2026-11-07). Those need no radio, so they go first and the
                // wait moves to just before the iPhone is chosen.
                if (!coldStart) awaitBluetoothRadio()
                // The car may bring Bluetooth back before its hotspot. Unless DiPlay starts the hotspot
                // itself, wait for the car instead of failing on "hotspot off" (Mi 8 bench, 2026-11-08).
                if (radioReturn && config.wirelessHotspotMode == WirelessHotspotMode.MANUAL &&
                    !com.shihab.diplay.network.CarHotspotSettings.shouldEnable(appContext, true, WirelessHotspotMode.MANUAL)
                ) {
                    val outcome = com.shihab.diplay.network.CarHotspotReturn.await(
                        state = { com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext) },
                        isCurrent = { state.running },
                        timeoutMillis = RADIO_RETURN_WAIT_MILLIS,
                        log = { debugLog("generation=$generation $it") },
                    )
                    if (outcome == com.shihab.diplay.network.CarHotspotReturn.Outcome.CANCELLED) return
                }
                // The radios go off in no fixed order either: Bluetooth may have gone off while this run
                // waited for the hotspot. Returns at once when it is on.
                if (radioReturn) awaitBluetoothRadio()
                val hotspotInfo = startWirelessHotspot(generation)
                if (!state.running) {
                    return
                }
                sampleWirelessStart(CarPlayStartupTimeline.Stage.NETWORK_BACKEND_READY, generation)
                synchronized(wirelessResourceLock) {
                    if (!state.running) return
                    pauseWifiScans(hotspotInfo.backend)
                }
                val startedHotspot = hotspot
                wirelessConnectionProof.begin(generation) {
                    whileRunning { startedHotspot?.onCarPlayConfirmed() }
                }
                val preferredFamily = HotspotAddressPolicy.parseFamily(
                    appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
                        .getString(HOTSPOT_FAMILY_PREFERENCE_KEY, null),
                )
                val addressRecovery = HotspotAddressRecovery(
                    sample = { hotspotCandidates(hotspotInfo, fallbackToSnapshot = false) },
                    cancelled = { !state.running },
                    pause = Thread::sleep,
                    log = { whileRunning { debugLog(it) } },
                )
                val candidates = addressRecovery.awaitCandidates(hotspotCandidates(hotspotInfo), preferredFamily)
                val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily)
                    ?: hotspotInfo.hostAddress
                    ?: throw IOException(
                        "Wireless hotspot did not provide a usable host address",
                    )
                synchronized(wirelessResourceLock) {
                    if (!state.running) return
                    wirelessPublishedAddress = hostAddress
                    wirelessAlternateAddress = HotspotAddressPolicy.alternateFamilyCandidate(candidates, hostAddress)
                    wirelessAddressRecovery = addressRecovery
                }
                if (
                    hostAddress is Inet6Address &&
                    (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
                ) {
                    throw IOException(
                        "Wireless hotspot link-local IPv6 address is not scoped",
                    )
                }
                sampleWirelessStart(CarPlayStartupTimeline.Stage.ENDPOINT_READY, generation)
                val hostAddressText = hostAddressText(hostAddress)
                val deviceIdentifier = hotspotInfo.bssid
                    ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                    ?: airPlayConfig.deviceId
                debugLog(
                    "wireless hotspot backend=${hotspotInfo.backend.label} " +
                        "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                        "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                        "preference=${preferredFamily ?: "default"} " +
                        "alternate=${wirelessAlternateAddress != null} " +
                        "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                        "host=$hostAddressText " +
                        "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                        "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
                )
                var startedBonjour: CarPlayBonjour? = null
                val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
                val diagnostics = WirelessStartupDiagnostics(
                    sample = {
                        "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                            "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                            (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                            receiveDiagnostics.snapshot()
                    },
                    // Closing diagnostics is synchronous with the still-owned hotspot.
                    // Keep the historical sampled line, then append its latest atomic SoftAP count.
                    finalSnapshot = {
                        startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"
                    },
                    log = { message -> whileRunning { debugLog(message) } },
                )
                synchronized(wirelessResourceLock) {
                    if (!state.running) { diagnostics.close(); return }
                    wirelessDiagnostics = diagnostics
                }
                onStatus(
                    CarPlayStatus.HotspotReady(
                        ssid = hotspotInfo.ssid,
                        band = hotspotInfo.bandLabel,
                        channel = hotspotInfo.channel,
                        bssid = deviceIdentifier,
                        address = hostAddressText,
                        backend = hotspotInfo.backend.label,
                    ),
                )
                onStatus(CarPlayStatus.WaitingForPairedIphone)

                val adapter = wirelessBluetooth.currentBluetoothAdapter()
                    ?: throw BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER)
                if (!state.running) return
                // Settings keep the local address while the radio is off, so the network side needs no radio.
                val hostBluetoothMac = accessoryBluetoothMac(adapter)
                /** The paired iPhone, after the radio is confirmed: the bonded list is empty while it is off. */
                fun chooseBluetoothTarget(): BluetoothDevice {
                    // Recheck after hotspot startup; a retained recovery marker never permits RFCOMM on OFF.
                    val radioBlocker = BluetoothRadioCheck.blockerForState(
                        adapterPresent = true,
                        state = adapter.state,
                        pausedByDiPlay = false,
                    )
                    if (radioBlocker != null) throw BluetoothRadioCheck.failure(radioBlocker)
                    return wirelessBluetooth.selectWirelessBluetoothDevice(adapter).also { device ->
                        debugLog(
                            "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                                "address=${device.address} localBt=$hostBluetoothMac",
                        )
                    }
                }
                var device = if (coldStart) null else chooseBluetoothTarget()
                val wirelessAirPlayConfig = airPlayConfig.copy(
                    deviceId = deviceIdentifier,
                    btMac = hostBluetoothMac,
                )

                // The RFCOMM link depends only on the paired target, while the listener, Bonjour and
                // endpoint preparation does not depend on Bluetooth: connect the socket in parallel
                // (the v124 real-car session C waited 235ms between bt/select and the first connect)
                // so the two waits overlap instead of adding up. Only the socket connect is
                // speculative — the bootstrap attempt below adopts it only after validateInfrastructure()
                // passes, so the Bonjour gate and the CSM/bootstrap sequencing are unchanged.
                var rfcommPreconnect = device?.let { startRfcommPreconnect(generation, it) }

                onStatus(CarPlayStatus.AttachingNetwork)
                val service = awaitVpnService()
                    ?: throw IOException("Could not bind the CarPlay AirPlay service")
                synchronized(wirelessResourceLock) {
                    if (!state.running) return
                    startedHotspot?.validateReady()
                    when (
                        val result = service.attachWireless(
                            bindAddress = hostAddress,
                            config = wirelessAirPlayConfig,
                            identity = identity,
                            pairings = pairings,
                            mfi = mfi,
                            listener = wirelessSessionListener(generation, watchdog),
                            listenerIdentity = watchdog.listener,
                            media = media,
                            additionalBindAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
                            ownerCurrent = { state.running },
                        )
                    ) {
                        CarPlayVpnService.AttachResult.Started -> { attachedService = service }
                        CarPlayVpnService.AttachResult.AlreadyStarted ->
                            throw IOException("Wireless AirPlay transport is already attached")
                        CarPlayVpnService.AttachResult.Superseded -> return
                        is CarPlayVpnService.AttachResult.Failed -> {
                            debugLog("wireless AirPlay listener attach failed detail=${service.lastAttachFailure}")
                            throw IOException(result.message)
                        }
                    }
                }
                sampleWirelessStart(CarPlayStartupTimeline.Stage.AIRPLAY_LISTENING, generation)
                val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
                val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
                debugLog(
                    "wireless AirPlay listener attached bind=$hostAddressText " +
                        "port=$listenerPort" +
                        (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
                )
                if (!state.running) {
                    return
                }

                val bonjourClient = CarPlayBonjour(
                    context = appContext,
                    config = advertisedAirPlayConfig,
                    identity = identity,
                    advertisedHost = hostAddress.hostAddress,
                    // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                    // The car hotspot previously used system NSD, which could resolve another interface
                    // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                    useInterfaceMdns = true,
                    onEvent = { event -> whileRunning {
                        if (event is CarPlayBonjourEvent.Discovery || event is CarPlayBonjourEvent.Resolved) {
                            // The iPhone showed up on this address family over mDNS; remember that the
                            // family is usable so a later first-TCP timeout can be blamed on the family.
                            state.markAddressFamilyReachable()
                        }
                        debugLog("wireless bonjour: ${event.diagnosticSummary()}")
                    } },
                    additionalAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
                )
                synchronized(wirelessResourceLock) {
                    if (!state.running) return
                    bonjour = bonjourClient
                    startedHotspot?.validateReady()
                    bonjourClient.start()
                }
                startedBonjour = bonjourClient
                sampleWirelessStart(CarPlayStartupTimeline.Stage.BONJOUR_STARTED, generation)
                synchronized(wirelessResourceLock) {
                    if (!state.running) return
                    diagnostics.start()
                }
                debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
                if (!state.running) {
                    return
                }

                val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
                val bootstrapIdentification = config.identification.forWirelessLink(
                    Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                    wirelessIdentification,
                )
                val runtimeIdentification = config.identification.forWirelessLink(
                    Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                    wirelessIdentification,
                )
                val endpoint = Iap2WirelessCarPlayEndpoint(
                    ssid = hotspotInfo.ssid,
                    passphrase = hotspotInfo.passphrase,
                    channel = hotspotInfo.channel,
                    security = hotspotInfo.security,
                    ipAddresses = listOf(hostAddressText),
                    airPlayPort = listenerPort,
                    deviceIdentifier = deviceIdentifier,
                    publicKey = identity.publicKeyHex,
                    sourceVersion = airPlayConfig.sourceVersion,
                    accessPointBssid = hotspotInfo.accessPointBssid,
                )
                synchronized(wirelessResourceLock) {
                    if (!state.running) return
                    wirelessRuntimeIdentification = runtimeIdentification
                    wirelessAirPlayEndpoint = endpoint
                    media.setIapTunnelHandler(::startWirelessTunnelControl)
                }
                debugLog(
                    "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                        "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                        "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
                )
                fun handoffStarted(): Boolean = state.handoffStarted(wirelessTunnelChannel != null, activeSession != null)
                fun validateInfrastructure() = synchronized(wirelessResourceLock) {
                    if (!state.running) throw java.util.concurrent.CancellationException("Stale wireless run")
                    if (hotspot !== startedHotspot || bonjour !== bonjourClient || vpnService !== service ||
                        !service.isWirelessAttached(watchdog.listener) || !bonjourClient.isRunning()) {
                        throw IOException("Wireless infrastructure changed during Bluetooth bootstrap")
                    }
                    requireNotNull(startedHotspot).validateReady()
                }
                if (device == null) {
                    // Cold start: the network side is up; now the part that needs the radio.
                    awaitBluetoothRadio()
                    if (!state.running) return
                    device = chooseBluetoothTarget()
                    rfcommPreconnect = startRfcommPreconnect(generation, device)
                }
                val bluetoothTarget = requireNotNull(device)
                val preconnect = requireNotNull(rfcommPreconnect)
                WirelessBootstrapRecovery(
                    isCurrent = { state.running },
                    validateInfrastructure = ::validateInfrastructure,
                    handoffStarted = ::handoffStarted,
                    log = { connectionDiagnostic("wireless generation=$generation $it") },
                ).run(
                    target = bluetoothTarget,
                    validateTarget = { target -> wirelessBluetooth.validateBluetoothTarget(adapter, target) },
                    fallback = { target -> wirelessBluetooth.wirelessBluetoothFallback(adapter, target) },
                ) { target, attempt ->
                    var completed = false
                    var bootstrapFailure: Throwable? = null
                    var stageStarted = System.nanoTime()
                    try {
                        // Resolve the speculative connect before taking the resource lock: the wait can
                        // last a full connect budget, and close/restart must stay responsive meanwhile.
                        val preconnected = preconnect.consume(
                            target.address,
                            RFCOMM_CONNECT_TIMEOUT_MILLIS + RFCOMM_PRECONNECT_WAIT_MARGIN_MILLIS,
                        )
                        val socket = synchronized(wirelessResourceLock) {
                            try {
                                attempt.checkCurrent()
                                if (handoffStarted()) throw java.util.concurrent.CancellationException("Wireless handoff started")
                                // A pre-connected socket is adopted only after the infrastructure (Bonjour
                                // included) is validated; a pre-connect failure is rethrown after the same
                                // check, so the recovery classification and the Bonjour gate are unchanged.
                                validateInfrastructure()
                            } catch (error: Throwable) {
                                // This attempt never owned the transport, so its cleanup will not close the
                                // connected pre-connect; the retry would open a second RFCOMM link beside it.
                                if (preconnected is RfcommPreconnect.Outcome.Adopted) {
                                    closeOwnedBluetoothSocket(preconnected.socket, "unadopted preconnect")
                                }
                                throw error
                            }
                            bootstrapAttempt = attempt
                            bluetoothLinkStarted = true
                            when (preconnected) {
                                is RfcommPreconnect.Outcome.Adopted -> preconnected.socket
                                is RfcommPreconnect.Outcome.Failed -> throw preconnected.error
                                RfcommPreconnect.Outcome.Unavailable -> {
                                    // A socket still registered here belongs to an attempt that ended before it
                                    // owned the transport. Never leave it open beside the new one.
                                    bluetoothSocket?.let { closeOwnedBluetoothSocket(it, "leftover") }
                                    // A failed socket is never reused, including after a protocol-layer failure.
                                    target.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                                        .also { bluetoothSocket = it }
                                }
                            }
                        }
                        val connectMillis: Long
                        if (preconnected is RfcommPreconnect.Outcome.Adopted) {
                            connectMillis = preconnected.connectMillis
                            connectionDiagnostic(
                                "Bluetooth preconnect adopted attempt=${attempt.number} connectMs=$connectMillis",
                            )
                        } else {
                            onBootstrapStatus(CarPlayStatus.ConnectingBluetooth, generation, attempt)
                            wirelessBluetooth.logBluetoothConnectionSnapshot(target, "before-connect-${attempt.number}")
                            val started = System.nanoTime()
                            try {
                                wirelessBluetooth.connectBluetoothSocket(socket, target.address) { attempt.isCurrent }
                            } catch (error: Exception) {
                                connectionDiagnostic("Bluetooth connect failed attempt=${attempt.number} " +
                                    "elapsedMs=${elapsedMillis(started)} failureClass=${diagnosticFailureClass(error)}")
                                throw error
                            }
                            connectMillis = elapsedMillis(started)
                            connectionDiagnostic("Bluetooth connect completed attempt=${attempt.number} elapsedMs=$connectMillis")
                            sampleWirelessStart(CarPlayStartupTimeline.Stage.RFCOMM_CONNECTED, generation, attempt.number)
                        }
                        attempt.stage = WirelessBootstrapRecovery.Stage.CSM
                        stageStarted = System.nanoTime()
                        val bootstrap = synchronized(wirelessResourceLock) {
                            attempt.checkCurrent()
                            bootstrapWatchdog?.terminate()
                            Iap2BootstrapWatchdog(
                                schedule = { delay, action ->
                                    val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                                    val cancel: () -> Unit = { future.cancel(false); Unit }
                                    cancel
                                },
                                onTimeout = { stall ->
                                    // Never block the shared timer on a transport close. Recheck ownership
                                    // in the cleanup thread: a new attempt or handoff may already have won.
                                    Thread({ expireBootstrapAttempt(generation, attempt, stall) },
                                        "diplay-bootstrap-attempt-timeout").apply { isDaemon = true; start() }
                                },
                                log = { if (attempt.isCurrent) debugLog("wireless generation=$generation attempt=${attempt.number} $it") },
                            ).also {
                                bootstrapWatchdog = it
                                it.mark(Iap2BootstrapPhase.RFCOMM_CONNECTED)
                            }
                        }
                        val stream = synchronized(wirelessResourceLock) {
                            attempt.checkCurrent()
                            try {
                                BluetoothRfcommDuplexStream(socket, ::connectionDiagnostic).also { bluetoothStream = it }
                            } finally {
                                if (bluetoothSocket === socket) bluetoothSocket = null
                            }
                        }
                        if (BluetoothLinkCheck.looksUnreal(connectMillis, 0L)) {
                            InstantBluetoothLink(generation, target, stream, connectMillis, attempt).also {
                                instantBluetoothLink = it
                                watchForUnrealBluetooth(it)
                            }
                        }
                        val channel = synchronized(wirelessResourceLock) {
                            attempt.checkCurrent()
                            Iap2Session.openWireless(
                                stream,
                                traceContext = "wireless-rfcomm-${attempt.number}",
                                onTrace = { if (attempt.isCurrent) debugLog(it) },
                                onArtwork = { if (attempt.isCurrent) onArtworkTransfer(it) },
                            ).also { csm = it }
                        }
                        if (!channel.awaitReady(WirelessBootstrapRecovery.CSM_TIMEOUT_MILLIS)) {
                            throw IOException("Timed out or closed waiting for wireless iAP2 CSM readiness")
                        }
                        attempt.checkCurrent()
                        bootstrap.mark(Iap2BootstrapPhase.CSM_ESTABLISHED)
                        connectionDiagnostic("wireless attempt=${attempt.number} stage=CSM ready elapsedMs=${elapsedMillis(stageStarted)}")
                        attempt.stage = WirelessBootstrapRecovery.Stage.BOOTSTRAP
                        stageStarted = System.nanoTime()
                        onBootstrapStatus(CarPlayStatus.RunningWireless, generation, attempt)
                        debugLog(
                            "wireless Bluetooth iAP2 bootstrap starting " +
                                "location=false vehicleStatus=false",
                        )
                        startedHotspot?.validateReady()
                        val result = Iap2WirelessControlClient(
                            session = channel,
                            mfi = Iap2MfiAuthenticationClient(mfi),
                        ).run(
                            identification = bootstrapIdentification,
                            endpoint = endpoint,
                            timeoutMillis = controlLoopTimeoutMillis(),
                            keepAliveAfterDeadline = { attempt.isCurrent && keepBluetoothControlAlive(generation) },
                            handshakeTimeoutMillis = WirelessBootstrapRecovery.HANDSHAKE_TIMEOUT_MILLIS,
                            onReady = {
                                attempt.checkCurrent()
                                connectionDiagnostic("wireless attempt=${attempt.number} stage=BOOTSTRAP authenticated " +
                                    "elapsedMs=${elapsedMillis(stageStarted)}")
                            },
                            transportWaitMillis = WIRELESS_TRANSPORT_WAIT_MILLIS,
                            beforeStartSession = {
                                // The phone may receive 0x4301 even if the write later fails. Stop
                                // local retries before writing any of that frame, not after it succeeds.
                                beginStartSessionDispatch(generation, attempt, ::validateInfrastructure)
                            },
                            onStartSessionSent = {
                                if (attempt.isCurrent) {
                                    watchdog.startSessionSent(it.sentAtNanos)
                                    sampleWirelessStart(CarPlayStartupTimeline.Stage.START_SESSION_SENT, generation, attempt.number)
                                }
                            },
                            onBootstrapPhase = { reached ->
                                if (attempt.isCurrent) {
                                    bootstrap.mark(reached)
                                    when (reached) {
                                        Iap2BootstrapPhase.CSM_ESTABLISHED -> sampleWirelessStart(
                                            CarPlayStartupTimeline.Stage.IAP2_CSM_READY, generation, attempt.number)
                                        Iap2BootstrapPhase.MFi_AUTHENTICATED -> sampleWirelessStart(
                                            CarPlayStartupTimeline.Stage.IPHONE_MFI_ACCEPTED, generation, attempt.number)
                                        else -> Unit
                                    }
                                }
                            },
                            onIncoming = { if (attempt.isCurrent) onRouteFrame(it, "wireless-rfcomm") },
                            onProgress = { message ->
                                attempt.checkCurrent()
                                diagnostics.controlProgress(message)
                                debugLog(message)
                            },
                        )
                        if (!state.running) {
                            return@run
                        }
                        when (result.terminal) {
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                                debugLog(
                                    "wireless RFCOMM EOF: iap2State=${result.stage} " +
                                        "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                                        "transportIdentifier=${result.transportNotificationSeen} " +
                                        "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                                        "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                                        "handoffRequested=${state.handoffRequested} " +
                                        "tunnelReady=${state.tunnelReady} " +
                                        "wirelessActive=${state.activeReported}",
                                )
                                if (!state.activeReported) {
                                    val handoffInProgress = isWirelessHandoffInProgress(
                                        handoffRequested = state.handoffRequested,
                                        tunnelActive = wirelessTunnelChannel != null,
                                        sessionActive = activeSession != null,
                                    )
                                    if (!handoffInProgress) {
                                        // The link can die before the transport wait expires — the iPhone drops
                                        // it a few seconds after answering "unavailable". That answer still names
                                        // the reason better than a link error does.
                                        if (result.wirelessUnavailableReported) {
                                            throw ConnectionFailureException(
                                                ConnectionFailureCause.WIRELESS_CARPLAY_UNAVAILABLE,
                                                "The iPhone reported that this car cannot use wireless CarPlay, then " +
                                                    "closed the Bluetooth iAP2 link at state=${result.stage}",
                                            )
                                        }
                                        throw IOException(
                                            "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                                        )
                                    }
                                    debugLog(
                                        "wireless Bluetooth bootstrap closed during handoff; " +
                                            "keeping the Wi-Fi AirPlay tunnel alive",
                                    )
                                }
                            }
                            Iap2WirelessControlTerminal.TIMED_OUT ->
                                if (!state.activeReported) {
                                    onStatus(CarPlayStatus.ControlEnded)
                                }
                            Iap2WirelessControlTerminal.NO_WIRELESS_TRANSPORT -> {
                                // The iPhone authenticated over Bluetooth and then never asked for the Wi-Fi
                                // transport. Naming the reason beats waiting out the five-minute control budget:
                                // the screen just says it is connecting, and the driver has nothing to act on.
                                debugLog(
                                    "wireless CarPlay never started: iap2State=${result.stage} " +
                                        "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                                        "reportedUnavailable=${result.wirelessUnavailableReported} " +
                                        "wifiConfigs=${result.wifiConfigurationsSent} " +
                                        "carPlayStartSessions=${result.carPlayStartSessionsSent}",
                                )
                                throw ConnectionFailureException(
                                    if (result.wirelessUnavailableReported) {
                                        ConnectionFailureCause.WIRELESS_CARPLAY_UNAVAILABLE
                                    } else {
                                        ConnectionFailureCause.WIRELESS_TRANSPORT_NOT_STARTED
                                    },
                                    "The iPhone authenticated but did not start the wireless CarPlay transport " +
                                        "(wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen}, " +
                                        "reportedUnavailable=${result.wirelessUnavailableReported}, " +
                                        "iap2State=${result.stage})",
                                )
                            }
                        }
                        completed = true
                    } catch (error: Throwable) {
                        val failure = attempt.failure(error)
                        if (isExpectedHandoffControlClose(
                                attempt.stage, failure, state.activeReported, state.tunnelReady,
                            )) {
                            // The owner intentionally closed RFCOMM after the Wi-Fi tunnel became active.
                            // Treat the resulting CSM IOException as an expected handoff, not a failed attempt.
                            completed = true
                            connectionDiagnostic("wireless attempt=${attempt.number} stage=CONTROL handoff-completed " +
                                "elapsedMs=${elapsedMillis(stageStarted)} reason=expected-csm-close")
                        } else {
                            bootstrapFailure = failure
                            connectionDiagnostic("wireless attempt=${attempt.number} stage=${attempt.stage} failed " +
                                "elapsedMs=${elapsedMillis(stageStarted)} failureClass=${diagnosticFailureClass(failure)}")
                            throw failure
                        }
                    } finally {
                        if (!handoffStarted()) {
                            // A control probe can take a full connect timeout: never hold the resource
                            // lock while probing, so close/restart can invalidate and cancel this attempt.
                            val suspect = synchronized(wirelessResourceLock) {
                                instantBluetoothLink?.takeIf {
                                    !completed && bootstrapFailure?.let(WirelessBootstrapRecovery::retryable) == true &&
                                        it.attempt === attempt && attempt.isCurrent
                                }
                            }
                            val unreal = suspect?.let { unrealBluetoothFailure(it, "bootstrap-failure") }
                            synchronized(wirelessResourceLock) {
                                if (bootstrapAttempt === attempt && !handoffStarted()) {
                                    val reportUnreal = attempt.isCurrent && unreal != null
                                    closeBluetoothBootstrapTransport()
                                    if (reportUnreal) throw requireNotNull(unreal)
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!state.running) {
                    return
                }
                val handoffInProgress = isWirelessHandoffInProgress(
                    handoffRequested = state.handoffRequested,
                    tunnelActive = wirelessTunnelChannel != null,
                    sessionActive = activeSession != null,
                )
                if (bluetoothLinkStarted && (state.activeReported || handoffInProgress) && error !is Error) {
                    debugLog(
                        "wireless RFCOMM control ended during or after the tunnel handoff: ${error.message} " +
                            "(active=${state.activeReported} handoffInProgress=$handoffInProgress)",
                    )
                } else {
                    // reportFailure records the WIRELESS_FAILED checkpoint once per generation.
                    debugLog("wireless bring-up failed", error)
                    if (error is Error) throw error
                    // A link that "connected" at once and failed without a byte may never have been
                    // real: say so instead of retrying into the same failure.
                    val unreal = instantBluetoothLink?.takeIf { it.generation == generation }
                        ?.let { unrealBluetoothFailure(it, "failure") }
                    fail(unreal ?: classifyBootstrapFailure(error), generation)
                    closeWirelessStack(generation = generation)
                }
            }
        }

        private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
            if (!state.running) return false
            val identification = wirelessRuntimeIdentification ?: return false
            val endpoint = wirelessAirPlayEndpoint ?: return false
            val mfi = mfiSession?.client ?: return false
            debugLog("wireless type-130 tunnel data stream accepted")
            val channel = try {
                Iap2Session.openTunnel(
                    stream,
                    traceContext = "wireless-tunnel",
                    onTrace = ::debugLog,
                    onArtwork = { whileRunning { onArtworkTransfer(it) } },
                )
            } catch (error: Throwable) {
                debugLog("Could not open the tunneled iAP2 link", error)
                return false
            }
            synchronized(wirelessResourceLock) {
                if (!state.running) {
                    closeBestEffort("stale tunneled iAP2 link") { channel.close() }
                    return false
                }
                wirelessTunnelChannel = channel
            }
            val generation = this.generation
            debugLog(
                "wireless iAP2 runtime tunnel control starting " +
                    "location=${identification.locationInformationEnabled} " +
                    "vehicleStatus=${identification.vehicleStatusEnabled}",
            )
            return try {
                tunnelExecutor.execute {
                    try {
                        val result = Iap2WirelessControlClient(
                            session = channel,
                            mfi = Iap2MfiAuthenticationClient(mfi),
                        ).run(
                            identification = identification,
                            endpoint = endpoint,
                            timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                            locationProvider = locationProvider,
                            vehicleStatusProvider = vehicleStatusProvider,
                            onReady = {
                                onWirelessTunnelReady(generation)
                            },
                            onStartSessionSent = {
                                if (state.running &&
                                    state.claimTunnelStart()) {
                                    sampleWirelessStart(CarPlayStartupTimeline.Stage.TUNNEL_START_SESSION_SENT, generation)
                                }
                            },
                            onIncoming = { whileRunning { onRouteFrame(it, "wireless-tunnel") } },
                            onProgress = { message -> whileRunning { debugLog("iAP tunnel $message") } },
                        )
                        if (!state.running) return@execute
                        when (result.terminal) {
                            Iap2WirelessControlTerminal.TIMED_OUT ->
                                onStatus(CarPlayStatus.ControlEnded)
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                                onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                            // The tunnel only exists once the transport is up, so this cannot happen here.
                            Iap2WirelessControlTerminal.NO_WIRELESS_TRANSPORT -> Unit
                        }
                    } catch (error: Throwable) {
                        if (state.running) {
                            debugLog("tunneled iAP2 control failed", error)
                            onStatus(
                                CarPlayStatus.Failed(
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    } finally {
                        if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                    }
                }
                true
            } catch (error: Throwable) {
                if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                closeBestEffort("tunneled iAP2 link") { channel.close() }
                debugLog("iAP2 tunnel executor rejected the link", error)
                false
            }
        }

        private inline fun whileRunning(action: () -> Unit) {
            if (state.running) action()
        }

        private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
            object : AirPlaySessionListener {
                override val wantsTrace: Boolean get() = state.running && sessionListener.wantsTrace
                override val debugLogsEnabled: Boolean get() = state.running && sessionListener.debugLogsEnabled
                private var reportedFrameSession: AirPlaySession? = null

                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) = whileRunning {
                    if (
                        activeSession === session &&
                        isBluetoothHandoffCommand(type) &&
                        state.requestHandoff()
                    ) {
                        debugLog(
                            "wireless CarPlay Bluetooth handoff requested; " +
                                "waiting for tunnel iAP2 readiness",
                        )
                        armWirelessHandoffWatchdog(generation)
                        maybeCompleteWirelessHandoff()
                    }
                    sessionListener.onCommand(session, type, params)
                }

                override fun onTcpAccepted(event: AirPlayTcpAccepted) = whileRunning {
                    if (watchdog.accepted(event)) {
                        wirelessDiagnostics?.connectionAccepted()
                        if (state.claimFirstTcpSample()) sampleWirelessStart(
                            CarPlayStartupTimeline.Stage.AIRPLAY_TCP_ACCEPTED, generation)
                    }
                }

                override fun onTransportError(message: String) = whileRunning {
                    watchdog.terminate()
                    sessionListener.onTransportError(message)
                }

                override fun onSessionConnecting(session: AirPlaySession) = whileRunning {
                    sessionListener.onSessionConnecting(session)
                }

                override fun onSessionActive(session: AirPlaySession) = whileRunning {
                    if (!watchdog.sessionEstablished()) return@whileRunning
                    wirelessDiagnostics?.let {
                        it.sessionActive()
                        it.close()
                    }
                    wirelessConnectionProof.activate(generation, session)
                    sampleWirelessStart(CarPlayStartupTimeline.Stage.AIRPLAY_SESSION_ACTIVE, generation)
                    // Keep activeReported false until maybeCompleteWirelessHandoff() closes
                    // the Bluetooth bootstrap after the tunneled iAP2 channel is ready.
                    sessionListener.onSessionActive(session)
                }

                override fun onClusterControlReady(session: AirPlaySession) = whileRunning {
                    sessionListener.onClusterControlReady(session)
                }

                override fun onSessionEnded(session: AirPlaySession) = whileRunning {
                    wirelessConnectionProof.end(generation, session)
                    sessionListener.onSessionEnded(session)
                }

                override fun onVideoFrameRendered(session: AirPlaySession) = whileRunning {
                    if (activeSession !== session) return@whileRunning
                    if (!watchdog.sessionEstablished()) return@whileRunning
                    wirelessConnectionProof.rendered(generation, session)
                    // Each decoder start reports its first frame. The one after the tunnel StartSession
                    // is the restarted stream that stays on screen.
                    if (state.tunnelStartDispatched &&
                        state.claimStableFrame()) {
                        sampleWirelessStart(CarPlayStartupTimeline.Stage.STABLE_VIDEO_FRAME, generation)
                    }
                    val firstFrame = synchronized(this) {
                        if (reportedFrameSession === session) false else {
                            reportedFrameSession = session
                            true
                        }
                    }
                    if (firstFrame) {
                        sampleWirelessStart(CarPlayStartupTimeline.Stage.VIDEO_FIRST_FRAME, generation)
                        clusterOutputStateListener?.invoke()
                        uiListener?.onVideoFrameRendered(session)
                    }
                }

                override fun onDebugLog(message: String) = whileRunning {
                    sessionListener.onDebugLog(message)
                }

                // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
                override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) = whileRunning {
                    sessionListener.onRemoteControlMessage(session, streamId, message)
                }

                override fun onVideoPlaybackUiRequested(session: AirPlaySession) = whileRunning {
                    sessionListener.onVideoPlaybackUiRequested(session)
                }

                override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) = whileRunning {
                    sessionListener.onCornerMask(session, png, displayWidth)
                }

                override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) = whileRunning {
                    sessionListener.onDeviceInfo(session, info)
                }

                override fun onHostUiRequested(session: AirPlaySession) = whileRunning {
                    sessionListener.onHostUiRequested(session)
                }
            }

        private fun onWirelessTunnelReady(generation: Int) {
            // The iPhone reached our address over this family, so a later verdict on it is valid.
            synchronized(wirelessResourceLock) {
                if (!state.markTunnelReady()) return
                state.markAddressFamilyReachable()
            }
            bootstrapWatchdog?.mark(Iap2BootstrapPhase.TUNNEL_ESTABLISHED)
            wirelessConnectionProof.authenticated(generation)
            debugLog("wireless iAP2 tunnel ready; handoffRequested=${state.handoffRequested}")
            maybeCompleteWirelessHandoff()
        }

        /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
        private fun maybeCompleteWirelessHandoff() {
            val generation = synchronized(wirelessResourceLock) {
                if (!state.promoteTunnel()) return
                this.generation
            }
            Thread(
                {
                    synchronized(wirelessResourceLock) {
                        // A queued completion must not close a replacement generation's bootstrap.
                        if (!state.running || !state.tunnelReady) return@Thread
                        debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                        closeBluetoothBootstrapTransport()
                        CarBluetoothProfileGuard.yieldNow()
                        onStatus(CarPlayStatus.WirelessActive, generation)
                    }
                },
                "xcertplay-wireless-handoff",
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun keepBluetoothControlAlive(generation: Int): Boolean = synchronized(wirelessResourceLock) {
            state.keepBluetoothControl(activeSession != null, wirelessConnectionProof.hasRenderedFrame(generation))
        }

        private fun armWirelessHandoffWatchdog(generation: Int) {
            mainHandler.postDelayed(
                {
                    if (!state.handoffTimerDue()) {
                        return@postDelayed
                    }
                    debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                    Thread(
                        { handleWirelessHandoffTimeout(generation) },
                        "xcertplay-wireless-handoff-timeout",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                },
                WIRELESS_HANDOFF_TIMEOUT_MILLIS,
            )
        }

        private fun handleWirelessHandoffTimeout(generation: Int) = synchronized(wirelessResourceLock) {
            val decision = state.handoffTimeout(activeSession != null, wirelessConnectionProof.hasRenderedFrame(generation))
            if (decision == 0) return@synchronized
            // Only a rendered frame permits keeping Bluetooth control instead of failing the run.
            if (decision == 1) {
                if (!state.keepBluetoothFallback()) return@synchronized
                bootstrapWatchdog?.terminate()
                // The Bluetooth link is the only iAP2 channel here. Closing it would stop NowPlaying,
                // route guidance and call updates for the rest of the session, so keep it until the
                // tunnel really takes over (see maybeCompleteWirelessHandoff).
                debugLog("wireless handoff fallback after rendered video; tunnel iAP2 unavailable; " +
                    "preserving AirPlay and keeping the Bluetooth bootstrap for iAP2 control")
                onStatus(CarPlayStatus.WirelessActiveFallback, generation)
            } else {
                fail(ConnectionFailureException(
                    ConnectionFailureCause.WIRELESS_TUNNEL_NOT_STARTED,
                    "Wireless CarPlay handoff timed out waiting for the tunneled iAP2 channel",
                ), generation)
                closeWirelessStack(generation = generation)
            }
        }

        /**
         * Point of no return for local recovery: an incomplete 0x4301 write can still
         * reach the iPhone. Claim it under the same owner lock as the timeout callback,
         * and disarm the bootstrap watchdog before the first byte is sent.
         */
        private fun beginStartSessionDispatch(
            generation: Int,
            attempt: WirelessBootstrapRecovery.Attempt,
            validateInfrastructure: () -> Unit,
        ) = synchronized(wirelessResourceLock) {
            attempt.checkCurrent()
            validateInfrastructure()
            attempt.checkCurrent()
            if (!state.canDispatchStartSession(bootstrapAttempt === attempt)) {
                throw java.util.concurrent.CancellationException("Stale bootstrap before StartSession dispatch")
            }
            attempt.stage = WirelessBootstrapRecovery.Stage.CONTROL
            state.markBootstrapDispatched()
            bootstrapWatchdog?.mark(Iap2BootstrapPhase.START_SESSION_SENT)
        }

        private fun expireBootstrapAttempt(
            generation: Int,
            attempt: WirelessBootstrapRecovery.Attempt,
            stall: Iap2BootstrapStall,
        ) = synchronized(wirelessResourceLock) {
            if (!state.canExpireBootstrap(bootstrapAttempt === attempt, wirelessTunnelChannel != null, activeSession != null)) return@synchronized
            if (!attempt.timeout(stall)) return@synchronized
            connectionDiagnostic("wireless attempt=${attempt.number} ${stall.logLine()}")
            // Wake the blocking protocol worker; it maps cancellation/EOF back to the stall and
            // consumes the local retry budget, rather than failing/tearing down the Wi-Fi stack here.
            closeBluetoothBootstrapTransport()
        }

        private fun closeBluetoothBootstrapTransport() {
            bootstrapWatchdog?.terminate()
            bootstrapWatchdog = null
            bootstrapAttempt?.invalidate()
            bootstrapAttempt = null
            instantBluetoothLink = null
            val activeCsm = csm
            csm = null
            if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

            val activeStream = bluetoothStream
            bluetoothStream = null
            if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

            val activeSocket = bluetoothSocket
            bluetoothSocket = null
            if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
        }

        private fun isBluetoothHandoffCommand(type: String): Boolean =
            type.equals("disableBluetooth", ignoreCase = true) ||
                type.equals("disable-bluetooth", ignoreCase = true)

        private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
            if (com.shihab.diplay.network.HotspotHeadStart.isRunning()) {
                val started = System.nanoTime()
                // Bounded by the head start's own budget; a second start meanwhile would only get BUSY.
                val finished = com.shihab.diplay.network.HotspotHeadStart.await(HOTSPOT_HEAD_START_WAIT_MILLIS) {
                    !state.running
                }
                debugLog("generation=$generation boot hotspot start waited finished=$finished elapsedMs=${elapsedMillis(started)}")
                if (!state.running) throw java.util.concurrent.CancellationException("Stale wireless run")
            }
            var readyDeadline = System.nanoTime() + WirelessStartupPolicy.HOTSPOT_READY_MILLIS * 1_000_000
            val hotspotMode = config.wirelessHotspotMode
            if (com.shihab.diplay.network.CarHotspotSettings.shouldEnable(
                    appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
                )
            ) {
                val result = com.shihab.diplay.network.CarHotspotTethering.enable(
                    appContext,
                    isCancelled = { !state.running ||
                        !com.shihab.diplay.network.CarHotspotSettings.enabled(appContext) },
                    timeoutMillis = ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1),
                    log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting hotspot interface") },
                    preferredBand = config.manualHotspotBand,
                )
                // Wi-Fi recovery can consume the start budget; the new interface still needs time to appear.
                if (result == com.shihab.diplay.network.CarHotspotTethering.Result.READY) {
                    readyDeadline = System.nanoTime() + WirelessStartupPolicy.HOTSPOT_READY_MILLIS * 1_000_000
                }
                val manualFallback = result == com.shihab.diplay.network.CarHotspotTethering.Result.UNSUPPORTED &&
                    com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext) == null
                if (result != com.shihab.diplay.network.CarHotspotTethering.Result.READY && !manualFallback) {
                    throw WirelessStartupException(
                        if (result == com.shihab.diplay.network.CarHotspotTethering.Result.TIMED_OUT ||
                            result == com.shihab.diplay.network.CarHotspotTethering.Result.FAILED)
                            WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "${result.diagnostic}. Open the car hotspot settings and connect again.",
                        when (result) {
                            com.shihab.diplay.network.CarHotspotTethering.Result.TIMED_OUT ->
                                ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT
                            com.shihab.diplay.network.CarHotspotTethering.Result.FAILED ->
                                ConnectionFailureCause.CAR_HOTSPOT_START_FAILED
                            else -> null
                        })
                }
            }
            var pastOffReport = false
            if (hotspotMode == WirelessHotspotMode.MANUAL) {
                val state = com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext)
                if (com.shihab.diplay.network.CarHotspotSettings.reportedOff(appContext, state)) {
                    throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "The car hotspot is off. Turn it on in the car settings and connect again.",
                        ConnectionFailureCause.CAR_HOTSPOT_OFF)
                }
                if (state == false) debugLog("car hotspot: Android reports it off; connecting anyway, as the driver chose")
                pastOffReport = state == false
            }
            // The Host may predate the car's latest settings. Use one fresh credential snapshot for
            // this handshake; the manager's ordinary AP read does not read back the password.
            val carCredentials = if (hotspotMode == WirelessHotspotMode.MANUAL &&
                Build.VERSION.SDK_INT == Build.VERSION_CODES.P &&
                com.shihab.diplay.network.CarHotspotSettings.enabled(appContext)) {
                com.shihab.diplay.network.CarHotspotConfigRead.read(appContext,
                    log = ::debugLog).credentials
            } else null
            val manager: WirelessHotspotManager = when (hotspotMode) {
                WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext, ::debugLog,
                    preferredChannel = config.wifiP2pPreferredChannel)
                WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    LocalOnlyHotspotManager(appContext, ::debugLog)
                } else {
                    throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "Android ${Build.VERSION.RELEASE} has no local-only hotspot. Choose Car hotspot or Wi-Fi Direct.")
                }
                WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
                    appContext, config.existingWifiSsid, config.existingWifiPassphrase, ::debugLog,
                    onNetworkChanged = { restartWireless(this@WirelessRun) },
                    preferredInterface = config.existingWifiInterface,
                )
                WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                    context = appContext,
                    ssid = carCredentials?.ssid ?: config.manualHotspotSsid
                        ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot SSID is not configured"),
                    passphrase = carCredentials?.passphrase ?: config.manualHotspotPassphrase.orEmpty(),
                    band = config.manualHotspotBand,
                    channel = config.manualHotspotChannel,
                    security = carCredentials?.security ?: config.manualHotspotSecurity,
                    onDiagnostic = { debugLog("generation=$generation $it") },
                    isCancelled = { !state.running },
                    preferredInterface = config.manualHotspotInterface,
                    // Without the AP the session is gone; restart now instead of after the TCP liveness deadline.
                    onApLost = {
                        if (state.running) {
                            debugLog("generation=$generation car hotspot stopped; restarting wireless")
                            lifecycle.awaitRadioReturn()
                            com.shihab.diplay.network.CarRadioOutage.mark()
                            restartWireless(this@WirelessRun)
                        }
                    },
                )
            }
            synchronized(wirelessResourceLock) {
                if (!state.running) {
                    manager.close()
                    throw java.io.InterruptedIOException("Hotspot startup cancelled")
                }
                hotspot = manager
            }
            val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
                WIFI_P2P_START_TIMEOUT_MILLIS
            } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
                ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            } else {
                HOTSPOT_START_TIMEOUT_MILLIS
            }
            return try {
                manager.start(timeoutMillis)
            } catch (failure: Exception) {
                if (hotspot === manager) hotspot = null
                closeBestEffort(hotspotMode.name) { manager.close() }
                if (!state.running) throw failure
                if (pastOffReport && (failure as? WirelessStartupException)?.reason == WirelessStartupFailure.HOTSPOT_NOT_READY &&
                    com.shihab.diplay.network.CarHotspotSettings.hotspotNeverCameUp(
                        appContext, com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext))) {
                    debugLog("car hotspot: Android still reports it off and no hotspot came up; the off warning is back")
                }
                throw IOException(
                    "Could not establish ${hotspotMode.name} hotspot: " +
                        (failure.message ?: failure.javaClass.simpleName),
                    failure,
                )
            }
        }

        /**
         * True when there is real evidence that the current address family failed for a phone that is
         * actually on the hotspot, so persisting the opposite family is a correction and not a guess.
         *
         * A first-TCP timeout on its own is ambiguous: the iPhone may simply still be away, in which
         * case flipping the preferred family would poison the next, healthy connection. Require both an
         * associated phone (a Bonjour `_airplay._tcp` announcement) and a reachable-address signal
         * before persisting a flip.
         */
        private fun shouldFlipAddressFamily(): Boolean {
            if (!state.running) return false
            val phoneOnHotspot = bonjourSawPhone()
            val familyReachable = state.addressFamilyReachable
            val reason = AddressFamilyFlipGate.withholdReason(phoneOnHotspot, familyReachable)
            if (reason != null) {
                debugLog("wireless address family flip withheld: $reason")
                return false
            }
            return true
        }

        private fun bonjourSawPhone(): Boolean {
            val snapshot = bonjour?.diagnosticSnapshot().orEmpty()
            fun count(key: String): Int =
                Regex("$key=(\\d+)").find(snapshot)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return count("bonjourAdded") > 0 || count("bonjourResolved") > 0
        }

        /**
         * Some head units answer every RFCOMM connect with success at once and then carry no byte:
         * calls and music run on the maker's own Bluetooth module, and Android's adapter fronts
         * nothing a phone can hear. Some of them leave the link open and silent, others drop it after
         * a few seconds, so the link is put to the test both when it has stayed silent for a while and
         * when the attempt fails without a byte received (see the bring-up failure handler).
         */
        private fun watchForUnrealBluetooth(link: InstantBluetoothLink) {
            Thread({
                try {
                    Thread.sleep(BluetoothLinkCheck.SILENCE_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!state.running || !link.attempt.isCurrent || instantBluetoothLink !== link) return@Thread
                val failure = unrealBluetoothFailure(link, "silence") ?: return@Thread
                if (!state.running || !link.attempt.isCurrent || instantBluetoothLink !== link) return@Thread
                synchronized(wirelessResourceLock) {
                    if (!link.attempt.isCurrent || instantBluetoothLink !== link) return@Thread
                    fail(failure, link.generation)
                    closeWirelessStack(generation = link.generation)
                }
            }, "diplay-bluetooth-link-check").apply { isDaemon = true; start() }
        }

        /**
         * The failure to report when [link] was never real, or null when it was or cannot be told.
         * The claim is put to a service no phone offers, which a real stack cannot connect to; only
         * when that "succeeds" at once as well is the link called unreal. A link that has delivered
         * a single byte is not tested at all.
         */
        private fun unrealBluetoothFailure(link: InstantBluetoothLink, trigger: String): WirelessStartupException? {
            val received = link.stream.bytesReceived
            if (!BluetoothLinkCheck.looksUnreal(link.connectMillis, received)) return null
            val started = System.nanoTime()
            val control = runCatching {
                link.device.createRfcommSocketToServiceRecord(UUID.randomUUID())
                    .use { wirelessBluetooth.connectBluetoothSocket(it, link.device.address) { link.attempt.isCurrent } }
            }
            val controlMillis = elapsedMillis(started)
            val unreal = BluetoothLinkCheck.controlProvesUnreal(control.isSuccess, controlMillis)
            connectionDiagnostic(
                "Bluetooth link check trigger=$trigger connectMs=${link.connectMillis} receivedBytes=$received " +
                    "controlConnect=${if (control.isSuccess) "succeeded" else "failed"} " +
                    "controlMs=$controlMillis unreal=$unreal",
            )
            if (!unreal) return null
            return WirelessStartupException(
                WirelessStartupFailure.BLUETOOTH_UNUSABLE,
                "Android Bluetooth on this head unit reports ${BluetoothLinkCheck.UNREAL_MARK}: " +
                    "a connection to a service no phone offers also succeeded in ${controlMillis}ms",
            )
        }

        /**
         * Starts the speculative RFCOMM connect right after target selection so it overlaps the
         * listener / Bonjour / endpoint preparation. Only the socket connect is speculative:
         *
         * - the socket is registered in [bluetoothSocket] while it is created, so cleanup keeps
         *   ownership exactly as for a serial connect;
         * - no iAP2 byte flows before the bootstrap attempt validates the infrastructure;
         * - a pre-connect failure is rethrown by the attempt after the infrastructure check and
         *   keeps the #36 recovery classification — it is never silently retried by a second connect.
         */
        private fun startRfcommPreconnect(generation: Int, target: BluetoothDevice): RfcommPreconnect<BluetoothSocket> {
            return RfcommPreconnect(
                targetAddress = target.address,
                isCurrent = { state.running },
                createSocket = {
                    synchronized(wirelessResourceLock) {
                        if (!state.running) {
                            throw java.util.concurrent.CancellationException("Stale wireless run")
                        }
                        // A failed socket is never reused, including after a protocol-layer failure.
                        target.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                            .also { bluetoothSocket = it }
                    }
                },
                connectSocket = { socket ->
                    // No status from here: the network preparation reports its own stages meanwhile, and
                    // a Bluetooth stage between them would step the connection screen backwards.
                    wirelessBluetooth.logBluetoothConnectionSnapshot(target, "before-preconnect")
                    val started = System.nanoTime()
                    try {
                        wirelessBluetooth.connectBluetoothSocket(socket, target.address) { state.running }
                    } catch (error: Exception) {
                        connectionDiagnostic("Bluetooth preconnect failed " +
                            "elapsedMs=${elapsedMillis(started)} failureClass=${diagnosticFailureClass(error)}")
                        throw error
                    }
                    connectionDiagnostic("Bluetooth preconnect completed elapsedMs=${elapsedMillis(started)}")
                    sampleWirelessStart(CarPlayStartupTimeline.Stage.RFCOMM_CONNECTED, generation)
                    elapsedMillis(started)
                },
                closeSocket = { socket ->
                    synchronized(wirelessResourceLock) { closeOwnedBluetoothSocket(socket, "abandoned preconnect") }
                },
                log = { debugLog("wireless generation=$generation $it") },
            ).also { it.start() }
        }

        /** Caller holds [wirelessResourceLock]. Closes [socket] and clears the slot if it is registered there. */
        private fun closeOwnedBluetoothSocket(socket: BluetoothSocket, reason: String) {
            if (bluetoothSocket === socket) bluetoothSocket = null
            connectionDiagnostic("Bluetooth socket closed reason=$reason")
            closeBestEffort("wireless Bluetooth socket") { socket.close() }
        }

        /**
         * Releases one wireless generation in two phases.
         *
         * The synchronous phase runs under [wirelessResourceLock] and only does work that must stay
         * ordered with bring-up: it invalidates the generation, unbinds the AirPlay listener, closes the
         * iAP2 tunnel and RFCOMM bootstrap, releases the hotspot, and detaches the Bonjour instance.
         *
         * The slow Bonjour teardown (closing JmDNS, releasing the MulticastLock and joining the worker)
         * runs afterwards on its own thread. Closing JmDNS has been observed to block for tens of seconds,
         * so waiting for it while holding the global resource lock starved every other operation.
         */
        fun closeWirelessStack(service: CarPlayVpnService? = vpnService, generation: Int? = null) {
            var bonjourToClose: CarPlayBonjour? = null
            synchronized(wirelessResourceLock) {
                if (generation != null && generation != this.generation || !state.beginClose()) return
                // A throw between beginClose and finishClose would leave the run in CLOSING, and every
                // later close would return at beginClose with the sockets and hotspot still open.
                try {
                    val owner = firstTcpWatchdog?.listener
                    for (action in ControllerLifecycleCore.wirelessClosePlan(wirelessRun === this)) when (action) {
                        1 -> { firstTcpWatchdog?.terminate(); bootstrapWatchdog?.terminate() }
                        2 -> {
                            val diagnostics = wirelessDiagnostics
                            wirelessDiagnostics = null
                            if (diagnostics != null) closeBestEffort("wireless diagnostics") { diagnostics.close() }
                        }
                        3 -> wirelessConnectionProof.clear()
                        4 -> closeBestEffort("iAP tunnel handler") { media.setIapTunnelHandler(null) }
                        5 -> {
                            if (owner != null) closeBestEffort("AirPlay service") { (attachedService ?: service)?.detachWireless(owner) }
                            attachedService = null
                        }
                        6 -> {
                            val tunnel = wirelessTunnelChannel
                            wirelessTunnelChannel = null
                            if (tunnel != null) closeBestEffort("tunneled iAP2 link") { tunnel.close() }
                        }
                        7 -> closeBluetoothBootstrapTransport()
                        8 -> {
                            val activeHotspot = hotspot
                            hotspot = null
                            if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
                        }
                        9 -> { bonjourToClose = bonjour; bonjour = null }
                        10 -> {
                            wirelessRuntimeIdentification = null
                            wirelessAirPlayEndpoint = null
                            wirelessPublishedAddress = null
                            wirelessAlternateAddress = null
                            wirelessAddressRecovery = null
                        }
                    }
                } finally {
                    state.finishClose()
                }
            }
            closeBonjourOffLock(bonjourToClose)
        }

        /**
         * Closes JmDNS, releases the MulticastLock and joins the Bonjour worker on a daemon thread, so a
         * slow teardown can never hold [wirelessResourceLock] or delay the next generation's bring-up.
         */
        private fun closeBonjourOffLock(activeBonjour: CarPlayBonjour?) {
            if (activeBonjour == null) return
            debugLog("Bonjour teardown dispatched off the wireless resource lock")
            Thread(
                { closeBestEffort("Bonjour") { activeBonjour.close() } },
                "diplay-bonjour-teardown",
            ).apply {
                isDaemon = true
                start()
            }
        }

        @Suppress("DEPRECATION")
        private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
            val address = try {
                adapter.address
            } catch (_: SecurityException) {
                null
            }
            val settingsAddress = try {
                Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
            } catch (_: SecurityException) {
                null
            }
            return listOfNotNull(address, settingsAddress)
                .firstOrNull {
                    BLUETOOTH_ADDRESS.matches(it) &&
                        !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
                }
                ?: airPlayConfig.btMac
        }

        private fun flipWirelessAddressFamily() = synchronized(wirelessResourceLock) {
            if (!state.running) return@synchronized
            val published = wirelessPublishedAddress
            val next = published?.let { wirelessAddressRecovery?.nextFamily(it) }
            val from = published?.let {
                if (it is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
            }
            if (next == null || from == null) {
                debugLog(
                    "wireless address family flip skipped published=${from ?: "none"} " +
                        "alternate=${next != null}",
                )
                return@synchronized
            }
            appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(HOTSPOT_FAMILY_PREFERENCE_KEY, next)
                .apply()
            debugLog("wireless address family flip published=$from next=$next")
        }

        private fun hotspotCandidates(info: WirelessHotspotInfo, fallbackToSnapshot: Boolean = true): List<InetAddress> {
            val name = info.interfaceName ?: return info.hostAddresses
            val scanned = try {
                NetworkInterface.getByName(name)?.inetAddresses?.toList().orEmpty()
            } catch (error: Exception) {
                debugLog("wireless interface scan failed iface=$name error=${error.javaClass.simpleName}")
                emptyList()
            }
            return if (scanned.isEmpty() && fallbackToSnapshot) info.hostAddresses else scanned
        }

        /**
         * Names an MFi authentication failure with the same driver-facing cause the bootstrap watchdog
         * uses for its other stalls, so "the phone did not accept authentication" is distinguishable from
         * "the phone went silent". Only the wireless bring-up calls this; wired keeps the generic path.
         */
        private fun classifyBootstrapFailure(error: Throwable): Throwable {
            if (error is ConnectionFailureException) return error
            val mfi = generateSequence(error) { it.cause }
                .filterIsInstance<com.shihab.diplay.mfi.MfiException>()
                .firstOrNull()
            return if (mfi != null) {
                ConnectionFailureException(
                    ConnectionFailureCause.WIRELESS_AUTHENTICATION_FAILED,
                    mfi.message ?: "iAP2 MFi authentication failed",
                    error,
                )
            } else {
                error
            }
        }

        private fun onStatus(status: CarPlayStatus, generation: Int = this.generation) {
            synchronized(wirelessResourceLock) {
                if (!state.running) return
                // A tunnel-control failure while the AirPlay session still renders is shown but does
                // not end the run: the session's own end and transport callbacks must keep arriving,
                // as they did before runs owned their lifecycle.
                if (status is CarPlayStatus.Failed && activeSession == null && !reportFailure()) return
                this@CarPlayController.onStatus(status, generation)
            }
        }

        private fun fail(error: Throwable, generation: Int = this.generation) {
            synchronized(wirelessResourceLock) {
                if (!state.running) return
                this@CarPlayController.fail(error, generation)
            }
        }

        fun reportFailure(): Boolean {
            if (!state.fail()) return false
            // One checkpoint per generation, whichever path failed it: bring-up, handoff timeout or
            // an unreal Bluetooth link.
            sampleWirelessStart(CarPlayStartupTimeline.Stage.WIRELESS_FAILED, generation)
            firstTcpWatchdog?.terminate()
            bootstrapWatchdog?.terminate()
            return true
        }

        fun retire() {
            state.retire()
            firstTcpWatchdog?.terminate()
            bootstrapWatchdog?.terminate()
        }

        fun cancelHotspotWait() { (hotspot as? ManualHotspotManager)?.close() }

        fun acceptsProgress(): Boolean = state.running

        fun hasFailed(): Boolean = state.failedOnce

        fun runtimeChannel(): Iap2Session? = wirelessTunnelChannel.takeIf { state.running }

        fun startSessionDispatched(): Boolean =
            state.bootstrapDispatched && state.running
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        private const val TRACE_LOG_TAG = "DiPlayTrace"
        // Indices in the telephony HID report (AirPlayHid.telephonyDescriptor).
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private val HOTSPOT_START_TIMEOUT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(4)
        private val WIFI_P2P_START_TIMEOUT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(5)
        private val PAIR_TIMEOUT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(6)
        private val VPN_CONNECT_TIMEOUT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(7)
        private const val WIRELESS_PREFS = "diplay_wireless"
        private const val HOTSPOT_FAMILY_PREFERENCE_KEY = "hotspot_address_family"
        // An iPhone that will start wireless CarPlay asks for the transport within about a second
        // (0.6 s and 3 s in measured runs). A minute leaves room for a slow Wi-Fi association while
        // still ending the attempt long before the control-loop budget runs out.
        private val WIRELESS_TRANSPORT_WAIT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(8)
        private val PERMISSION_POLL_INTERVAL_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(1)
        private val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(2)
        private val WIRELESS_HANDOFF_TIMEOUT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(3)
        /** Above the boot hotspot start's 30 s budget. */
        private val HOTSPOT_HEAD_START_WAIT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(9)
        /** The driver may come back to the car any time; the wait ends early on disconnect. */
        private const val RADIO_RETURN_WAIT_MILLIS = com.shihab.diplay.network.CarRadioOutage.BUDGET_MILLIS
        /** Elapsed may step back by a fraction of a second between updates (130690 -> 130552 on v129). */
        private const val PLAYBACK_ELAPSED_JITTER_MILLIS = 2_000L

        /**
         * The pre-connect's connect runs inside [RFCOMM_CONNECT_TIMEOUT_MILLIS] plus its polling
         * granularity; this margin makes the adoption wait outlive the connect instead of timing
         * out and silently opening a second socket while the first is still connecting.
         */
        private val RFCOMM_PRECONNECT_WAIT_MARGIN_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(10)
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        /** An iPhone answers the mode request within about a second; one took six (#335). */
        val REENUMERATION_WAIT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(11)
        internal val IPHONE_NOT_LISTED_AFTER_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(12)
        private const val START_SESSION_PROGRESS = "iap2 tx=0x4301"
        private val EXECUTOR_CLOSE_TIMEOUT_MILLIS: Long get() = ControllerLifecycleCore.timingPolicy(13)
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
