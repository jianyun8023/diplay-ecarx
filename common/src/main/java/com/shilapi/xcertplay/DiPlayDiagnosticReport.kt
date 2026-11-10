// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard
import com.shilapi.xcertplay.hud.EcarxHicarControl
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.CarWifiClientGuard
import com.shilapi.xcertplay.orchestration.CarPlayStartupTimeline
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File

/**
 * The text of the diagnostic report that Settings → Diagnostics saves and the backup page serves.
 * Built on a background thread from the application context; [setupReady] is whether the Activity
 * found its CarPlay authentication, the one piece of Activity state the report mentions.
 */
internal fun diagnosticReportText(appContext: Context, setupReady: Boolean): String = buildString {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Build diagnostic reports off the main thread" }
    appendLine("DiPlay ${appContext.appVersionName()} · private beta diagnostic report")
    appendLine(LastConnectionProblem.report(appContext))
    appendLine("Report createdAtEpochMs=${System.currentTimeMillis()}")
    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
    appendLine("App: package=${appContext.packageName} " +
        "debuggable=${appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0}")
    appendLine(PermissionDiagnosticSnapshot.read(appContext).report())
    appendLine(runCatching {
        val usb = appContext.getSystemService(android.hardware.usb.UsbManager::class.java)
        if (usb == null) "USB devices: unavailable" else buildString {
            val devices = usb.deviceList.values
            appendLine("USB devices: attached=${devices.size}")
            devices.forEach { device ->
                appendLine("USB device: vendorId=${device.vendorId} productId=${device.productId} " +
                    "interfaces=${device.interfaceCount} permission=${usb.hasPermission(device)}")
            }
        }
    }.getOrElse { "USB devices: unavailable=${it.javaClass.simpleName}" })
    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
    AirPlayPersistence.loadUsbChargingCurrent(appContext).let {
        appendLine("USB charging offer: ${it.key} (${it.milliAmps} mA)")
    }
    appendLine("Authentication: local experimental beta identity; no remote fallback")
    appendLine("CarPlay setup: ${if (setupReady) "ready" else "authentication unavailable"}")
    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
    appendLine("Media-key audio focus: ${if (AirPlayPersistence.loadDisableMediaKeyAudioFocus(appContext)) "disabled" else "enabled"}")
    appendLine("MTK decoder tuning preference: ${if (AirPlayPersistence.loadMtkDecoderTuning(appContext)) "enabled" else "disabled"}")
    appendLine("CarPlay size: ${AirPlayPersistence.loadWidthPhysicalMm(appContext).let { mm -> "${com.shilapi.xcertplay.airplay.CarPlaySize.exact(mm)?.label ?: "Custom"}, $mm mm" }}")
    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
    appendLine(runCatching { CarWifiClientGuard.diagnostic(appContext) }.getOrElse { "Car Wi-Fi client: unavailable=${it.javaClass.simpleName}" })
    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
    appendLine("CarPlay screen: ${CarPlayScreen.report(appContext)}")
    appendLine(PhoneWakeDiagnostics.report(appContext))
    appendLine(ConnectionDiagnostics.report(appContext))
    appendLine("Media integration savedMode=${MediaSessionSettings.mode(appContext)} " +
        "hyArtworkBroadcast=${MediaSessionSettings.hyArtworkBroadcast(appContext)}")
    appendLine("Wheel Siri enabled=${MediaSessionSettings.wheelSiriEnabled(appContext)} " +
        "voicePress=${MediaSessionSettings.voiceKeyPress(appContext)} hyKey=${MediaSessionSettings.hySiriKey(appContext)}")
    appendLine(CarPlayMediaSession.mediaCenterDiagnosticReport())
    appendLine(CarPlayMediaSession.diagnosticReport())
    appendLine(SystemMediaSessionDiagnostics.report(appContext))
    appendLine("Cluster output: " + CarPlayBackgroundSession.clusterDiagnostics(CarPlayBackgroundSession.snapshot()?.controller))
    if (com.shilapi.xcertplay.hud.EcarxCapabilities.detect(appContext).identified) {
        appendLine(NavigationOutputs.diagnosticReport(appContext))
    }
    appendLine(CarPlayMediaSession.artworkDiagnosticReport())
    appendLine(AudioDiagnosticSnapshot.report(appContext))
    appendLine(runCatching {
        EcarxHicarControl.state(appContext)?.let {
            "Factory HiCar: visible=true disabled=${it.disabled} canChange=${it.canChange}"
        } ?: "Factory HiCar: visible=false (not installed or not visible to this app)"
    }.getOrElse { "Factory HiCar: unavailable=${it.javaClass.simpleName}" })
    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
    appendLine("Firmware: product=${Build.PRODUCT} device=${Build.DEVICE} fingerprint=${Build.FINGERPRINT}")
    appendLine()
    appendLine(BluetoothDiagnosticSnapshot.report(appContext))
    appendLine("Bluetooth settings: carAudio=${AirPlayPersistence.loadCarBluetoothAudio(appContext)} " +
        "mediaOnlyYield=${CarBluetoothProfileGuard.mediaOnlyEnabled(appContext)} " +
        "adapterYield=${CarBluetoothProfileGuard.adapterEnabled(appContext)}")
    appendLine(SettingsActionDiagnostics.report(appContext))
    appendLine()
    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
    appendLine(DisplayDiagnosticSnapshot.report(appContext))
    appendLine()
    appendLine("--- Last received boot and app-launch result ---")
    appendLine(StartupDiagnosticSnapshot.report(appContext))
    appendLine(CarPlayStartupTimeline.report(appContext))
    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
    appendLine("Car hotspot control: autoEnable=${CarHotspotSettings.enabled(appContext)} " +
        "hotspotPermission=${CarHotspotTethering.permitted(appContext)} " +
        "hotspotOn=${runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) }.getOrNull()}")
    com.shilapi.xcertplay.network.ManualHotspotControl(appContext).let {
        appendLine("Manual hotspot: capability=${it.capability()} state=${it.observe()}")
    }
    appendLine(WheelKeyService.report(appContext))
    WheelKeyLog.report(WheelKeyService.connected()).forEach { appendLine(it) }
    appendLine(com.shilapi.xcertplay.hud.EcarxSwKeyIntercept.diagnosticReport())
    appendLine()
    MediaPowerDiagnostics.report().forEach { appendLine(it) }
    appendLine()
    appendLine(UpdateAvailability.report(appContext, System.currentTimeMillis()))
    appendLine()
    appendLine("Platform permission build: Android 9 native grants")
    appendLine()
    appendLine("--- Recent own-app process exits (Android 11+) ---")
    appendLine(ProcessExitDiagnostics.report(appContext))
    appendLine()
    appendLine("--- Last crash recorded by DiPlay ---")
    appendLine(CrashRecord.report(appContext))
    appendLine()
    for (name in SessionLogFile.REPORT_NAMES) {
        val file = File(appContext.filesDir, "logs/$name")
        if (file.isFile) {
            appendLine("--- $name ---")
            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
        }
    }
}
