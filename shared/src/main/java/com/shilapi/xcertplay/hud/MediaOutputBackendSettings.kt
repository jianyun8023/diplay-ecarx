package com.shilapi.xcertplay.hud

import android.content.Context

/** Only media output is selected here; navigation is independent. A change applies next session. */
object MediaOutputBackendSettings {
    enum class Backend { UNI_MEDIACENTER, DIM }
    @Volatile private var mediaCenterBarrier = false
    fun blockDim() { mediaCenterBarrier = true }
    fun releaseDimBarrier() { mediaCenterBarrier = false }
    fun dimBlocked(): Boolean = mediaCenterBarrier
    private var owner: Any? = null
    private var sessionBackend: Backend? = null
    fun load(context: Context): Backend = runCatching {
        Backend.valueOf(context.getSharedPreferences("diplay_media_output", Context.MODE_PRIVATE)
            .getString("backend", Backend.UNI_MEDIACENTER.name)!!)
    }.getOrDefault(Backend.UNI_MEDIACENTER)
    fun save(context: Context, backend: Backend) {
        check(context.getSharedPreferences("diplay_media_output", Context.MODE_PRIVATE)
            .edit().putString("backend", backend.name).commit())
    }
    @Synchronized fun begin(owner: Any, backend: Backend) { this.owner = owner; sessionBackend = backend }
    @Synchronized fun end(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null; sessionBackend = null
    }
    @Synchronized fun dimAllowed(context: Context): Boolean =
        !mediaCenterBarrier && (sessionBackend ?: load(context)) == Backend.DIM
}
