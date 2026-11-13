package com.shihab.diplay.airplay

import android.util.Log
import com.shihab.diplay.hud.NavigationOutputs
import com.shihab.diplay.logging.DiPlayTrace
import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    fun onTcpAccepted(event: AirPlayTcpAccepted) {}
    fun onSessionConnecting(session: AirPlaySession) {}
    fun onSessionActive(session: AirPlaySession) {}
    fun onSessionEnded(session: AirPlaySession) {}
    fun onClusterControlReady(session: AirPlaySession) {}
    fun onVideoFrameRendered(session: AirPlaySession) {}
    /** Validated downstream; immutable copy of iPhone's top-left grayscale coverage PNG. */
    fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    /** Video in car: a playback message on a remote control session (X-Apple-StreamID). */
    fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {}
    /** Video in car: requestUI "videoplayback:", the iPhone asks the car to show its video player. */
    fun onVideoPlaybackUiRequested(session: AirPlaySession) {}
    /** False when this listener drops "TRACE " lines: the session then skips building their hex dumps. */
    val wantsTrace: Boolean get() = true
    val debugLogsEnabled: Boolean get() = false
    fun onDebugLog(message: String) {}
}

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /** CarPlay's main buffered audio (type 103): the SETUP response, or null to decline it. */
    fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /**
     * SETRATE, SETRATEANCHORTIME, GETANCHOR and FLUSHBUFFERED for the buffered stream: the response
     * plist (the anchor), or null for an empty 200.
     */
    fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}
    /**
     * The iPhone's `duckAudio` (true) / `unduckAudio` (false) command for a navigation prompt.
     *
     * [durationMs] and [volume] are the iPhone's own parameters for the command; each is null when
     * the command omits it. They are carried through rather than discarded because the prompt's
     * intended depth and fade length are the iPhone's decision, not this app's.
     */
    fun onDuckAudio(session: AirPlaySession, ducked: Boolean, durationMs: Int? = null, volume: Float? = null) {}
    /** duckAudio (true) and unduckAudio (false): the iPhone marks the start and end of a navigation prompt. */
    fun onAudioDuck(session: AirPlaySession, ducked: Boolean) {}
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class AirPlaySession(
    private val socket: Socket,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
) : Closeable {
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    private val controlCipherLock = Any()
    @Volatile internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    var deviceBtMac = ""
        private set
    private val negotiation = AirPlayNegotiation(config)
    internal val activeStreams: List<Int> get() = negotiation.streams()

    /** Counts the iPhone's cluster stream setups; 0 while no cluster stream is up. */
    val clusterStream: Int get() = negotiation.clusterStream
    private class MainScreenLease(val epoch: Long)
    @Volatile private var mainScreenToken: MainScreenLease? = null
    // Activity 重建后仍可判断当前会话的中控主视频是否已经出帧。
    val hasRenderedMainFrame: Boolean get() = negotiation.hasRenderedMainFrame

    internal val isClosed: Boolean get() = negotiation.isClosed
    @Volatile private var eventServer: ServerSocket? = null
    @Volatile private var eventSocket: Socket? = null
    @Volatile private var eventCipher: ControlCipher? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    // Only the controller's single touch thread calls sendTouch.
    private val touchSlots = AirPlayTouchSlots(AirPlayHid.TOUCH_CONTACTS)
    private val ntp = NtpClock()
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    private val navigationExitTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-navigation-exit").apply { isDaemon = true }
    }
    private var navigationExitTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    private val eventThreads = CopyOnWriteArrayList<Thread>()

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()
    val videoInCar: Boolean get() = config.videoInCar
    private val videoPlaybackAvailability = VideoPlaybackAvailability { allowed ->
        sendCommand(AirPlayCommands.videoAllowed(allowed))
    }

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun logDebug(message: String) = debugLog(message)

    internal fun videoFrameRendered() {
        if (negotiation.frameRendered()) listener.onVideoFrameRendered(this)
    }

    internal fun logTrace(message: String) = trace { message }

    fun start() {
        Thread(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!negotiation.beginClose()) return
        // A sender may hold eventWriteLock while blocked in write; interrupt its socket first.
        safeClose(eventSocket)
        releaseNavigationExit("session-closed")
        navigationExitTimer.shutdownNow()
        mainScreenToken = null
        clearClusterContent()
        safeClose(socket)
        synchronized(controlCipherLock) {
            cipher?.close()
            cipher = null
        }
        try {
            media.onSessionClosed(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay media stream teardown failed", error)
        }
        teardown()
        pairVerify.close()
        pairSetup.close()
        touchSlots.close()
        if (negotiation.notifyEnded()) listener.onSessionEnded(this)
    }

    /** Stable identity of the usable primary screen; replaced on SETUP, absent after teardown/close. */
    internal fun mainScreenSessionToken(): Any? {
        val lease = mainScreenToken ?: return null
        return lease.takeIf { negotiation.isMainScreenCurrent(it.epoch) }
    }

    /**
     * Asks the iPhone to draw CarPlay's cluster UI on the alt screen (showUI with the display's URL,
     * then a keyframe) or to stop drawing it (stopUI). The stream stays up either way; CarKit handles
     * both as car-initiated commands for a screen UUID.
     */
    fun setClusterUiShown(shown: Boolean): Boolean {
        synchronized(clusterCommandLock) {
            if (negotiation.isClosed || clusterStream <= 0) return false
            val content = synchronized(clusterContentLock) { clusterContent } ?: return false
            if (!shown) {
                val sent = sendCommand(AirPlayCommands.stopUi(AirPlayInfoPlist.ALT_UUID))
                return synchronized(clusterContentLock) {
                    val current = clusterContent ?: return false
                    if (negotiation.isClosed || current.stream != content.stream) {
                        if (sent) clusterContent = current.copy(displayed = null, shown = false, token = Any())
                        return false
                    }
                    if (sent) clusterContent = current.copy(shown = false)
                    sent
                }
            }
            return showClusterUrl(content, content.requested ?: return false)
        }
    }

    private data class ClusterContent(val stream: Int, val requested: String?, val displayed: String?,
        val shown: Boolean = true, val token: Any = Any())
    private val clusterContentLock = Any()
    private val clusterCommandLock = Any()
    @Volatile private var clusterContent: ClusterContent? = null
    private var requestedClusterUrl = config.cluster?.initialUrl // guarded by clusterContentLock

    /** Last delivered content for this stream. Pending selections never grant wheel zoom eligibility. */
    fun clusterUrl(): String? = clusterContentRoute()?.second

    /** One immutable snapshot keeps content and generation checks consistent without locking keys. */
    internal fun clusterContentRoute(): Triple<Int, String?, Any>? {
        val content = clusterContent ?: return null
        if (negotiation.isClosed || content.stream != clusterStream) return null
        return Triple(content.stream, content.displayed, content.token)
    }

    /**
     * Switches the dashboard to another of the iPhone's `altScreenURLs` without reconnecting: `showUI`
     * with that URL and a keyframe (on this head unit this switches map, turn card and both at once). With [send]
     * false (the map is paused) the URL is only kept for the next `showUI`. False without a cluster stream.
     */
    fun setClusterUrl(url: String, send: Boolean): Boolean {
        return synchronized(clusterCommandLock) {
            val content = synchronized(clusterContentLock) {
                val current = clusterContent ?: return false
                if (negotiation.isClosed || current.stream != clusterStream || config.cluster == null) return false
                requestedClusterUrl = url
                current.copy(requested = url).also { clusterContent = it }
            }
            if (!send) true else showClusterUrl(content, url)
        }
    }

    /** Seeds a retained controller selection even when this phone has not set up its cluster yet. */
    internal fun restoreClusterUrl(url: String, send: Boolean): Boolean = synchronized(clusterCommandLock) {
        val content = synchronized(clusterContentLock) {
            if (negotiation.isClosed || config.cluster == null) return false
            requestedClusterUrl = url
            clusterContent?.copy(requested = url)?.also { clusterContent = it }
        }
        if (content == null || !send || (content.shown && content.displayed == url)) true
        else showClusterUrl(content, url)
    }

    private fun showClusterUrl(content: ClusterContent, url: String): Boolean {
        if (negotiation.isClosed || clusterStream != content.stream) return false
        val uuid = AirPlayInfoPlist.ALT_UUID
        val uiSent = sendCommand(AirPlayCommands.showUi(uuid, url))
        val sent = uiSent && clusterStream == content.stream && !negotiation.isClosed &&
            sendCommand(AirPlayCommands.keyFrame(uuid))
        return synchronized(clusterContentLock) {
            val current = clusterContent ?: return false
            if (negotiation.isClosed || current.stream != content.stream || clusterStream != content.stream) {
                // A command already in flight can outlast re-SETUP. Its displayed result is unknown.
                if (uiSent) clusterContent = current.copy(displayed = null, token = Any())
                return false
            }
            if (uiSent) clusterContent = current.copy(displayed = if (sent) url else null,
                shown = true, token = Any())
            sent
        }
    }

    private fun recordAcceptedStream(type: Int) = synchronized(clusterContentLock) {
        // These Java identities are platform routing leases; Rust owns their live generations.
        when (negotiation.accepted(type)) {
            1 -> mainScreenToken = MainScreenLease(negotiation.mainScreenEpoch)
            2 -> clusterContent = ClusterContent(negotiation.clusterStream, requestedClusterUrl, config.cluster?.initialUrl)
        }
    }

    private fun clearClusterContent() = synchronized(clusterContentLock) {
        negotiation.clearCluster()
        clusterContent = null
    }

    // Re-SETUP starts at initialURL. Reapply the saved live selection only after SETUP was answered,
    // or the event channel becomes ready, without claiming it was displayed before delivery.
    private fun reapplyClusterContent() {
        synchronized(clusterCommandLock) {
            val content = clusterContent ?: return
            val requested = content.requested ?: return
            if (content.shown && requested != content.displayed) showClusterUrl(content, requested)
        }
    }

    /**
     * Zooms CarPlay's dashboard map one step, as a car's own zoom controls do: the car command
     * changeMapZoomLevel for the cluster screen's UUID. Seen with Apple Maps on this head unit: zoomDirection 0
     * zooms in, 1 zooms out. Sent only while the cluster stream is up.
     */
    fun changeMapZoomLevel(zoomIn: Boolean): Boolean = clusterStream > 0 && sendCommand(AirPlayCommands.zoom(zoomIn))

    /** Moves CarPlay on the main screen to one of its declared view areas, without reconnecting. */
    fun showViewArea(index: Int): Boolean =
        sendCommand(AirPlayInfoPlist.viewAreaCommand(index, config.main.viewAreas?.size ?: 1))

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    /**
     * A message on a video in car remote control session, framed like the iPhone's own: POST /command
     * with X-Apple-StreamID and {params: {data: bplist(message)}}.
     */
    fun sendRemoteControlMessage(streamId: Long, message: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(
            AirPlayCommands.remoteControl(BplistCodec.encode(message)),
            streamId,
        )
    }

    /**
     * Video in car: retains whether video may play now and sends the latest value once SETUP has
     * enabled video and the event channel is ready. An iPhone without the feature gets no command.
     */
    internal fun setVideoPlaybackAllowed(allowed: Boolean): VideoPlaybackDelivery =
        if (negotiation.isClosed) VideoPlaybackDelivery.QUEUED else videoPlaybackAvailability.setDesired(allowed)

    private fun sendCommandLocked(command: Map<String, Any?>, streamId: Long? = null): Boolean {
        if (negotiation.isClosed) return false
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        negotiation.advanceEventSequence()
        val body = BplistCodec.encode(command)
        val head = AirPlayCommands.eventHeader(negotiation.eventSequence, body.size, streamId)
        trace { "airplay event tx headers=$head bodyHex=${body.toHex()}" }
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            close()
            false
        }
    }

    /** [contacts] carry Android pointer ids; an empty list releases every finger. */
    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (negotiation.isClosed) return false
        val width = config.main.widthPixels
        val height = config.main.heightPixels
        val report = touchSlots.report(contacts, width, height) ?: return false
        val sendStartNs = System.nanoTime()
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent) com.shihab.diplay.media.TouchLatencyProbe.onTouchSent(sendStartNs, System.nanoTime() - sendStartNs)
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = contacts.firstOrNull()?.let { it.copy(x = it.x * width, y = it.y * height) }
            Log.i(
                TAG,
                "airplay touch report sent contacts=${contacts.size} first=" +
                    "(${first?.x},${first?.y},down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int) {
        val startedNs = System.nanoTime()
        // An executor accepting a key is not evidence that either HID report reached the socket.
        debugLog("Playback recovery session=${System.identityHashCode(this)} media HID begin index=$index monotonicNs=$startedNs")
        val down = sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        val up = sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
        debugLog("Playback recovery session=${System.identityHashCode(this)} media HID complete index=$index startedNs=$startedNs downWritten=$down upWritten=$up " +
            "writeMs=${(System.nanoTime() - startedNs) / 1_000_000}")
    }

    fun sendTelephony(index: Int) {
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
    }

    fun invokeSiri() {
        sendCommand(AirPlayCommands.siri(2))
        sendCommand(AirPlayCommands.siri(3))
    }

    /**
     * TurnByTurn arbitration requests that the phone yield navigation, then releases our claim.
     * Only the phone's subsequent route metadata can confirm that its route actually ended.
     */
    fun requestNavigationExit(requestId: Long? = null): Boolean = synchronized(eventWriteLock) {
        val action = negotiation.beginNavigationExit(requestId)
        if (action < 0) {
            if (requestId != null) NavigationOutputs.diagnostic("exit claim written=false reason=session-closed", requestId)
            return false
        }
        if (action == 0) {
            if (requestId != null) NavigationOutputs.diagnostic("exit claim coalesced withRequest=${negotiation.navigationRequestId()}", requestId)
            return true
        }
        val sent = sendCommandLocked(AirPlayCommands.navigationExit(true))
        negotiation.navigationExitSent(sent)
        Log.i(TAG, "navigation exit claim written=$sent")
        if (requestId != null) NavigationOutputs.diagnostic("exit claim written=$sent", requestId)
        if (sent) navigationExitTimeout = navigationExitTimer.schedule(
            { releaseNavigationExit("timeout") }, negotiation.navigationTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        sent
    }

    private fun releaseNavigationExit(reason: String): Unit = synchronized(eventWriteLock) {
        val action = negotiation.releaseNavigationExit()
        if (action < 0) return@synchronized
        navigationExitTimeout?.cancel(false)
        val sent = action > 0 && sendCommandLocked(AirPlayCommands.navigationExit(false))
        Log.i(TAG, "navigation exit release reason=$reason written=$sent routeEndUnconfirmed=true")
        negotiation.navigationRequestId()?.let { NavigationOutputs.diagnostic("exit release reason=$reason written=$sent", it) }
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = AirPlayCommands.iap(data)
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (negotiation.isClosed) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        negotiation.queueNightMode(night)
        sendPendingNightModeLocked()
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = negotiation.pendingNightMode ?: return true
        val sent = sendCommandLocked(
            AirPlayCommands.night(night),
        )
        if (sent) negotiation.nightModeDelivered()
        if (sent && listener.debugLogsEnabled) {
            // The reader uses the same lock so even an immediate response can be correlated.
            val sequence = negotiation.trackNightMode(night)
            debugLog("THEME_DIAGNOSTIC tx cseq=$sequence night=$night")
        }
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(AirPlayCommands.hid(uid, report))

    private fun runControl() {
        com.shihab.diplay.network.TcpLiveness.configure(socket) { debugLog(it) }
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var closeReason = "session closed"
        try {
            while (!negotiation.isClosed) {
                val count = input.read(buffer)
                if (count < 0) {
                    closeReason = "peer EOF"
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    encBuf += plaintext
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $closeReason encrypted=${encBuf.size}", error)
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                accumulated += plaintext
                val parsed = RtspMessage.parseMessages(accumulated)
                accumulated = parsed.rest
                for (request in parsed.messages) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    val rxMessage =
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}"
                    if (showInDebugOverlay) debugLog(rxMessage, true) else DiPlayTrace.log(rxMessage)
                    if (showInDebugOverlay) debugLog(
                        AirPlayControlDiagnostics.request(request.method, request.path, request.body.size),
                        false,
                    )
                    trace {
                        "airplay control rx headers=${request.headers} " +
                            "bodyHex=${request.body.toHex()}"
                    }
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        RtspMessage.Response(status = 500)
                    }
                    val txMessage =
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}"
                    if (showInDebugOverlay || (response.status ?: 200) != 200) {
                        debugLog(txMessage, showInDebugOverlay)
                    } else {
                        DiPlayTrace.log(txMessage)
                    }
                    if (showInDebugOverlay) debugLog(
                        "airplay control response status=${response.status ?: 200} contentBytes=${response.body.size}",
                        false,
                    )
                    val wire = RtspMessage.buildResponse(request, response)
                    trace { "airplay control tx wireHex=${wire.toHex()}" }
                    output.write(cipher?.encrypt(wire) ?: wire)
                    val keys = if (cipher == null) pairVerify.controlKeys else null
                    if (keys != null) {
                        synchronized(controlCipherLock) {
                            if (!negotiation.isClosed && cipher == null) cipher = ControlCipher(keys.readKey, keys.writeKey)
                        }
                        debugLog("airplay control encryption enabled")
                    }
                }
                output.flush()
                notifySetupResponseSent()
            }
        } catch (error: Exception) {
            closeReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!negotiation.isClosed) Log.e(TAG, "airplay $closeReason", error)
        } finally {
            debugLog("airplay control closing reason=$closeReason activeStreams=$activeStreams")
            close()
        }
    }

    private fun handle(request: RtspMessage.Request): RtspMessage.Response {
        val action = negotiation.route(request.method, request.path, request.body.size)
        when (action) {
            AirPlayNegotiation.RequestAction.SETUP -> return handleSetup(request)
            AirPlayNegotiation.RequestAction.PARAMETER -> {
                // iOS can replace the mask when the display changes; never decode PNG on RTSP.
                val value = runCatching { BplistCodec.decode(request.body) }.getOrNull()
                findIphoneCornerMask(value)?.let(::notifyCornerMask)
                return RtspMessage.Response(status = 200)
            }
            AirPlayNegotiation.RequestAction.RECORD -> {
                debugLog("Playback recovery session=${System.identityHashCode(this)} RECORD monotonicNs=${System.nanoTime()}")
                listener.onSessionActive(this)
                return RtspMessage.Response(status = 200)
            }
            AirPlayNegotiation.RequestAction.TEARDOWN -> return handleTeardown(request)
            else -> Unit
        }

        if (action == AirPlayNegotiation.RequestAction.BUFFERED || action == AirPlayNegotiation.RequestAction.GET_ANCHOR) {
            val body = if (request.body.isEmpty() && action == AirPlayNegotiation.RequestAction.GET_ANCHOR) emptyMap() else {
                runCatching { asMap(BplistCodec.decode(request.body)) }.getOrNull()
                    ?: return RtspMessage.Response(status = 400)
            }
            val response = media.onBufferedAudioControl(this, request.method, body)
            if (action != AirPlayNegotiation.RequestAction.GET_ANCHOR) debugLog("airplay ${request.method} $body -> ${response ?: "ok"}")
            return if (response == null) RtspMessage.Response(status = 200) else RtspMessage.Response(
                headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                body = BplistCodec.encode(response),
            )
        }

        return when (action) {
            AirPlayNegotiation.RequestAction.PAIR_SETUP -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            AirPlayNegotiation.RequestAction.PAIR_VERIFY -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            AirPlayNegotiation.RequestAction.AUTH_SETUP -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            AirPlayNegotiation.RequestAction.INFO -> {
                val info = AirPlayInfoPlist.build(config)
                if (request.body.isNotEmpty()) {
                    val requestInfo = try {
                        BplistCodec.decode(request.body).toString()
                    } catch (_: Exception) {
                        "<unparseable ${request.body.size} bytes>"
                    }
                    debugLog("airplay /info request=$requestInfo")
                }
                Log.i(
                    TAG,
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                )
                debugLog(
                    "airplay /info videoInCar=${config.videoInCar} " +
                        "videoPlaybackAllowed=${if (config.videoInCar) VideoInCar.allowed else "not-offered"}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            AirPlayNegotiation.RequestAction.COMMAND -> handleCommand(request)
            AirPlayNegotiation.RequestAction.FEEDBACK -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
    }

    private fun notifySetupResponseSent() {
        try {
            media.onSetupResponseSent(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay SETUP response callback failed", error)
        }
        reapplyClusterContent()
        listener.onClusterControlReady(this)
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Log.i(TAG, message)
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private inline fun trace(message: () -> String) {
        if (!listener.wantsTrace) return
        try {
            listener.onDebugLog("TRACE ${message()}")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    private fun handleSetup(request: RtspMessage.Request): RtspMessage.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body)) ?: return RtspMessage.Response(status = 400)
        } catch (error: Exception) {
            Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            return RtspMessage.Response(status = 400)
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            val responseStreams = handleStreams(streams)
            debugLog("airplay SETUP response streams=$responseStreams")
            val body = BplistCodec.encode(AirPlayCommands.streams(responseStreams))
            trace { "airplay SETUP response bplistHex=${body.toHex()}" }
            return RtspMessage.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        debugLog(AirPlayPeerDiagnostics.summary(model, string(dict["osVersion"]), string(dict["sourceVersion"])))
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val timingPort = openTiming(peerTimingPort)
        val eventPort = openEvent()
        val keepAlivePort = if (AirPlayCommands.needsKeepAlive(dict["keepAliveLowPower"])) openKeepAlive() else null
        val features = setupEnabledFeatures(config, dict["features"] as? List<*>)
        val videoPlaybackEnabled = VideoInCar.FEATURE in features
        val videoDelivery = videoPlaybackAvailability.setFeatureEnabled(videoPlaybackEnabled)
        debugLog("airplay video playback negotiated=$videoPlaybackEnabled availability=$videoDelivery")
        val response = AirPlayCommands.setup(timingPort, eventPort, keepAlivePort, features)
        if (negotiation.reportConnecting()) listener.onSessionConnecting(this)
        return RtspMessage.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            debugLog("airplay SETUP stream type=$type payload=$stream")
            when (val action = negotiation.streamAction(type)) {
                AirPlayNegotiation.StreamAction.SCREEN -> {
                    val port = media.onScreen(this, type, stream)
                    debugLog("airplay screen stream type=$type dataPort=${port ?: "rejected"}")
                    if (port != null) {
                        if (type == STREAM_TYPE_MAIN_SCREEN && config.main.useIphoneCornerMask) {
                            findIphoneCornerMask(stream)?.let(::notifyCornerMask)
                        }
                        recordAcceptedStream(type)
                        result.add(AirPlayCommands.screen(type, port))
                    }
                }
                AirPlayNegotiation.StreamAction.AUDIO -> {
                    val streamResponse = media.onAudio(this, type, stream)
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        recordAcceptedStream(type)
                        result.add(streamResponse)
                    }
                }
                AirPlayNegotiation.StreamAction.BUFFERED, AirPlayNegotiation.StreamAction.BUFFERED_DISABLED -> {
                    val streamResponse = if (action == AirPlayNegotiation.StreamAction.BUFFERED)
                        media.onBufferedAudio(this, stream) else null
                    debugLog("airplay buffered audio stream accepted=${streamResponse != null} " +
                        "dataPort=${streamResponse?.get("dataPort") ?: "none"}")
                    if (streamResponse != null) {
                        recordAcceptedStream(type)
                        result.add(streamResponse)
                    }
                }
                AirPlayNegotiation.StreamAction.DATA -> {
                    val streamResponse = media.onDataStream(this, stream)
                    debugLog(
                        "airplay data stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        recordAcceptedStream(type)
                        result.add(streamResponse)
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
        }
        return result
    }

    private fun handleCommand(request: RtspMessage.Request): RtspMessage.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        val commandPlan = AirPlayCommands.incoming(type, params)
        if (commandPlan.noisy) {
            if (DiPlayTrace.enabled) {
                DiPlayTrace.log("airplay command type=" + type + " keys=" + params.keys.sorted())
            }
        } else {
            debugLog("airplay command type=" + type + " keys=" + params.keys.sorted())
        }
        val streamId = request.headers["x-apple-streamid"]?.toLongOrNull()
        val data = params["data"] as? ByteArray
        if (streamId != null && data != null) {
            runCatching { asMap(BplistCodec.decode(data)) }.getOrNull()
                ?.let { listener.onRemoteControlMessage(this, streamId, it) }
            return RtspMessage.Response(status = 200)
        }
        commandPlan.navigationEntity?.let { entity ->
            synchronized(eventWriteLock) {
                val action = negotiation.navigationEntity(entity)
                if (action and 1 != 0) {
                    Log.i(TAG, "navigation mode entity=" + entity)
                    negotiation.navigationRequestId()?.let { NavigationOutputs.diagnostic("exit modesChanged entity=$entity", it) }
                }
                if (action and 2 != 0) releaseNavigationExit("accessory-granted")
            }
        }
        commandPlan.duck?.let { media.onAudioDuck(this, it) }
        when (commandPlan.ui) {
            2 -> listener.onVideoPlaybackUiRequested(this)
            1 -> listener.onHostUiRequested(this)
        }
        listener.onCommand(this, type, params)
        return RtspMessage.Response(status = 200)
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        val decodedBody = try {
            BplistCodec.decode(request.body)
        } catch (_: Exception) {
            null
        }
        val types = teardownStreamTypes(decodedBody)

        debugLog(
            "airplay TEARDOWN types=${types ?: "all"} activeBefore=$activeStreams " +
                "body=${request.body.size} bytes payload=$decodedBody",
        )
        trace { "airplay TEARDOWN raw${request.body.size}Hex=${request.body.toHex()}" }

        // Restore wheel input before releasing media resources, which may take time to close.
        synchronized(clusterContentLock) {
            val actions = negotiation.prepareTeardown(types)
            if (actions and 1 != 0) mainScreenToken = null
            if (actions and 2 != 0) clusterContent = null
        }
        if (types == null) {
            // A failed callback leaves the full ordered set intact, as before.
            activeStreams.forEach { media.onTeardown(this, it) }
        } else {
            // A partial teardown commits each removal before invoking that resource's callback.
            types.forEach { type -> if (negotiation.removeStream(type)) media.onTeardown(this, type) }
        }
        if (negotiation.finishTeardown(types == null)) clearClusterContent()
        return RtspMessage.Response(status = 200)
    }

    private fun notifyCornerMask(png: ByteArray) {
        if (png.size !in 45..IPhoneCornerMaskWireLimit.MAX_PNG_BYTES) return
        listener.onCornerMask(this, png.copyOf(), config.main.widthPixels)
    }

    private fun openTiming(peerPort: Int): Int {
        val actions = negotiation.timingAction(peerPort, peerAddress != null)
        val port = if (actions and 1 != 0) ntp.listen().also(negotiation::timingOpened) else negotiation.timingPort
        if (actions and 2 != 0) {
            ntp.start(requireNotNull(peerAddress), peerPort)
            negotiation.timingPeerStarted()
        }
        return port
    }

    private fun openKeepAlive(): Int {
        keepAliveSocket?.let { return it.localPort }
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        keepAliveSocket = socket
        keepAliveThread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!negotiation.isClosed) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (negotiation.isClosed) return
            }
        }
    }

    private fun openEvent(): Int {
        eventServer?.let { return it.localPort }
        val server = ServerSocket(0, 50, InetAddress.getByName("::"))
        eventServer = server
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    private fun teardown() {
        safeClose(eventSocket)
        // A failing sender may close while holding eventWriteLock; do not wait for a gear callback's monitor.
        if (!negotiation.isClosed) {
            videoPlaybackAvailability.setEventReady(false)
            videoPlaybackAvailability.setFeatureEnabled(false)
        }
        ntp.close()
        negotiation.timingClosed()
        safeClose(keepAliveSocket)
        keepAliveSocket = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        safeClose(eventServer)
        eventServer = null
        synchronized(eventWriteLock) {
            safeClose(eventSocket)
            eventSocket = null
            eventCipher?.close()
            eventCipher = null
        }
        eventThreads.forEach { it.interrupt() }
        eventThreads.clear()
    }

    private fun acceptEvent(server: ServerSocket) {
        try {
            val expected = peerAddress ?: throw IOException("control peer unknown")
            val socket = server.acceptFrom(expected) { debugLog("airplay event rejected sender outside the control session") }
            socket.setSoLinger(true, 0)
            // Avoid Nagle coalescing on this interactive event connection: small HID/event writes
            // need not wait for earlier unacknowledged data.
            socket.tcpNoDelay = true
            debugLog("airplay event connection accepted from ${socket.remoteSocketAddress} noDelay=${socket.tcpNoDelay}")
            synchronized(eventWriteLock) {
                if (negotiation.isClosed || eventServer !== server) { safeClose(socket); return }
                eventSocket = socket
            }
            val shared = pairVerify.shared
            if (shared == null) {
                Log.e(TAG, "airplay event rejected: pair-verify shared secret unavailable")
                safeClose(socket)
                close()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            val cipher = synchronized(eventWriteLock) {
                if (negotiation.isClosed || eventServer !== server || eventSocket !== socket) { safeClose(socket); return }
                eventCipher?.close()
                val cipher = ControlCipher(readKey, writeKey)
                eventCipher = cipher
                sendPendingNightModeLocked()
                cipher
            }
            val videoDelivery = videoPlaybackAvailability.setEventReady(true)
            debugLog("airplay video event ready availability=$videoDelivery")
            reapplyClusterContent()
            listener.onClusterControlReady(this)
            runEventRead(socket, cipher)
        } catch (error: Exception) {
            if (!negotiation.isClosed) {
                Log.e(TAG, "airplay event accept failed", error)
                close()
            }
        }
    }

    private fun runEventRead(socket: Socket, cipher: ControlCipher) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!negotiation.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                encrypted += buffer.copyOf(count)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    Log.e(TAG, "airplay event decrypt failed encrypted=${encrypted.size}", error)
                    break
                }
                encrypted = decrypted.rest
                plaintext += decrypted.data
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                for (message in parsed.messages) {
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) {
                        if (!listener.debugLogsEnabled) continue
                        val seq = message.headers["cseq"]?.toIntOrNull() ?: continue
                        val night = synchronized(eventWriteLock) { negotiation.nightModeResponse(seq) } ?: continue
                        val status = if (message.body.size in 1..4096) runCatching {
                            asMap(BplistCodec.decode(message.body))?.get("status") as? Number
                        }.getOrNull() else null
                        debugLog("THEME_DIAGNOSTIC rx cseq=$seq night=$night rtspStatus=${message.path.toIntOrNull()} " +
                            "bytes=${message.body.size} plistStatus=$status")
                        continue
                    }
                    debugLog(
                        "airplay event rx ${message.method} ${message.path} cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}",
                    )
                    val response = RtspMessage.buildResponse(message,
                        if (message.path == "/command") handleCommand(message) else RtspMessage.Response(status = 200))
                    trace {
                        "airplay event rx headers=${message.headers} " +
                            "bodyHex=${message.body.toHex()}"
                    }
                    trace { "airplay event tx wireHex=${response.toHex()}" }
                    synchronized(eventWriteLock) {
                        output.write(cipher.encrypt(response))
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            if (!negotiation.isClosed) Log.e(TAG, "airplay event read failed", error)
        } finally {
            safeClose(socket)
            debugLog("airplay event connection closed")
            if (!negotiation.isClosed) videoPlaybackAvailability.setEventReady(false)
            synchronized(eventWriteLock) {
                if (eventSocket === socket) eventSocket = null
                if (eventCipher === cipher) eventCipher = null
                cipher.close()
            }
            if (!negotiation.isClosed) close()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = Thread(body, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private companion object {
        const val TAG = "diplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val STREAM_TYPE_MAIN_BUFFERED_AUDIO = 103

        const val READ_CHUNK_BYTES = 16 * 1024
        const val ZOOM_DIRECTION_IN = 0
        const val ZOOM_DIRECTION_OUT = 1
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** The features SETUP enables; video in car only when configured and the iPhone [proposed] it. */
/** The session feature the iPhone proposes for main buffered audio. */
internal const val MAIN_BUFFERED_FEATURE = "mainBuffered"

internal object IPhoneCornerMaskWireLimit {
    const val MAX_PNG_BYTES = 512 * 1024
}

/** The mask may arrive inside the screen stream SETUP entry or in a runtime parameter plist. */
internal fun findIphoneCornerMask(value: Any?, depth: Int = 0): ByteArray? = NativeAirPlayProtocol.cornerMask(value, depth)

internal fun setupEnabledFeatures(config: AirPlayConfig, proposed: List<*>?): List<String> =
    AirPlayNegotiation.enabledFeatures(config, proposed)

/**
 * The stream types a TEARDOWN body closes, or null when it names none (the whole session). Several
 * type-130 streams can be open, the iAP tunnel (streamID 1) and video in car's data streams; tearing
 * down one of the latter must not close the iAP tunnel.
 */
internal fun teardownStreamTypes(body: Any?): List<Int>? = AirPlayNegotiation.teardownTypes(body)

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
