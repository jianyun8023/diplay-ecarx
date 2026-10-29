package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.atomic.AtomicReference

/** Credentials for an explicit car-hotspot write; connection settings change only after verification. */
data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val security: ManualHotspotSecurity,
    val band: ManualHotspotBand = ManualHotspotBand.AUTO,
)

/**
 * Writes the car's saved hotspot configuration before starting it. Android 7.1 writes from the app:
 * the WifiConfiguration class is public, only WifiManager.setWifiApConfiguration is hidden, and its
 * gate is CHANGE_WIFI_STATE alone. Android 11+ writes through the shell helper, because the shell
 * holds NETWORK_SETTINGS on 11-12 and OVERRIDE_WIFI_CONFIG since 13. The platform-signed Android 9
 * vehicle build writes a copy of its saved WPA2 configuration and verifies the complete readback.
 * WPA3 hotspots are never downgraded.
 */
object CarHotspotConfigWrite {
    enum class Result(val diagnostic: String) {
        WRITTEN("car hotspot configuration written"),
        SKIPPED("car hotspot configuration write skipped"),
        UNSUPPORTED_SDK("this Android version does not allow writing the car hotspot configuration"),
        ADB_UNAVAILABLE("ADB access is missing for the car hotspot configuration write"),
        FAILED("the car hotspot configuration write failed"),
        OPEN_REJECTED("an open hotspot does not overwrite the car's password"),
    }

    /** The result of a write plus the SSID that is actually saved, which the car may have kept. */
    data class WriteOutcome(val result: Result, val ssid: String? = null)

    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk == Build.VERSION_CODES.N_MR1 || sdk == 28 || sdk >= Build.VERSION_CODES.R

    fun write(
        context: Context,
        credentials: HotspotCredentials,
        deadline: Long = Long.MAX_VALUE,
        isCancelled: () -> Boolean = { false },
        sdk: Int = Build.VERSION.SDK_INT,
        log: (String) -> Unit,
    ): WriteOutcome {
        if (!supported(sdk)) {
            log("car hotspot config: unsupported Android $sdk")
            return WriteOutcome(Result.UNSUPPORTED_SDK)
        }
        if (credentials.security != ManualHotspotSecurity.OPEN && credentials.security != ManualHotspotSecurity.WPA2) {
            log("car hotspot config: a ${credentials.security} hotspot is not rewritten")
            return WriteOutcome(Result.SKIPPED)
        }
        if (listOf(credentials.ssid, credentials.passphrase).any { it.contains('\n') || it.contains('\r') }) {
            log("car hotspot config: credentials contain a line break")
            return WriteOutcome(Result.SKIPPED)
        }
        if (sdk == 28 && (credentials.ssid.toByteArray(Charsets.UTF_8).size !in 1..32 ||
            credentials.ssid.any { it == '\u0000' || it == '\n' || it == '\r' } ||
            credentials.security != ManualHotspotSecurity.WPA2 || credentials.passphrase.length !in 8..63 ||
            credentials.passphrase.any { it.code !in 32..126 })) return WriteOutcome(Result.SKIPPED)
        return if (sdk <= Build.VERSION_CODES.N_MR1 || sdk == 28) writeWithApp(context, credentials, sdk, log)
        else writeWithAdb(context, credentials, deadline, isCancelled, log)
    }

