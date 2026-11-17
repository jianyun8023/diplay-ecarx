package com.shihab.diplay.network

import android.content.Context
import java.io.InterruptedIOException

/**
 * One-shot, fail-open wait for the car hotspot a boot auto-start needs before it opens the CarPlay
 * screen.
 *
 * The hotspot is car-controlled: across five cold boots on the ECARX 753 the AP became enabled
 * somewhere between boot+27 s and boot+1239 s. Opening the CarPlay screen before that only produced
 * a splash that waited for the same hotspot again inside `CarPlayController`, so the boot launch can
 * wait here instead, with a hard cap.
 *
 * This is not a second address sampler. [ManualHotspotInterfaces] samples interfaces and the AP
 * state ([CarHotspotStatus]), and [ManualHotspotReadiness] applies the existing stable-sample rule
 * and its own poll interval (at most [POLL_MILLIS], owned by `session::hotspot_recovery`).
 */
object CarHotspotReady {
    /** The existing readiness rule never waits longer than this between two samples. */
    const val POLL_MILLIS = 250L

    enum class Reason {
        /** A usable hotspot address was observed and confirmed by the existing stable-sample rule. */
        READY,

        /** The hotspot stayed off or without a usable address for the whole timeout. */
        TIMEOUT,

        /** [isCurrent] turned false, or the waiting thread was interrupted; nothing was confirmed. */
        CANCELLED,

        /**
         * The firmware does not report the AP state, so there is no evidence either way; the boot
         * must not be delayed on that.
         */
        UNOBSERVABLE,

        /** Sampling or the readiness core failed; the caller opens anyway. */
        ERROR,
    }

    /** [address] is `iface/hostAddress` of the confirmed hotspot, or null when none was confirmed. */
    data class Outcome(
        val reason: Reason,
        val waitedMs: Long,
        val apEnabled: Boolean?,
        val address: String?,
    )

    /**
     * Waits on the calling thread (never the main thread) until the car hotspot is usable,
     * [timeoutMillis] passes, or [isCurrent] turns false. Never throws: every failure is an
     * [Outcome] so the caller can fail open.
     */
    fun await(
        context: Context,
        timeoutMillis: Long,
        isCurrent: () -> Boolean,
        log: (String) -> Unit = {},
        /** The saved "hotspot interface" setting, for units whose AP is not a platform Wi-Fi AP. */
        configuredInterface: String? = null,
    ): Outcome {
        val app = context.applicationContext
        val interfaces = runCatching { ManualHotspotInterfaces(app, log, configuredInterface) }.getOrNull()
            ?: return Outcome(Reason.ERROR, 0, null, null)
        return interfaces.use {
            await(
                sample = it::sample, isCurrent = isCurrent, timeoutMillis = timeoutMillis, log = log,
                configuredInterface = configuredInterface,
            )
        }
    }

    /**
     * [sample], [pause] and [now] are seams for tests; production passes the existing readiness
     * pieces. A sample whose AP state is unobservable ends the wait at once: waiting would be a
     * delay without evidence.
     */
    internal fun await(
        sample: () -> HotspotNetworkSnapshot,
        isCurrent: () -> Boolean,
        timeoutMillis: Long,
        pause: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        log: (String) -> Unit = {},
        configuredInterface: String? = null,
    ): Outcome {
        val start = now()
        var apEnabled: Boolean? = null
        var unobservable = false
        val readiness = ManualHotspotReadiness(
            sample = {
                sample().also { snapshot ->
                    apEnabled = snapshot.apEnabled
                    if (snapshot.apEnabled == null) unobservable = true
                }
            },
            // The readiness loop only reports cancellation, so an unobservable AP state ends the
            // wait through the same hook and is told apart below.
            cancelled = { unobservable || !isCurrent() },
            pause = pause,
            nowMillis = now,
            log = log,
            configuredInterface = configuredInterface,
        )
        return try {
            val selection = readiness.await(timeoutMillis)
            log("boot hotspot ready iface=${selection.name} atMs=${now() - start}")
            Outcome(Reason.READY, now() - start, apEnabled, "${selection.name}/${selection.address.hostAddress}")
        } catch (timeout: WirelessStartupException) {
            log("boot hotspot not ready reason=timeout waitedMs=${now() - start}")
            Outcome(Reason.TIMEOUT, now() - start, apEnabled, null)
        } catch (interrupted: InterruptedIOException) {
            val reason = if (unobservable) Reason.UNOBSERVABLE else Reason.CANCELLED
            log("boot hotspot wait ended reason=$reason waitedMs=${now() - start}")
            Outcome(reason, now() - start, apEnabled, null)
        } catch (interrupted: InterruptedException) {
            log("boot hotspot wait interrupted waitedMs=${now() - start}")
            Outcome(Reason.CANCELLED, now() - start, apEnabled, null)
        } catch (error: Exception) {
            log("boot hotspot wait failed error=${error.javaClass.simpleName}")
            Outcome(Reason.ERROR, now() - start, apEnabled, null)
        }
    }
}
