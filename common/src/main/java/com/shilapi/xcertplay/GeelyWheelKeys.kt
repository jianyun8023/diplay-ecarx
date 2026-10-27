package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.network.GeelyHeadUnit

/**
 * Geely/Lynk steering-wheel media keys.
 *
 * The head unit maps the wheel's keys to vendor key codes and routes them only through its own input
 * stack - no standard Android KeyEvent is produced - so a media session or accessibility service
 * never sees them. Its input service (`com.geely.inputservice.InputService`) is exported and its
 * `registerListener` has no permission check, so we subscribe to the raw codes there (exactly how
 * the car's own `KeyInputController` does) and forward them to the active CarPlay session.
 *
 * With [AirPlayPersistence.loadCarBluetoothAudio] on, the car's own Bluetooth (AVRCP) control drives
 * the iPhone's music, so forwarding the media keys too would double-fire; only the microphone key is
 * registered then, for the long-press Siri shortcut.
 *
 * The microphone key's long press is answered by the car's HiCar app, which registers for the same
 * code and cannot be intercepted from here; disable HiCar over ADB if Siri should answer instead
 * (see docs/COMPATIBILITY.md).
 *
 * Silently does nothing off this head unit ([GeelyHeadUnit.detected]).
 */
internal object GeelyWheelKeys {
    private const val TAG = "DiPlay-GeelyWheel"
    private const val INPUT_PACKAGE = "com.geely.inputservice"
    private const val INPUT_CLASS = "com.geely.inputservice.InputService"

    private const val RETRY_MILLIS = 3_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var bound = false
    private var manager: IInputManager? = null
    @Volatile private var listener: IInputListener? = null
    @Volatile private var connection: ServiceConnection? = null

    /** Binds while a CarPlay session is active; safe to call repeatedly. */
    @Synchronized
    fun start(context: Context) {
        if (!GeelyHeadUnit.detected) return
        appContext = context.applicationContext
        if (bound) return
        bindLocked()
    }

    /** Unbinds when the CarPlay session ends. */
    fun stop() {
        // Match attach/detach's lock order and finish any accepted dispatch before stopping.
        synchronized(CarPlayMediaKeys) {
            val context: Context?
            val oldConnection: ServiceConnection?
            val oldManager: IInputManager?
            val oldListener: IInputListener?
            val wasBound: Boolean
            synchronized(this) {
                context = appContext
                oldConnection = connection
                oldManager = manager
                oldListener = listener
                wasBound = bound
                appContext = null
                connection = null
                listener = null
                manager = null
                handler.removeCallbacksAndMessages(null)
                bound = false
            }
            if (context != null && oldListener != null) {
                runCatching { oldManager?.unregisterListener(oldListener, context.packageName) }
            }
            if (wasBound && context != null && oldConnection != null) runCatching { context.unbindService(oldConnection) }
        }
    }

    private fun bindLocked() {
        val context = appContext ?: return
        val intent = Intent().setComponent(ComponentName(INPUT_PACKAGE, INPUT_CLASS))
        val nextConnection = newConnection()
        connection = nextConnection
        bound = runCatching { context.bindService(intent, nextConnection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) connection = null
        Log.i(TAG, "bind geely input service requested=$bound")
    }

    private fun newConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val input = IInputManager.Stub.asInterface(service) ?: return
            val owner = this
            val registered = object : IInputListener.Stub() {
                override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) {}
                override fun onShortClick(keyCode: Int, softKeyFunction: Int) {
                    handler.post {
                        synchronized(CarPlayMediaKeys) {
                            if (connection === owner && listener === this) forward(keyCode)
                        }
                    }
                }
                override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) {}
                override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) {}
                override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) {
                    handler.post {
                        synchronized(CarPlayMediaKeys) {
                            if (connection !== owner || listener !== this) return@post
                            Log.i(TAG, "long press key=$keyCode func=$softKeyFunction")
                            // The microphone key's long press: Siri, once the car's HiCar app is disabled.
                            if (keyCode == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) {
                                Log.i(TAG, "Siri requested=${CarPlayMediaKeys.onExternalSiriKey()}")
                            }
                        }
                    }
                }
                override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) {}
            }
            val context = synchronized(this@GeelyWheelKeys) {
                if (connection !== owner) return
                val context = appContext ?: return
                manager = input
                listener = registered
                context
            }
            runCatching {
                // With CarPlay audio on the car's Bluetooth, the head unit's own AVRCP already drives
                // the iPhone's music, so forwarding play/pause and skip over iAP2 as well would
                // double-fire. Keep only the microphone key (long press -> Siri) then.
                val bluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(context)
                val codes = GeelyWheelKeyCodes.registeredCodes(bluetoothAudio)
                if (bluetoothAudio) {
                    Log.i(TAG, "car Bluetooth audio on: media keys pass to the head unit, only the Siri key is wired")
                }
                input.registerListener(registered, context.packageName, codes)
                codes
            }
                .onSuccess {
                    val active = synchronized(this@GeelyWheelKeys) {
                        connection === owner && listener === registered
                    }
                    if (active) {
                        Log.i(TAG, "registered for wheel key codes=${it.joinToString()}")
                    } else {
                        runCatching { input.unregisterListener(registered, context.packageName) }
                    }
                }
                .onFailure { Log.w(TAG, "registerListener failed", it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@GeelyWheelKeys) {
                if (connection !== this || appContext == null) return
                manager = null
                listener = null
                val owner = this
                // The car service is persistent; rebind shortly after an unexpected drop.
                handler.postDelayed({
                    synchronized(this@GeelyWheelKeys) {
                        val context = appContext
                        if (connection === owner && context != null && manager == null) {
                            connection = null
                            if (bound) runCatching { context.unbindService(owner) }
                            bound = false
                            bindLocked()
                        }
                    }
                }, RETRY_MILLIS)
            }
        }
    }

    private fun forward(keyCode: Int) {
        val index = when (keyCode) {
            GeelyWheelKeyCodes.KEY_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
            GeelyWheelKeyCodes.KEY_NEXT -> CarPlayMediaButton.NEXT
            GeelyWheelKeyCodes.KEY_PREVIOUS -> CarPlayMediaButton.PREVIOUS
            else -> return
        }
        CarPlayMediaKeys.onExternalMediaKey(index)
    }
}