    /** Android 7.1 stores raw (unquoted) SSID and passphrase, like the framework's own default AP configuration. */
    internal fun writeApp(
        credentials: HotspotCredentials,
        newConfig: () -> WifiConfiguration,
        write: (WifiConfiguration) -> Boolean?,
        readBack: () -> WifiConfiguration?,
        log: (String) -> Unit,
        sdk: Int = Build.VERSION_CODES.N_MR1,
    ): WriteOutcome {
        return try {
            val current = readBack()
            if (sdk == 28 && (current == null ||
                !current.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) ||
                current.allowedKeyManagement.cardinality() != 1)) {
                log("car hotspot config: the saved WPA2 configuration could not be read")
                return WriteOutcome(Result.SKIPPED)
            }
            if (credentials.security == ManualHotspotSecurity.OPEN && isSecured(current)) {
                log("car hotspot config: an open hotspot does not overwrite the car's password")
                return WriteOutcome(Result.OPEN_REJECTED)
            }
            val effective = if (sdk != 28 && current?.SSID?.isNotBlank() == true) credentials.copy(ssid = current.SSID) else credentials
            val config = if (sdk == 28) WifiConfiguration(current!!).apply {
                // The verified API-28 path changes credentials only; keep the car's radio/security settings.
                SSID = credentials.ssid
                preSharedKey = credentials.passphrase
            } else newConfig().apply {
                applyTo(this, effective)
                if (current != null) {
                    // The car keeps everything else about its saved configuration: hidden SSID and band/channel
                    // unless a 5 GHz move was asked for.
                    hiddenSSID = current.hiddenSSID
                    val type = WifiConfiguration::class.java
                    val currentBand = current.javaClass.getField("apBand").getInt(current)
                    val fiveGhz = type.getField("AP_BAND_5GHZ").getInt(null)
                    if (credentials.band != ManualHotspotBand.GHZ_5 || currentBand == fiveGhz) {
                        javaClass.getField("apBand").setInt(this, currentBand)
                        javaClass.getField("apChannel").setInt(this, current.javaClass.getField("apChannel").getInt(current))
                    }
                }
            }
            if (write(config) != true) {
                log("car hotspot config: the framework rejected the configuration")
                WriteOutcome(Result.FAILED)
            } else {
                val saved = readBack()
                when {
                    saved == null -> {
                        log("car hotspot config: no configuration to read back")
                        WriteOutcome(Result.FAILED)
                    }
                    matches(saved, effective) && (sdk != 28 ||
                        saved.allowedKeyManagement == config.allowedKeyManagement && saved.hiddenSSID == config.hiddenSSID &&
                        listOf("apBand", "apChannel").all { field ->
                            val member = WifiConfiguration::class.java.getField(field)
                            member.getInt(saved) == member.getInt(config)
                        }) -> WriteOutcome(Result.WRITTEN, effective.ssid)
                    else -> {
                        log("car hotspot config: readback does not match the written credentials")
                        WriteOutcome(Result.FAILED)
                    }
                }
            }
        } catch (error: Exception) {
            log("car hotspot config: app write failed ${error.javaClass.simpleName}")
            WriteOutcome(Result.FAILED)
        }
    }

    private fun writeWithApp(context: Context, credentials: HotspotCredentials, sdk: Int, log: (String) -> Unit): WriteOutcome {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return WriteOutcome(Result.FAILED).also { log("car hotspot config: no Wi-Fi manager") }
        val managerType = WifiManager::class.java
        return writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { config ->
                managerType.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
                    .invoke(wifi, config) as? Boolean
            },
            readBack = { managerType.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration },
            log = log, sdk = sdk)
    }

    /** True when the saved configuration is not open, so an open write would strip its password. */
    internal fun isSecured(config: WifiConfiguration?): Boolean {
        if (config == null) return false
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) return false
        return !config.preSharedKey.isNullOrEmpty() || config.allowedKeyManagement.cardinality() != 0
    }

    internal fun applyTo(config: WifiConfiguration, credentials: HotspotCredentials) {
        config.SSID = credentials.ssid
        if (credentials.security == ManualHotspotSecurity.WPA2) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
            config.preSharedKey = credentials.passphrase
        }
        if (credentials.band == ManualHotspotBand.GHZ_5) {
            // apBand was removed from the public SDK in API 27 but exists on 7.1.
            val type = WifiConfiguration::class.java
            config.javaClass.getField("apBand").set(config, type.getField("AP_BAND_5GHZ").getInt(null))
        }
    }

    internal fun matches(saved: WifiConfiguration?, credentials: HotspotCredentials): Boolean {
        if (saved == null) return false
        val sameKey = when (credentials.security) {
            ManualHotspotSecurity.WPA2 -> saved.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) &&
                saved.preSharedKey == credentials.passphrase
            ManualHotspotSecurity.OPEN -> saved.allowedKeyManagement.cardinality() == 0 ||
                saved.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
            else -> false
        }
        return sameKey && saved.SSID == credentials.ssid
    }

    private fun writeWithAdb(
        context: Context,
        credentials: HotspotCredentials,
        deadline: Long,
        isCancelled: () -> Boolean,
        log: (String) -> Unit,
    ): WriteOutcome {
        val client = AtomicReference<LocalAdb?>()
        val outcome = AtomicReference<WriteOutcome?>()
        CarHotspotAdbFallback.bounded(deadline, isCancelled,
            abort = { client.get()?.cancelPendingOperations() }) {
            val adb = LocalAdb(AdbKeys.load(context))
            client.set(adb)
            adb.use {
                val access = if (isCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) null
                    else adb.connect(mayAsk = false)
                if (access != null && access != LocalAdb.Access.READY) log("car hotspot config: ADB access=$access")
                if (access != LocalAdb.Access.READY) {
                    outcome.set(WriteOutcome(Result.ADB_UNAVAILABLE))
                    false
                } else {
                    val timeout = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1_000, 30_000).toInt()
                    outcome.set(writeAdb({ command, millis, input -> adb.shell(command, millis, input) },
                        context.applicationInfo.sourceDir, credentials, timeout, log))
                    outcome.get()?.result == Result.WRITTEN
                }
            }
        }
        return outcome.get() ?: WriteOutcome(Result.FAILED).also { log("car hotspot config: ADB write did not finish") }
    }

    internal fun writeAdb(
        shell: (String, Int, ByteArray?) -> String?,
        apk: String,
        credentials: HotspotCredentials,
        timeoutMillis: Int,
        log: (String) -> Unit,
    ): WriteOutcome {
        val passphrase = credentials.passphrase.toByteArray(Charsets.UTF_8)
        val stdin = "${passphrase.size}\n".toByteArray(Charsets.UTF_8) + passphrase
        val reply = CarHotspotStartMain.parseApConfig(
            shell(CarHotspotStartMain.apConfigCommand(apk, credentials), timeoutMillis, stdin))
        return when {
            reply != null && CarHotspotStartMain.apConfigAccepted(reply) ->
                WriteOutcome(Result.WRITTEN, CarHotspotStartMain.apConfigSsid(reply))
            reply == "error open" -> WriteOutcome(Result.OPEN_REJECTED)
            else -> WriteOutcome(Result.FAILED)
                .also { log("car hotspot config: shell write failed: ${reply ?: "no reply"}") }
        }
    }
}
