package com.shihab.diplay.network

import android.net.wifi.p2p.WifiP2pManager
import com.shihab.diplay.airplay.NativeMedia
import java.io.IOException

internal enum class P2pCreationMode {
    ALIGNED_5_GHZ, ALIGNED_2_GHZ, FIXED_5_GHZ, FIXED_2_GHZ, SYSTEM_DEFAULT, PREFERRED_CHANNEL,
    /** Band-only request (API 29+): the driver picks the channel inside the band. */
    BAND_5_GHZ, BAND_2_GHZ,
}

internal data class P2pCreationRequest(val mode: P2pCreationMode, val frequencyMHz: Int? = null)

/** [replyMillis]: from createGroup to this answer, when known. */
internal class P2pCreateRejected(val reason: Int, message: String, val replyMillis: Long? = null) : IOException(message)

internal object P2pHotspotConflict {
    fun failure(sdk: Int, hotspotOn: Boolean?, rejection: P2pCreateRejected): WirelessStartupException? {
        if (!NativeP2pStartup.hotspotConflict(sdk, hotspotOn == true, rejection.reason == WifiP2pManager.BUSY,
                rejection.replyMillis ?: 0L)) return null
        return WirelessStartupException(WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT,
            "Android was not allowed to turn the car hotspot off for Wi-Fi Direct").apply { initCause(rejection) }
    }
}

/** Raised only for a recognized framework defect before createGroup has been called. */
internal class P2pConfigBuildCompatibilityFailure(cause: NoSuchMethodError) :
    IOException("Wi-Fi P2P custom configuration unavailable on this Android 10 framework", cause)

/** Rust owns channel plans and retry budgets; Android requests and original failures stay here. */
internal object P2pStartupRecovery {
    fun rememberedFrequency(frequency: Int): P2pCreationRequest? =
        NativeP2pStartup.remembered(frequency).takeIf { it >= 0 }?.let { P2pCreationRequest(P2pCreationMode.entries[it], frequency) }

    fun plan(stationFrequency: Int?, preferred: P2pCreationRequest? = null,
             preferredChannel: Int = WifiP2pChannels.AUTO,
             bandRequests: Boolean = true,
             fiveGhz: Boolean = true): List<P2pCreationRequest> {
        require(WifiP2pChannels.isValid(preferredChannel)) { "Unsupported Wi-Fi Direct channel: $preferredChannel" }
        val values = NativeP2pStartup.plan(stationFrequency ?: 0, preferred?.mode?.ordinal ?: -1,
            preferred?.frequencyMHz ?: 0, preferred?.frequencyMHz != null, preferredChannel, bandRequests, fiveGhz)
        return values.indices.step(2).map { P2pCreationRequest(P2pCreationMode.entries[values[it]], values[it + 1].takeIf { it != 0 }) }
    }

    fun create(
        stationFrequency: Int?,
        beforeRetry: () -> Unit,
        preferred: P2pCreationRequest? = null,
        preferredChannel: Int = WifiP2pChannels.AUTO,
        bandRequests: Boolean = true,
        fiveGhz: Boolean = true,
        busyCause: (P2pCreateRejected) -> IOException? = { null },
        request: (P2pCreationRequest) -> Unit,
    ): P2pCreationRequest {
        require(WifiP2pChannels.isValid(preferredChannel)) { "Unsupported Wi-Fi Direct channel: $preferredChannel" }
        val owner = NativeP2pStartup.start(stationFrequency ?: 0, preferred?.mode?.ordinal ?: -1,
            preferred?.frequencyMHz ?: 0, preferred?.frequencyMHz != null, preferredChannel, bandRequests, fiveGhz)
        while (true) {
            val spec = NativeP2pStartup.current(owner)
            val mode = P2pCreationRequest(P2pCreationMode.entries[spec[0]], spec[1].takeIf { it != 0 })
            try {
                request(mode)
                return mode
            } catch (failure: P2pConfigBuildCompatibilityFailure) {
                when (NativeP2pStartup.compatibility(owner)) {
                    0 -> throw failure
                    1 -> throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                    else -> {
                        beforeRetry()
                        val fallbackSpec = NativeP2pStartup.current(owner)
                        val fallback = P2pCreationRequest(P2pCreationMode.entries[fallbackSpec[0]], fallbackSpec[1].takeIf { it != 0 })
                        // A default SDK failure exits directly; it must not reenter the channel plan.
                        request(fallback)
                        return fallback
                    }
                }
            } catch (failure: P2pCreateRejected) {
                val busy = failure.reason == WifiP2pManager.BUSY
                if (busy) busyCause(failure)?.let { throw it }
                when (NativeP2pStartup.rejected(owner, busy,
                    failure.reason == WifiP2pManager.NO_PERMISSION || failure.reason == WifiP2pManager.P2P_UNSUPPORTED)) {
                    0 -> throw failure
                    1 -> throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                    else -> beforeRetry()
                }
            }
        }
    }
}

internal object NativeP2pStartup {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun validChannel(channel: Int): Boolean
    @JvmStatic external fun frequency(channel: Int): Int
    @JvmStatic external fun remembered(frequency: Int): Int
    @JvmStatic external fun hotspotConflict(sdk: Int, hotspotOn: Boolean, busy: Boolean, replyMillis: Long): Boolean
    @JvmStatic external fun plan(station: Int, preferredMode: Int, preferredFrequency: Int, hasPreferredFrequency: Boolean,
        channel: Int, bandRequests: Boolean, fiveGhz: Boolean): IntArray
    @JvmStatic external fun start(station: Int, preferredMode: Int, preferredFrequency: Int, hasPreferredFrequency: Boolean,
        channel: Int, bandRequests: Boolean, fiveGhz: Boolean): IntArray
    @JvmStatic external fun current(owner: IntArray): IntArray
    @JvmStatic external fun compatibility(owner: IntArray): Int
    @JvmStatic external fun rejected(owner: IntArray, busy: Boolean, terminal: Boolean): Int
}
