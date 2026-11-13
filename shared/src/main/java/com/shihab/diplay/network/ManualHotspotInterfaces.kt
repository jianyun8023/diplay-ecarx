package com.shihab.diplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

internal class ManualHotspotInterfaces(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
    private val configuredInterface: String? = null,
) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val publicTethering = if (Build.VERSION.SDK_INT >= 36) PublicTethering(context) else null
    private var lastLegacyDiagnostic: String? = null
    private var lastOwnershipDiagnostic: String? = null

    fun sample(): HotspotNetworkSnapshot {
        val before = runCatching { connectivity?.activeNetwork }
        val upstreams = runCatching {
            checkNotNull(connectivity)
            connectivity.allNetworks.mapNotNull { network ->
                val caps = checkNotNull(connectivity.getNetworkCapabilities(network))
                val links = checkNotNull(connectivity.getLinkProperties(network))
                links.interfaceName?.takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            }.toSet()
        }.getOrNull()
        val defaultName = runCatching {
            before.getOrNull()?.let { connectivity?.getLinkProperties(it)?.interfaceName }
        }.getOrNull()
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { iface ->
                runCatching {
                    if (iface.isLoopback) null else HotspotInterfaceSnapshot(
                        iface.name, iface.index, iface.isUp, Collections.list(iface.inetAddresses),
                        wirelessInterfaceName(iface.name) || File("/sys/class/net/${iface.name}/wireless").isDirectory,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        val apEnabled = CarHotspotStatus.isEnabled(context)
        val ap = apInterfaces(
            observed = publicTethering?.interfaces ?: legacyApInterfaces(),
            interfaces = interfaces,
            apEnabled = apEnabled,
            upstreams = upstreams,
            defaultInterface = defaultName,
        )
        val after = runCatching { connectivity?.activeNetwork }
        return HotspotNetworkSnapshot(
            interfaces, ap, upstreams, defaultName,
            consistent = before.isSuccess && after.isSuccess && before.getOrNull() == after.getOrNull(),
            apEnabled = apEnabled,
        )
    }

    /**
     * Some head units run the car hotspot on a Wi-Fi chip the platform never reports as a tether
     * interface, and hand the clients to the Android side over a VLAN of the wired interface, such
     * as `eth0.3` on a QUALCOMM CS11 unit (#573). Tethering detection only ever names Wi-Fi
     * interfaces, so that VLAN never enters the AP set and readiness rejects it as
     * `not_platform_ap`. A configured interface, or a discovered non-wireless interface that carries
     * a hotspot address, is added to the set instead.
     */
    private fun apInterfaces(
        observed: Set<String>?,
        interfaces: List<HotspotInterfaceSnapshot>,
        apEnabled: Boolean?,
        upstreams: Set<String>?,
        defaultInterface: String?,
    ): Set<String>? {
        val explicit = configuredInterface?.trim()?.takeIf { it.isNotEmpty() }
        val owned = when {
            // An explicit choice is honoured even when the platform already lists a Wi-Fi AP.
            explicit != null -> (observed ?: emptySet()) + explicit
            // Only when the hotspot is reported on and no tethered Wi-Fi interface exists. An
            // unobservable set stays unobservable, so the fallback evidence keeps working.
            apEnabled == true && observed?.isEmpty() == true -> {
                val discovered = hotspotCarrierCandidates(interfaces, upstreams.orEmpty(), defaultInterface)
                if (discovered.isEmpty()) observed else discovered
            }
            else -> observed
        }
        val diagnostic = "manual hotspot interface override=${explicit ?: "none"} " +
            "observedAp=${observed ?: "unobservable"} selectedAp=$owned"
        if (diagnostic != lastOwnershipDiagnostic) {
            lastOwnershipDiagnostic = diagnostic
            onDiagnostic(diagnostic)
        }
        return owned
    }

    /**
     * Interfaces that plausibly carry a hotspot the platform will not name. See
     * [inferHotspotCarrierCandidates] for the acceptance rules.
     */
    private fun hotspotCarrierCandidates(
        interfaces: List<HotspotInterfaceSnapshot>,
        upstreams: Set<String>,
        defaultInterface: String?,
    ): Set<String> = inferHotspotCarrierCandidates(
        interfaces, upstreams, defaultInterface, EXCLUDED_INTERFACES, DEFAULT_CAR_HOTSPOT_INTERFACE,
    )

    // 旧平台只使用允许读取的结果；接口归属读不到时保持 unknown，不放宽普通网卡资格。
    @SuppressLint("PrivateApi")
    private fun legacyApInterfaces(): Set<String>? = runCatching {
        val manager = checkNotNull(connectivity)
        val tethered = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            .invoke(manager) as Array<*>
        val regexes = ConnectivityManager::class.java.getMethod("getTetherableWifiRegexs")
            .invoke(manager) as Array<*>
        val names = tethered.filterIsInstance<String>()
        val patterns = regexes.filterIsInstance<String>()
        val ap = legacyHotspotInterfaces(names, patterns)
        val diagnostic = "legacy hotspot ownership=${if (ap == null) "unobservable" else "observed"} " +
            "tethered=$names wifiRegexes=$patterns matched=$ap"
        if (diagnostic != lastLegacyDiagnostic) {
            lastLegacyDiagnostic = diagnostic
            onDiagnostic(diagnostic)
        }
        ap
    }.getOrNull()

    override fun close() { publicTethering?.close() }

    companion object {
        /** Interface that carried the car hotspot on the reference QUALCOMM CS11 unit (#573). */
        const val DEFAULT_CAR_HOTSPOT_INTERFACE = "eth0.3"

        // Virtual interfaces that carry vendor plumbing, not the hotspot an iPhone joins.
        internal val EXCLUDED_INTERFACES = setOf("dummy0", "sit0", "bond0", "ip6tnl0", "tunl0", "rmnet0")
    }

    @RequiresApi(36)
    private class PublicTethering(context: Context) : Closeable {
        @Volatile var interfaces: Set<String>? = null
            private set
        private val manager = context.getSystemService(TetheringManager::class.java)
        private val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                this@PublicTethering.interfaces = interfaces.filter { it.type == TetheringManager.TETHERING_WIFI }
                    .map { it.getInterface() }.toSet()
            }
        }
        private val registered = runCatching {
            checkNotNull(manager).registerTetheringEventCallback(context.mainExecutor, callback)
        }.isSuccess

        override fun close() {
            if (registered) runCatching { manager?.unregisterTetheringEventCallback(callback) }
        }
    }
}

