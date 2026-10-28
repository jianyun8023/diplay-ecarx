package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.ConnectionDiagnostics.Event
import com.shilapi.xcertplay.ConnectionDiagnostics.Source

/** Optional wake-up path for the iPhone the user selected in connection setup. */
class PhoneBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!DiPlayPreferences.connectOnPhoneBluetooth(context)) { trace(context, Event.OPT_OUT); return }
        if (CarPlayBackgroundSession.hasSession()) { trace(context, Event.OWNER_PRESENT); return }
        val selected = DiPlayPreferences.phoneAddress(context)
            ?: run { trace(context, Event.NO_SELECTION); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            trace(context, Event.PERMISSION_DENIED); return
        }
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: run { trace(context, Event.NO_DEVICE); return }
        val address = runCatching { device.address }.getOrNull()
            ?: run { trace(context, Event.ADDRESS_UNAVAILABLE); return }
        if (!address.equals(selected, ignoreCase = true)) { trace(context, Event.OTHER_DEVICE); return }

        // The car can send several ACL events during one wake-up. Commit before launching so
        // another receiver instance cannot open a second connection from the same event burst.
        val prefs = context.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong("last_launch_elapsed", 0)
        if (prefs.contains("last_launch_elapsed") && now >= previous && now - previous < 15_000) {
            trace(context, Event.THROTTLED); return
        }
        prefs.edit().putLong("last_launch_elapsed", now).commit()
        PhoneWakeDiagnostics.record(context, "matched")
        trace(context, Event.LAUNCH_REQUESTED)
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_AUTO_CONNECT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onSuccess {
                PhoneWakeDiagnostics.record(context, "startActivity-returned")
                trace(context, Event.LAUNCH_RETURNED)
            }
            .onFailure { error ->
                PhoneWakeDiagnostics.record(context, "failed", error)
                trace(context, Event.LAUNCH_FAILED)
                Log.w("DiPlayPhoneWake", "Bluetooth wake launch failed: ${error.javaClass.simpleName}")
            }
    }

    private fun trace(context: Context, event: Event) =
        ConnectionDiagnostics.record(context, Source.ACL_BROADCAST, event)

    companion object {
        const val EXTRA_AUTO_CONNECT = "com.shihab.diplay.AUTO_CONNECT_SELECTED_PHONE"
    }
}

/** Only a timestamp and fixed outcome; the Bluetooth address and broadcast extras stay out of reports. */
internal object PhoneWakeDiagnostics {
    private const val PREFS = "diplay_phone_wake_diagnostics"

    fun record(context: Context, result: String, error: Throwable? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis())
            .putString("result", result)
            .putString("failureClass", error?.javaClass?.simpleName ?: "none")
            .apply()
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0)
        return if (at == 0L) "Phone Bluetooth wake: no matching event recorded"
        else "Phone Bluetooth wake: matchedAtEpochMs=$at result=${prefs.getString("result", "unknown")} " +
            "failureClass=${prefs.getString("failureClass", "none")}; " +
            "startActivity-returned does not prove the car displayed DiPlay."
    }
}
