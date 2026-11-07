package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-only tethered Soft AP client count and state for Android 9 (API 28).
 *
 * The hidden callback may be unavailable on vendor builds even with NETWORK_SETTINGS. The observer
 * never changes hotspot state or credentials; it only tells its owner, through [onApLost], that
 * the AP it attached to went down. A missing
 * callback is UNKNOWN, never zero. Counts include all stations on the system AP, not necessarily
 * the CarPlay iPhone (and may include a companion SSID).
 *
 * Registration and unregistration are dispatched away from startup/teardown and media threads.
 */
internal class Android9SoftApClientObserver(
    private val registrar: Registrar,
    private val nowNs: () -> Long = System::nanoTime,
    private val dispatch: (() -> Unit) -> Unit = { action ->
        Thread(action, "diplay-softap-client-observer").apply { isDaemon = true; start() }
    },
    private val log: (String) -> Unit = {},
    /** The AP went from enabled to disabling, disabled or failed while observed; called once. */
    private val onApLost: () -> Unit = {},
) : Closeable {
    fun interface Registrar {
        fun register(onClients: (Int) -> Unit, onState: (Int) -> Unit): Closeable
    }

    private data class Sample(
        val clients: Int? = null,
        val clientsAtNs: Long? = null,
        val apState: Int? = null,
    )

    private val sample = AtomicReference(Sample())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lostReported = AtomicBoolean(false)
    private val registrationLock = Any()
    private var registration: Closeable? = null

    @Volatile private var observation = "not_started"
    @Volatile private var failureClass: String? = null

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        observation = "registering"
        try {
            dispatch {
                if (closed.get()) return@dispatch
                try {
                    val handle = registrar.register(::onClients, ::onState)
                    val retained = synchronized(registrationLock) {
                        if (closed.get()) false else {
                            registration = handle
                            observation = "registered"
                            true
                        }
                    }
                    // Registration may have completed after close(); do not leak the callback.
                    if (!retained) safelyClose(handle) else report("observation=registered")
                } catch (error: Exception) {
                    markUnavailable(error)
                } catch (error: LinkageError) {
                    markUnavailable(error)
                }
            }
        } catch (error: Exception) {
            markUnavailable(error)
        }
    }

    private fun onClients(count: Int) {
        if (closed.get() || count < 0) return
        val previous = sample.getAndUpdate { it.copy(clients = count, clientsAtNs = nowNs()) }
        if (!closed.get() && previous.clients != count) {
            // Record short-lived transitions even if the 10 s startup snapshot misses them.
            report("associatedClients=$count")
        }
    }

    private fun onState(state: Int) {
        if (closed.get()) return
        val previous = sample.getAndUpdate { it.copy(apState = state) }.apState
        if (previous == state) return
        // Logged with its time: something else stopping the AP shows up here first.
        report("state=${stateLabel(state)} previous=${stateLabel(previous)}")
        if (previous == AP_ENABLED && state in AP_DOWN && lostReported.compareAndSet(false, true)) {
            runCatching { onApLost() }
        }
    }

    private fun markUnavailable(error: Throwable) {
        if (closed.get()) return
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        failureClass = cause.javaClass.simpleName
        observation = "unavailable"
        report("observation=unavailable failureClass=${failureClass ?: "Unknown"}")
    }

    private fun report(message: String) {
        if (!closed.get()) runCatching { log("wireless SoftAP $message") }
    }

    fun diagnosticSnapshot(): String {
        val phase = if (closed.get()) "closed" else observation
        if (phase == "unavailable") {
            return "softApClients=unavailable softApObservation=unavailable " +
                "failureClass=${failureClass ?: "Unknown"}"
        }
        val current = sample.get()
        val age = current.clientsAtNs?.let {
            ((nowNs() - it).coerceAtLeast(0L) / 1_000_000L).toString()
        } ?: "none"
        return "softApClients=${current.clients ?: "unknown"} " +
            "softApState=${stateLabel(current.apState)} softApCallbackAgeMs=$age " +
            "softApObservation=$phase"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observation = "closed"
        val handle = synchronized(registrationLock) {
            registration.also { registration = null }
        }
        if (handle != null) {
            // Never hold the controller's wireless resource lock over a vendor Wi-Fi Binder call.
            try {
                dispatch { safelyClose(handle) }
            } catch (_: Exception) {
                safelyClose(handle)
            }
        }
    }

    private fun safelyClose(handle: Closeable) {
        runCatching { handle.close() }
    }

    private fun stateLabel(value: Int?): String = when (value) {
        10 -> "disabling"
        11 -> "disabled"
        12 -> "enabling"
        13 -> "enabled"
        14 -> "failed"
        else -> "unknown"
    }

    companion object {
        private const val AP_ENABLED = 13
        private val AP_DOWN = setOf(10, 11, 14)

        /**
         * Android 9 AOSP: WifiManager.registerSoftApCallback(SoftApCallback, Handler).
         * Android 10+ uses a different signature; callers must explicitly guard API 28.
         * A vendor-hidden API failure is only an unavailable diagnostic, not startup failure.
         */
        @SuppressLint("PrivateApi")
        fun forWifiManager(
            wifi: WifiManager,
            log: (String) -> Unit = {},
            onApLost: () -> Unit = {},
        ): Android9SoftApClientObserver =
            Android9SoftApClientObserver(
                registrar = Registrar { onClients, onState ->
                    val type = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
                    val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                        when (method.name) {
                            "onNumClientsChanged" -> {
                                (args?.getOrNull(0) as? Number)?.toInt()?.let(onClients)
                                null
                            }
                            "onStateChanged" -> {
                                (args?.getOrNull(0) as? Number)?.toInt()?.let(onState)
                                null
                            }
                            "hashCode" -> System.identityHashCode(self)
                            "equals" -> self === args?.getOrNull(0)
                            "toString" -> "DiPlay read-only SoftAP observer"
                            else -> null
                        }
                    }
                    val register = WifiManager::class.java.getMethod(
                        "registerSoftApCallback", type, Handler::class.java,
                    )
                    val unregister = WifiManager::class.java.getMethod("unregisterSoftApCallback", type)
                    register.invoke(wifi, callback, Handler(Looper.getMainLooper()))
                    Closeable { unregister.invoke(wifi, callback) }
                },
                log = log,
                onApLost = onApLost,
            )
    }
}
