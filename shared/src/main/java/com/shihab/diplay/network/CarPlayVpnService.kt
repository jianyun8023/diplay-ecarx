package com.shihab.diplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shihab.diplay.airplay.AirPlayListenerIdentity
import com.shihab.diplay.airplay.AirPlayTcpAccepted
import com.shihab.diplay.airplay.isInternalAirPlayPeer
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayDeviceInfo
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.orchestration.VpnAttachmentCore
import com.shihab.diplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data object Superseded : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    /** Immutable platform lease. The predicate only queries its native owner, never resource locks. */
    class WiredOwner internal constructor(val generation: Int, internal val current: () -> Boolean)

    /** Lifecycle-ending cleanup still reaches its original session; progress requires a live lease. */
    private fun ownedListener(delegate: AirPlaySessionListener, current: () -> Boolean) =
        object : AirPlaySessionListener by delegate {
            override fun onTcpAccepted(event: AirPlayTcpAccepted) { if (current()) delegate.onTcpAccepted(event) }
            override fun onSessionConnecting(session: AirPlaySession) { if (current()) delegate.onSessionConnecting(session) }
            override fun onSessionActive(session: AirPlaySession) { if (current()) delegate.onSessionActive(session) }
            override fun onClusterControlReady(session: AirPlaySession) { if (current()) delegate.onClusterControlReady(session) }
            override fun onVideoFrameRendered(session: AirPlaySession) { if (current()) delegate.onVideoFrameRendered(session) }
            override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) { if (current()) delegate.onCornerMask(session, png, displayWidth) }
            override fun onTransportError(message: String) { if (current()) delegate.onTransportError(message) }
            override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) { if (current()) delegate.onDeviceInfo(session, info) }
            override fun onHostUiRequested(session: AirPlaySession) { if (current()) delegate.onHostUiRequested(session) }
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) { if (current()) delegate.onCommand(session, type, params) }
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) { if (current()) delegate.onRemoteControlMessage(session, streamId, message) }
            override fun onVideoPlaybackUiRequested(session: AirPlaySession) { if (current()) delegate.onVideoPlaybackUiRequested(session) }
        }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
        val ownerCurrent: () -> Boolean = { true },
    )

    private val binder = LocalBinder()
    // Lazy allocation stays inside attach's platform-worker failure boundary.
    private val transportState = lazy { VpnAttachmentCore() }
    private val transport by transportState
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: java.io.Closeable? = null
    private var tun: ParcelFileDescriptor? = null
    private var wiredOwner: WiredOwner? = null

    /** The exception chain behind the latest [AttachResult.Failed], for the diagnostic log. */
    @Volatile var lastAttachFailure: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        owner: WiredOwner? = null,
    ): AttachResult {
        val action = transport.prepareAttach(owner?.current?.invoke() ?: true)
        if (action == 0) return AttachResult.Superseded
        if (action == 2) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        val generation = transport.beginAttach()
        wiredOwner = owner
        val callbacks = ownedListener(listener, owner?.current ?: { true })
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                // An empty app list routes every UID through this VPN. Scope it before establish;
                // rejection must reach the existing attachment cleanup, never an unscoped retry.
                .addAllowedApplication(packageName)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val nativePath = getSharedPreferences(NcmUsbBridge.NATIVE_DATA_PATH_PREFS, MODE_PRIVATE)
                .getBoolean(NcmUsbBridge.NATIVE_DATA_PATH_KEY, true) &&
                ncm.startNative(tunFd, hostMac, { line -> runCatching { listener.onDebugLog(line) } }) { error ->
                    onTransportError(generation, callbacks, error)
                }
            runCatching { listener.onDebugLog("wired data path=${if (nativePath) "native" else "kotlin"}") }
            bridge = if (nativePath) {
                java.io.Closeable {
                    ncm.close()
                    tunFd.close()
                }
            } else {
                Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                    onTransportError(generation, callbacks, error)
                }.also { it.start() }
            }

            startAirPlayServer(
                generation,
                AirPlayAttachment(scopedToTunnel(address, listener), config, identity, pairings, mfi, callbacks, media,
                    ownerCurrent = owner?.current ?: { true }),
            )
            if (transport.current(generation, owner?.current?.invoke() ?: true)) AttachResult.Started
            else {
                if (transport.matches(generation)) releaseLocked()
                AttachResult.Superseded
            }
        } catch (error: Exception) {
            if (transport.matches(generation)) releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
        ownerCurrent: () -> Boolean = { true },
    ): AttachResult {
        val action = transport.prepareAttach(ownerCurrent())
        if (action == 0) return AttachResult.Superseded
        if (action == 2) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        val generation = transport.beginAttach()
        val callbacks = ownedListener(listener, ownerCurrent)
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, callbacks, media,
                    additionalBindAddresses, listenerIdentity, ownerCurrent),
            )
            if (transport.current(generation, ownerCurrent())) AttachResult.Started
            else {
                if (transport.matches(generation)) releaseLocked()
                AttachResult.Superseded
            }
        } catch (error: Exception) {
            if (transport.matches(generation)) releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        if (!transportState.isInitialized()) return
        releaseLocked()
    }

    /** An old run/controller must never detach a replacement's NCM/VPN transport. */
    @Synchronized
    fun detachWired(owner: WiredOwner) {
        if (wiredOwner === owner) releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = transportState.isInitialized() && transport.active && attachment != null

    /** A retained listener may only be reused by the wireless run that attached it. */
    @Synchronized
    fun isWirelessAttached(owner: AirPlayListenerIdentity): Boolean =
        transportState.isInitialized() && transport.active && attachment?.listenerIdentity === owner && serverSocket?.isClosed == false

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    /**
     * Linux rejects bind() to a link-local IPv6 address without a scope id (EINVAL). Android 8+
     * IoBridge.bind looks the scope up itself, but Android 7.x binds natively in
     * PlainSocketImpl.socketBind and never derives one, so the unscoped wired listener failed with
     * "Invalid argument". Scope it to the tunnel that owns the address on every version; when no
     * interface owns it, keep the unscoped address and the platform's own behavior.
     */
    private fun scopedToTunnel(address: Inet6Address, listener: AirPlaySessionListener): InetAddress {
        if (address.scopeId != 0) return address
        var interfaces = emptyList<TunnelCandidate>()
        repeat(TUN_LOOKUP_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(TUN_LOOKUP_DELAY_MILLIS)
            interfaces = tunnelCandidates()
            selectTunnelInterface(address, interfaces)?.let { owner ->
                runCatching { listener.onDebugLog("wired AirPlay listener scope iface=${owner.name} index=${owner.index}") }
                return Inet6Address.getByAddress(null, address.address, owner.index)
            }
        }
        val tunnels = interfaces.filter { it.name.startsWith("tun") }.joinToString(",") { it.name }.ifEmpty { "none" }
        runCatching { listener.onDebugLog("wired AirPlay listener scope iface=not_found tun=$tunnels; binding unscoped") }
        return address
    }

    private fun tunnelCandidates(): List<TunnelCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().map { networkInterface ->
            TunnelCandidate(networkInterface.name, networkInterface.index, Collections.list(networkInterface.inetAddresses))
        }
    }.getOrDefault(emptyList())

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (transport.active) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!transport.current(generation, attachment?.ownerCurrent?.invoke() ?: false) ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    try { AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionConnecting(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionConnecting(session)
                            }

                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession) } catch (error: Throwable) {
                        socket.close()
                        throw error
                    }
                }
                session.start()
            }
        } catch (error: Exception) {
            if (transport.active) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (!transport.matches(generation)) return@Thread
                    releaseLocked()
                    transport.generation
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (transport.matches(releasedGeneration) && !transport.active) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        transport.release()
        wiredOwner = null
        attachment = null
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "diplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "DiPlay CarPlay"
        private const val TUN_MTU = 1500
        private const val TUN_LOOKUP_ATTEMPTS = 10
        private const val TUN_LOOKUP_DELAY_MILLIS = 50L

        internal data class TunnelCandidate(val name: String, val index: Int, val addresses: List<InetAddress>)

        /**
         * Picks the interface that owns [address]. A tun interface wins when another interface
         * happens to carry the same link-local address.
         */
        internal fun selectTunnelInterface(address: Inet6Address, interfaces: List<TunnelCandidate>): TunnelCandidate? {
            val owners = interfaces.filter { candidate ->
                candidate.index > 0 && candidate.addresses.any { owned ->
                    owned is Inet6Address && owned.address.contentEquals(address.address)
                }
            }
            return owners.firstOrNull { it.name.startsWith("tun") } ?: owners.firstOrNull()
        }

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        /**
         * Android 7.1 reported only "Invalid argument" when establish failed (#557); name the
         * exception chain and the frame that threw, so the rejected VPN setting can be identified.
         */
        internal fun attachFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { failure ->
                "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
            }
            val origin = error.stackTrace.firstOrNull()
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
            return chain + origin
        }
    }
}
