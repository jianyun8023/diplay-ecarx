package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.util.Log
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.orchestration.CarPlayStartupTimeline

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> onLockedBoot(context)
            Intent.ACTION_BOOT_COMPLETED -> onBootCompleted(context)
            else -> return
        }
    }

    /**
     * Runs before credential-encrypted storage is available. Only the device-protected copy of the
     * auto-start flag is safe to read. Creating this process early is the win: the later
     * BOOT_COMPLETED launch no longer pays the fork, class-load and provider-install cost, and the
     * receiver is directBootAware so the platform can deliver it without waiting for user unlock.
     */
    private fun onLockedBoot(context: Context) {
        val launchEnabled = BootStartupPreferences.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.lockedBootReceived(context, launchEnabled)
        if (!launchEnabled) return
        Log.i(TAG, "LOCKED_BOOT_COMPLETED received; process warmed for boot auto-start")
        // Without a lock-screen credential the user unlocks right away, about 10 s before
        // BOOT_COMPLETED reaches this app. Credential storage, and with it the settings, is then
        // readable: open CarPlay at once. BOOT_COMPLETED finds it open and launches nothing else.
        val app = context.applicationContext
        if (userUnlocked(app)) {
            openAtUnlock(app)
            return
        }
        // USER_UNLOCKED goes to registered receivers only; this warmed process waits for it. If the
        // process does not live that long, BOOT_COMPLETED still opens CarPlay as before.
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, unlocked: Intent) {
                    runCatching { app.unregisterReceiver(this) }
                    openAtUnlock(app)
                }
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }.onFailure { Log.w(TAG, "Could not wait for user unlock", it) }
    }

    private fun openAtUnlock(context: Context) {
        // ECARX starts its own services once its home screen has shown; CarPlay must not cover it first.
        if (com.shilapi.xcertplay.hud.EcarxCapabilities.detect(context).identified) {
            EcarxLauncherStartup.afterFirstBatch(context) { waitedMs, seen ->
                Log.i(TAG, "Launcher first batch seen=$seen waitedMs=$waitedMs; opening CarPlay")
                CarPlayStartupTimeline.record(context,
                    if (seen) CarPlayStartupTimeline.Stage.LAUNCHER_FIRST_BATCH else CarPlayStartupTimeline.Stage.LAUNCHER_FIRST_BATCH_MISSED,
                    AirPlayPersistence.loadWirelessHotspotMode(context))
                openCarPlayAtUnlock(context)
            }
        } else openCarPlayAtUnlock(context)
    }

    private fun openCarPlayAtUnlock(context: Context) {
        // The direct path only: anything that needs the home screen waits for BOOT_COMPLETED.
        runCatching {
            if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return@runCatching
            // As BOOT_COMPLETED does: release a profile policy an unclean exit left behind before CarPlay
            // uses Bluetooth. It skips itself once a session holds the guard.
            CarBluetoothProfileGuard.recoverIfStale(context)
            if (BootCarPlayLaunch.open(context, early = true)) {
                Log.i(TAG, "CarPlay opened at user unlock")
            }
        }.onFailure { Log.w(TAG, "CarPlay at user unlock failed; BOOT_COMPLETED will retry", it) }
    }

    private fun userUnlocked(context: Context): Boolean = runCatching {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    }.getOrDefault(false)

    private fun onBootCompleted(context: Context) {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(context)
        CarPlayStartupTimeline.record(context, CarPlayStartupTimeline.Stage.BOOT_BROADCAST, mode)
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        // Refresh the device-protected mirror for the next power cycle, including upgrades from
        // versions that only had the credential-protected preference.
        runCatching { BootStartupPreferences.saveAutoStartOnBoot(context, launchEnabled) }
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        // The profile policy outlives a killed process, so a power cycle is the one moment that is
        // guaranteed to follow an unclean exit. Release it before anything else can look at it.
        CarBluetoothProfileGuard.recoverIfStale(context)
        if (!launchEnabled) return
        // A fully configured wireless auto-connect opens CarPlay directly and starts the car hotspot
        // in parallel; everything else goes through the home screen as before.
        if (BootCarPlayLaunch.open(context)) return

        // Shaped like the launcher icon's intent: a USB authenticator or iPhone plugged in can open CarPlay
        // before this broadcast, and DiPlayActivity then returns to that session instead of showing Home over it.
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
