package com.shihab.diplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.network.CarHotspotTethering

/** Each grant is requested explicitly from settings; startup never calls this authorization path. */
internal object CarHotspotSetup {
    enum class Phase { CHECKING, STARTING, WAITING_FOR_NETWORK, READY }

    /** Internet readiness is evidence for one startup retry, never a prerequisite for local CarPlay. */
    fun networkReady(context: Context): Boolean? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        @Suppress("DEPRECATION")
        connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }.getOrNull()

    /** Blocking worker entry, shared by launch and connect; never writes hotspot credentials. */
    fun prepare(context: Context, isCancelled: () -> Boolean, onProgress: (Phase) -> Unit, log: (String) -> Unit): CarHotspotTethering.Result =
        prepare(30_000L, isCancelled, onProgress, log,
            hotspotEnabled = { CarHotspotStatus.isEnabled(context) },
            networkReady = { networkReady(context) },
            enable = { remaining ->
                CarHotspotTethering.enable(context, isCancelled, remaining, log,
                    preferredBand = AirPlayPersistence.loadManualHotspotBand(context))
            })

    internal fun prepare(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        onProgress: (Phase) -> Unit,
        log: (String) -> Unit,
        hotspotEnabled: () -> Boolean?,
        networkReady: () -> Boolean?,
        enable: (Long) -> CarHotspotTethering.Result,
        clock: () -> Long = SystemClock::elapsedRealtime,
        pause: (Long) -> Unit = Thread::sleep,
    ): CarHotspotTethering.Result {
        val deadline = clock() + timeoutMillis
        fun stopped(): CarHotspotTethering.Result? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> CarHotspotTethering.Result.CANCELLED
            clock() >= deadline -> CarHotspotTethering.Result.TIMED_OUT
            else -> null
        }
        stopped()?.let { return it }
        onProgress(Phase.CHECKING)
        if (hotspotEnabled() == true) {
            onProgress(Phase.READY)
            return CarHotspotTethering.Result.READY
        }
        val initialNetwork = networkReady()
        log("car hotspot prepare: cellularReady=${initialNetwork ?: "unknown"} budgetMs=$timeoutMillis")
        stopped()?.let { return it }
        onProgress(Phase.STARTING)
        var result = enable(minOf(deadline - clock(), if (initialNetwork == false) 10_000L else timeoutMillis))
        stopped()?.let { return it }
        if (result == CarHotspotTethering.Result.READY) {
            onProgress(Phase.READY)
            return result
        }
        if (initialNetwork != false ||
            result != CarHotspotTethering.Result.FAILED && result != CarHotspotTethering.Result.TIMED_OUT) return result
        onProgress(Phase.WAITING_FOR_NETWORK)
        while (true) {
            stopped()?.let { return it }
            if (hotspotEnabled() == true) {
                onProgress(Phase.READY)
                return CarHotspotTethering.Result.READY
            }
            if (networkReady() == true) break
            try {
                pause(minOf(200L, deadline - clock()).coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return CarHotspotTethering.Result.CANCELLED
            }
        }
        stopped()?.let { return it }
        log("car hotspot prepare: cellular became ready; retrying once remainingMs=${deadline - clock()}")
        onProgress(Phase.STARTING)
        result = enable(deadline - clock())
        stopped()?.let { return it }
        if (result == CarHotspotTethering.Result.READY) onProgress(Phase.READY)
        return result
    }
    fun shouldStartOnLaunch(context: Context, hasSession: Boolean): Boolean =
        !hasSession && CarHotspotSettings.shouldEnable(context,
            AirPlayPersistence.loadWirelessEnabled(context), AirPlayPersistence.loadWirelessHotspotMode(context))
}