/**
 * An address a hotspot publishes to its clients: a usable IPv4, or a scoped link-local IPv6.
 *
 * This stays deliberately broad because the platform-owned and explicitly configured paths rely on
 * it to accept an IPv6-only hotspot. Only the automatic inference in [inferHotspotCarrierCandidates]
 * requires the stricter [publishesHotspotIpv4] evidence, so a bare `fe80::` on an internal wired
 * interface cannot be mistaken for the hotspot carrier.
 */
internal fun publishesHotspotAddress(address: java.net.InetAddress): Boolean = when (address) {
    is Inet4Address -> publishesHotspotIpv4(address)
    is Inet6Address -> address.isLinkLocalAddress
    else -> false
}

/**
 * A usable IPv4 address a hotspot would publish to its clients. Link-local IPv6 is intentionally
 * excluded: it is the evidence the automatic inference must not treat as hotspot ownership.
 */
internal fun publishesHotspotIpv4(address: java.net.InetAddress): Boolean =
    address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress &&
        !address.isLinkLocalAddress && !address.isMulticastAddress

/**
 * Interfaces that plausibly carry a hotspot the platform will not name: up, not wireless, not an
 * upstream, not the default route, not a virtual/mobile-data interface, and holding a publishable
 * IPv4 address. The reference vendor interface wins over an inferred one, so the pick stays
 * deterministic on firmware where several VLANs share one address.
 *
 * Inference is intentionally stricter than the platform-owned and explicitly configured paths: it
 * demands a publishable IPv4 address and rejects a bare link-local IPv6 (`fe80::`). Such an address
 * is common on internal wired interfaces (`eth0`, `tam0`, …) and used to leak them into the
 * candidate set. A genuine IPv6 hotspot is still reachable through the platform's tethered report or
 * an explicitly configured interface, so IPv6 support is not disabled.
 */
internal fun inferHotspotCarrierCandidates(
    interfaces: List<HotspotInterfaceSnapshot>,
    upstreams: Set<String>,
    defaultInterface: String?,
    excludedInterfaces: Set<String>,
    preferredInterface: String,
): Set<String> {
    val eligible = interfaces.filter { iface ->
        iface.up && iface.index > 0 && !iface.wireless &&
            iface.name !in upstreams && iface.name != defaultInterface &&
            iface.name !in excludedInterfaces && !mobileDataInterfaceName(iface.name) &&
            iface.addresses.any(::publishesHotspotIpv4)
    }.map { it.name }
    if (eligible.isEmpty()) return emptySet()
    if (preferredInterface in eligible) return setOf(preferredInterface)
    return eligible.toSet()
}

internal fun legacyHotspotInterfaces(tethered: List<String>, wifiRegexes: List<String>): Set<String>? {
    if (wifiRegexes.isEmpty()) return null
    val patterns = wifiRegexes.map(::Regex)
    val matched = tethered.filter { name -> patterns.any { it.matches(name) } }.toSet()
    // 厂商可能动态使用 wlan0，而静态配置仍写 softap0；匹配失败不能证明没有热点。
    return matched.takeUnless { tethered.isNotEmpty() && it.isEmpty() }
}

/**
 * The modem's mobile-data interfaces: Qualcomm's rmnet… (rmnet_data0 to rmnet_data8, rmnet_ipa0 and r_rmnet_data… on
 * BYD units, #921) and MediaTek's ccmni…. Several of them hold an address while the car is online, and none carries
 * the hotspot an iPhone joins.
 */
internal fun mobileDataInterfaceName(name: String): Boolean =
    name.startsWith("rmnet") || name.startsWith("r_rmnet") || name.startsWith("ccmni")

internal fun wirelessInterfaceName(name: String): Boolean =
    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") ||
        name.contains("softap", ignoreCase = true) || name.startsWith("wifi") || name.startsWith("p2p")
