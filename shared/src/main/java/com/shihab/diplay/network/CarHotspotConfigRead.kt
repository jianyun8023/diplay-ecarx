package com.shihab.diplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shihab.diplay.orchestration.ManualHotspotSecurity

/** The hotspot details saved on the car, read back from its own configuration. */
data class CarHotspotSavedCredentials(
    val ssid: String,
    val passphrase: String,
    val security: ManualHotspotSecurity,
)

/** Reads the Android 9 vehicle hotspot directly using its platform permissions. */
object CarHotspotConfigRead {
    enum class Reason { UNSUPPORTED_SDK, UNAVAILABLE }

    data class Outcome(
        val credentials: CarHotspotSavedCredentials? = null,
        val reason: Reason? = null,
    )

    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT, log: (String) -> Unit): Outcome {
        if (!CarHotspotConfigWrite.supported(sdk)) {
            log("car hotspot config: unsupported Android $sdk")
            return Outcome(reason = Reason.UNSUPPORTED_SDK)
        }
        return readWithApp(context, log)
    }

    private fun readWithApp(context: Context, log: (String) -> Unit): Outcome {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return Outcome(reason = Reason.UNAVAILABLE).also { log("car hotspot config: no Wi-Fi manager") }
        val managerType = WifiManager::class.java
        return readApp({ managerType.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration }, log)
    }

    internal fun readApp(read: () -> WifiConfiguration?, log: (String) -> Unit): Outcome = try {
        val config = read()
        val keyManagement = config?.allowedKeyManagement
        val credentials = savedCredentials(
            ssid = config?.SSID,
            passphrase = config?.preSharedKey ?: "",
            security = when {
                keyManagement == null -> null
                keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) -> ManualHotspotSecurity.WPA2
                keyManagement.cardinality() == 0 || keyManagement.get(WifiConfiguration.KeyMgmt.NONE) ->
                    ManualHotspotSecurity.OPEN
                else -> null
            },
            log = log)
        if (credentials == null) Outcome(reason = Reason.UNAVAILABLE) else Outcome(credentials)
    } catch (error: Exception) {
        log("car hotspot config: app read failed ${error.javaClass.simpleName}")
        Outcome(reason = Reason.UNAVAILABLE)
    }

    private fun savedCredentials(
        ssid: String?,
        passphrase: String,
        security: ManualHotspotSecurity?,
        log: (String) -> Unit,
    ): CarHotspotSavedCredentials? {
        if (ssid.isNullOrBlank()) {
            log("car hotspot config: the car saved no hotspot name")
            return null
        }
        if (security == null) {
            log("car hotspot config: the car saved an unsupported security mode")
            return null
        }
        if (security == ManualHotspotSecurity.OPEN && passphrase.isNotEmpty()) {
            log("car hotspot config: an open hotspot with a passphrase")
            return null
        }
        if (security != ManualHotspotSecurity.OPEN && passphrase.isBlank()) {
            log("car hotspot config: a secured hotspot without a passphrase")
            return null
        }
        return CarHotspotSavedCredentials(ssid, passphrase, security)
    }
}
