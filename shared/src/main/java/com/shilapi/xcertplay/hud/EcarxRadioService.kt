package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/** Narrow client for the dumped 753 IRadioSvc ABI; binding never opens or takes audio focus. */
internal class EcarxRadioService(context: Context, private val log: (String) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    @Volatile private var service: IBinder? = null
    private var bound = false
    @Volatile private var closed = false

    data class State(val band: String, val frequency: Int?, val playing: Boolean?)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!closed) runCatching {
                check(binder?.interfaceDescriptor == DESCRIPTOR) { "unexpected radio service ABI" }
                service = binder
            }.onFailure { log("Vehicle state radio connect failed: $it") }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    fun start() {
        bound = runCatching { context.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { log("Vehicle state radio bind failed: $it") }.getOrDefault(false)
    }

    // Integer/boolean methods share the same Parcel representation in this AIDL version.
    private fun call(code: Int, argument: Int? = null, open: Boolean = false): Int {
        val binder = checkNotNull(service) { "radio service disconnected" }
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            if (open) request.writeStrongBinder(null) else argument?.let(request::writeInt)
            check(binder.transact(code, request, response, 0)) { "radio transaction $code refused" }
            response.readException()
            response.readInt()
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    fun read(): State? = if (closed || service == null) null else runCatching {
        val band = when (call(13)) { 0 -> "AM"; 1 -> "FM"; else -> return@runCatching null }
        val frequency = call(14).takeIf { validFrequency(band, it) }
        val playing = when (call(22)) { 0x1001 -> true; 0x1000, 0x1002, 0x6000, 0x7000 -> false; else -> null }
        State(band, frequency, playing)
    }.onFailure { log("Vehicle state radio read failed: $it") }.getOrNull()

    fun control(id: Long, params: Map<*, *>): Boolean {
        if (closed || service == null || id !in 4360L..4366L) return false
        if (params.containsKey("sourceIdentifier") && params["sourceIdentifier"] !is String) return false
        val band = (params["sourceIdentifier"] as? String) ?: read()?.band ?: return false
        if (band !in listOf("AM", "FM")) return false
        val frequency = when (id) {
            4364L -> (params["identifier"] as? String)?.toIntOrNull()
            4365L -> (params["frequency"] as? Number)?.toDouble()?.takeIf {
                it.isFinite() && it in 531.0..108000.0 && it == kotlin.math.floor(it)
            }?.toInt()
            else -> null
        }
        if (id in 4364L..4365L && (frequency == null || !validFrequency(band, frequency))) return false
        val selected = if (id == 4366L) (params["identifier"] as? String)?.takeIf { it in listOf("AM", "FM") } ?: return false else band
        // shortcut: cross-band tuning waits for an explicit source selection, extend after car validation.
        if (id in 4364L..4365L && read()?.band != band) return false
        return runCatching {
            // Only an explicit play/tune request starts the tuner. No persistent callback is
            // registered: this firmware unregisters one only by closing the physical radio.
            if (id != 4361L && call(2, open = true) != 1) return@runCatching false
            when (id) {
                4360L -> call(19) == 1
                4361L -> call(20) == 1
                4362L -> call(6, 0) == 1
                4363L -> call(6, 1) == 1
                4364L, 4365L -> call(5, frequency) == 1
                else -> call(4, if (selected == "AM") 0 else 1) == 1
            }
        }.onSuccess { log("Vehicle state radio control iid=$id accepted=$it") }
            .onFailure { log("Vehicle state radio control iid=$id failed: $it") }.getOrDefault(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        service = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }

    companion object {
        const val DESCRIPTOR = "com.geely.radio.service.IRadioSvc"
        val COMPONENT = ComponentName("com.geely.radio.service", "com.geely.radio.service.RadioService")
        fun validFrequency(band: String, frequency: Int): Boolean = when (band) {
            "FM" -> frequency in 87500..108000 && frequency % 100 == 0
            "AM" -> frequency in 531..1629 && frequency % 9 == 0
            else -> false
        }
    }
}
