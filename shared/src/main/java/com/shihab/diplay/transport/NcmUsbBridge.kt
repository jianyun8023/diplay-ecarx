package com.shihab.diplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativeStreamCallbacks
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    init { NativeMedia.requireAvailable() }
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private val transmit = NcmTransmitCore()

    /** [System.nanoTime] when the iPhone first took a frame on bulk OUT, or null while it still NAKs the pipe. */
    val linkOpenedAtNanos: Long?
        get() = synchronized(stateLock) {
            nativeEngine.takeIf { it != 0L }?.let { NativeMedia.ncmLinkOpened(it).takeIf { at -> at != 0L } }
                ?: transmit.openedAt
        }
    // The native data path ([startNative]) while it runs; it then owns every bulk transfer.
    // Every native handle use, publication and retirement is serialized by stateLock.
    private var nativeEngine = 0L
    private var nativeStarting = false
    // A full-speed link uses 64-byte packets; the short-packet pad follows the real packet size.
    private val usbPacketSize = inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val readRequests = UsbRequestQueue(connection, "ncm-read-reaper")
    private val traffic = NcmTrafficCounter()
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val receiveCore = NcmReceiveCore(usbPacketSize)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            transmit.nextSequence()
        }
        val block = Ntb16Codec.build(frame, sequence)
        // bulkTransfer() pins `block` in a JNI critical section until it returns (see the read
        // path above). While the phone still NAKs this pipe, every frame waited out the full
        // timeout, and each USBMUX write of the iAP2 handshake finished only when one expired:
        // ~2 s per step on Android 8.1–14 (#634, #335, #301). Until the phone takes its first
        // frame, a frame that cannot go out at once is dropped, as it was after the full timeout.
        val transferred = connection.bulkTransfer(
            outEndpoint,
            block,
            block.size,
            transmit.timeout(timeoutMillis),
        )
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        val result = transmit.begin(transferred)
        if (result[0] == 0L) {
            if (result[1] != 0L) Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            return@synchronized
        }
        if (result[0] == 2L) {
            transmit.opened(System.nanoTime())
            runCatching { onDiagnostic("NCM bulk-out ready after ${result[2]} dropped frames") }
        }
        val completion = transmit.finish(transferred, block.size)
        if (completion < 0) {
            throw IphoneUsbException.DeviceUnavailable("NCM write transferred $transferred of ${block.size} bytes")
        }
        traffic.sent()
        if (completion != 0) Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
    }

    /** Frames to and from the iPhone so far, for a report line; see [NcmTrafficCounter]. */
    fun trafficSummary(airPlayPort: Int?): String = synchronized(stateLock) {
        nativeEngine.takeIf { it != 0L }?.let { NativeMedia.ncmTraffic(it, airPlayPort ?: -1) } ?: traffic.summary(airPlayPort)
    }

    /** Whether the iPhone tried to connect to [port] at or after [sinceNanos]; see [NcmTrafficCounter]. */
    fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean = synchronized(stateLock) {
        nativeEngine.takeIf { it != 0L }?.let { NativeMedia.ncmAttemptSince(it, port, sinceNanos) }
            ?: traffic.connectionAttemptSince(port, sinceNanos)
    }

    /**
     * Hands both directions of the data link to `libdiplay_media` (the default wired data path): bulk
     * IN/OUT and [tun] then never pass through Java. Call before any [recv]: a queued Java read would take
     * the engine's data. False when the engine cannot start; the caller then bridges in Kotlin.
     */
    fun startNative(tun: ParcelFileDescriptor, hostMac: ByteArray, onDiagnosticLine: (String) -> Unit, onError: (Throwable) -> Unit): Boolean {
        NativeMedia.requireAvailable()
        val callbackIn = nativeCallbacks("inbound", onDiagnosticLine, onError)
        val callbackOut = nativeCallbacks("outbound", onDiagnosticLine, onError)
        // Keep managed owners throughout startup. There is no detached-FD/Pair allocation gap,
        // and JNI only borrows these descriptors: native duplicates them for its own lifetime.
        var usb: ParcelFileDescriptor? = null
        var tunnel: ParcelFileDescriptor? = null
        var starting = false
        try {
            synchronized(stateLock) {
                if (closed || readQueued || nativeStarting || nativeEngine != 0L) return false
                nativeStarting = true
                starting = true
                // Duplicate while close cannot retire the connection and recycle its FD.
                usb = runCatching { ParcelFileDescriptor.fromFd(connection.fileDescriptor) }.getOrNull() ?: return false
                tunnel = runCatching { tun.dup() }.getOrNull() ?: return false
            }
            val handle = NativeMedia.ncmStart(usb!!.fd, inEndpoint.address, outEndpoint.address, usbPacketSize, tunnel!!.fd, hostMac,
                callbackIn, callbackOut)
            if (handle == 0L) return false
            val accepted = synchronized(stateLock) {
                if (closed) false else { nativeEngine = handle; true }
            }
            if (!accepted) { NativeMedia.ncmStop(handle); return false }
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm data path native in=${inEndpoint.address} out=${outEndpoint.address} packet=$usbPacketSize")
            return true
        } finally {
            // Independent closes also cover argument evaluation/JNI entry failures and native
            // exceptions. Native never owns these numbers, so no return-value ownership guess.
            try { usb?.close() } catch (_: Throwable) { }
            try { tunnel?.close() } catch (_: Throwable) { }
            if (starting) synchronized(stateLock) { nativeStarting = false }
        }
    }

    private fun nativeCallbacks(direction: String, onDiagnosticLine: (String) -> Unit, onError: (Throwable) -> Unit) = object : NativeStreamCallbacks() {
        override fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long) = NativeMedia.packetRelease(handle)
        override fun onLog(level: Int, line: String) { if (level == 0) Log.i(IphoneCarPlayConfiguration.TAG, line) else Log.w(IphoneCarPlayConfiguration.TAG, line) }
        override fun onReport(line: String) { runCatching { onDiagnosticLine(line) } }
        override fun onEnded(error: String?) {
            if (!closedState()) onError(IOException("NCM $direction ended natively: ${error ?: "end of stream"}"))
        }
    }

    private fun closedState(): Boolean = synchronized(stateLock) { closed }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            pollFrame()?.let { return it }

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                pollFrame()?.let { return it }
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val (engine, requestToClose) = synchronized(stateLock) {
            if (closed) return
            closed = true
            val retired = nativeEngine
            nativeEngine = 0L
            retired to readRequest
        }
        if (engine != 0L) NativeMedia.ncmStop(engine)
        receiveCore.close()
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        readRequests.close()
        connection.close()
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        val detail = arrayOfNulls<String>(1)
        var now: Long? = null
        while (true) {
            val action = receiveCore.step(now, detail)
            now = null
            when (action) {
                0 -> return
                1 -> now = System.nanoTime()
                2 -> runCatching { onDiagnostic(requireNotNull(detail[0])) }
                -1 -> throw failSession(requireNotNull(detail[0]))
                else -> error("Unknown NCM receive action $action")
            }
        }
    }

    private fun appendBuffered(source: ByteArray, length: Int) = receiveCore.append(source, length)

    private fun pollFrame(): ByteArray? = receiveCore.poll()?.also(traffic::received)

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                check(!nativeStarting && nativeEngine == 0L) { "NCM native receiver owns bulk IN" }
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) { readRequests.queue(current, it) }
                    if (!queued.queued) throw failSession(
                        "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
                            "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                            "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                    )
                    readQueued = true
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        try {
            val completed = try {
                readRequests.await(timeoutMillis.coerceAtLeast(1))
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    companion object {
        /** The app's preferences file and the key of the native wired data path (enabled unless explicitly disabled). */
        const val NATIVE_DATA_PATH_PREFS = "diplay_airplay"
        const val NATIVE_DATA_PATH_KEY = "native_wired_data_path"

        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        internal const val USB_SETUP_ATTEMPTS = 5
        private const val USB_SETUP_RETRY_MILLIS = 100L
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        internal const val NOT_READY_WRITE_TIMEOUT_MILLIS = 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * Retries a USB setup call that a vendor kernel can lose to its own cdc_ncm driver, which may
         * rebind between Android's forced detach and the claim (#518). Returns the successful attempt.
         */
        internal fun retryUsbSetup(
            attempts: Int = USB_SETUP_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
            action: () -> Boolean,
        ): Int? {
            for (attempt in 1..attempts) {
                if (action()) return attempt
                if (attempt < attempts) pause(USB_SETUP_RETRY_MILLIS)
            }
            return null
        }

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = retryUsbSetup { connection.claimInterface(first, true) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=${firstClaimed != null}" +
                        " attempts=${firstClaimed ?: USB_SETUP_ATTEMPTS}",
                )
                if (firstClaimed == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = retryUsbSetup { connection.claimInterface(function.data, true) }
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=${dataClaimed != null}" +
                            " attempts=${dataClaimed ?: USB_SETUP_ATTEMPTS}",
                    )
                    if (dataClaimed == null) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = retryUsbSetup { connection.setInterface(function.data) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=${altSelected != null}" +
                        " attempts=${altSelected ?: USB_SETUP_ATTEMPTS}",
                )
                if (altSelected == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    onDiagnostic,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
