package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Device-only probe, packaged separately from the app. Runs as the target app, without shell UID adoption. */
class BluetoothMediaProbeInstrumentation : Instrumentation() {
    private var options = Bundle()
    private val report: (String) -> Unit = {
        Log.i("DiPlay-BT-Probe", it)
        sendStatus(0, Bundle().apply { putString("stream", "$it\n") })
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = runCatching {
            val app = targetContext
            report("app=${app.packageName} uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}")
            report("BLUETOOTH_PRIVILEGED=" +
                (app.checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED) == PackageManager.PERMISSION_GRANTED))
            val adapter = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
            report("adapterEnabled=${adapter.isEnabled}")
            check(adapter.isEnabled) { "Enable Bluetooth before probing; the probe never changes radio power" }
            val devices = adapter.bondedDevices
            report("pairedDevices=${devices.size}")
            val address = options.getString("peerAddress")
            val peer = address?.let { requested ->
                requireNotNull(devices.singleOrNull { it.address.equals(requested, ignoreCase = true) }) {
                    "peerAddress must identify an already paired device"
                }
            }
            // Reuse exactly the profile acquisition path shipped in this APK.
            val acquire = CarBluetoothProfileGuard::class.java.getDeclaredMethod("proxyFor", Context::class.java,
                BluetoothAdapter::class.java, Int::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java)
                .apply { isAccessible = true }
            for ((id, role) in listOf(11 to "A2DP_SINK", 2 to "A2DP_SOURCE")) {
                val proxy = acquire.invoke(CarBluetoothProfileGuard, app, adapter, id, report) as? BluetoothProfile
                report("$role profile=$id available=${proxy != null}")
                if (proxy == null) continue
                try {
                    report("$role connectedDevices=${proxy.connectedDevices.size}")
                    val modern = runCatching {
                        proxy.javaClass.getMethod("getConnectionPolicy", BluetoothDevice::class.java)
                    }.getOrNull()
                    val getter = modern ?: runCatching {
                        proxy.javaClass.getMethod("getPriority", BluetoothDevice::class.java)
                    }.getOrNull()
                    val setter = runCatching {
                        proxy.javaClass.getMethod(if (modern != null) "setConnectionPolicy" else "setPriority",
                            BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    }.getOrNull()
                    report("$role getter=${getter?.name ?: "unavailable"} setter=${setter?.name ?: "unavailable"}")
                    if (peer != null && getter != null) {
                        runCatching { getter.invoke(proxy, peer) }
                            .onSuccess { report("$role selectedPeerPolicy=$it state=${proxy.getConnectionState(peer)}") }
                            .onFailure {
                                val cause = it.cause ?: it
                                report("$role read failed ${cause.javaClass.simpleName}: ${cause.message}")
                            }
                    }
                } finally {
                    adapter.closeProfileProxy(id, proxy)
                }
            }
            if (options.getString("action") == "cycle") {
                requireNotNull(peer) { "A cycle requires an explicit paired peerAddress" }
                val prefs = app.getSharedPreferences("diplay_bt_profile_guard", Context.MODE_PRIVATE)
                check(!prefs.getBoolean("applied", false)) { "Existing recovery pending; do not overwrite it" }
                val mediaEnabled = CarBluetoothProfileGuard.mediaOnlyEnabled(app)
                val adapterEnabled = CarBluetoothProfileGuard.adapterEnabled(app)
                val hadMedia = prefs.contains("yield_media_only")
                val hadAdapter = prefs.contains("yield_adapter")
                val worker = CarBluetoothProfileGuard::class.java.getDeclaredField("worker")
                    .apply { isAccessible = true }.get(CarBluetoothProfileGuard) as ScheduledExecutorService
                try {
                    CarBluetoothProfileGuard.saveMediaOnlyEnabled(app, true)
                    CarBluetoothProfileGuard.yieldForSession(app, peer.address, log = report)
                    worker.submit {}.get(10, TimeUnit.SECONDS)
                    report("cycle mediaApplied=${prefs.getBoolean("applied", false)}")
                } finally {
                    CarBluetoothProfileGuard.restore(app, report)
                    worker.submit {}.get(10, TimeUnit.SECONDS)
                    val edit = prefs.edit()
                    if (hadMedia) edit.putBoolean("yield_media_only", mediaEnabled) else edit.remove("yield_media_only")
                    if (hadAdapter) edit.putBoolean("yield_adapter", adapterEnabled) else edit.remove("yield_adapter")
                    edit.commit()
                    report("cycle recoveryPending=${prefs.getBoolean("applied", false)} adapterEnabled=${adapter.isEnabled}")
                }
            }
        }
        val failure = result.exceptionOrNull()
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (failure == null) "\nProbe finished; inspect capability results above.\n"
                else "\nProbe failed: ${(failure.cause ?: failure).javaClass.simpleName}: ${failure.message}\n")
        })
    }
}
