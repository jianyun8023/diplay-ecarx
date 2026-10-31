package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Preferred band for an automatic car-hotspot start.
 *
 * The head unit keeps the hotspot band in its saved [WifiConfiguration]: `apBand` 0 is 2.4 GHz and
 * 1 is 5 GHz. The car's own settings app changes it the same way (see the recovered
 * `com.ecarx.settings.wifiap.WifiAPManager.setCurrentFrequencyMode` and
 * `IWifiAPHost.setFrequencyMode`), and only writes the saved configuration, so the access point
 * has to be off for the change to reach the next start.
 *
 * This helper preserves the saved SSID, passphrase and security. It never touches a running AP and
 * never writes when the radio does not advertise dual-band support. A failed read or write leaves
 * the saved configuration unchanged and reports the reason, so the caller can keep using the
 * car's saved band instead of failing the whole hotspot startup.
 */
object CarHotspotBand {
    enum class Result(val diagnostic: String) {
        ALREADY_5GHZ("car hotspot band is already 5 GHz"),
        CHANGED("car hotspot band changed to 5 GHz"),
        UNSUPPORTED("this Android version does not let DiPlay write the car hotspot band"),
        NOT_DUAL_BAND("this radio does not report dual-band support"),
        UNAVAILABLE("the car hotspot configuration could not be read"),
        FAILED("the car hotspot band write failed"),
    }

    /** Only the versions whose app-side [WifiManager.setWifiApConfiguration] path DiPlay already uses. */
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk == Build.VERSION_CODES.N_MR1 || sdk == Build.VERSION_CODES.P

    /**
     * Blocking; call off the main thread. Changes only the saved configuration, so a running
     * hotspot is not disturbed. The caller must check that the AP is off first.
     */
    fun preferFiveGhz(
        context: Context,
        sdk: Int = Build.VERSION.SDK_INT,
        log: (String) -> Unit,
    ): Result {
        if (!supported(sdk)) return report(Result.UNSUPPORTED, log)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return report(Result.UNAVAILABLE, log)
        val managerType = WifiManager::class.java
        return preferFiveGhz(
            dualBand = runCatching {
                managerType.getMethod("isDualBandSupported").invoke(wifi) as? Boolean
            }.getOrNull() == true,
            read = {
                runCatching { managerType.getMethod("getWifiApConfiguration").invoke(wifi) }
                    .getOrNull() as? WifiConfiguration
            },
            write = { config ->
                runCatching {
                    managerType.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
                        .invoke(wifi, config)
                }.getOrNull() == true
            },
            log = log,
        )
    }

    internal fun preferFiveGhz(
        dualBand: Boolean,
        read: () -> WifiConfiguration?,
        write: (WifiConfiguration) -> Boolean,
        log: (String) -> Unit,
    ): Result {
        if (!dualBand) return report(Result.NOT_DUAL_BAND, log)
        val current = read() ?: return report(Result.UNAVAILABLE, log)
        return try {
            val bandField = WifiConfiguration::class.java.getField("apBand")
            val fiveGhz = WifiConfiguration::class.java.getField("AP_BAND_5GHZ").getInt(null)
            if (bandField.getInt(current) == fiveGhz) return report(Result.ALREADY_5GHZ, log)

            val proposed = WifiConfiguration(current)
            bandField.setInt(proposed, fiveGhz)
            // The saved channel may belong to 2.4 GHz. Zero lets the driver choose a 5 GHz channel.
            runCatching { WifiConfiguration::class.java.getField("apChannel").setInt(proposed, 0) }
            if (!write(proposed)) return report(Result.FAILED, log)

            val saved = read()
            val savedBand = saved?.let { runCatching { bandField.getInt(it) }.getOrNull() }
            if (savedBand == fiveGhz) report(Result.CHANGED, log) else report(Result.FAILED, log)
        } catch (error: Exception) {
            log("car hotspot band: ${error.javaClass.simpleName}")
            report(Result.FAILED, log)
        }
    }

    private fun report(result: Result, log: (String) -> Unit): Result {
        log("car hotspot band: ${result.diagnostic}")
        return result
    }
}
