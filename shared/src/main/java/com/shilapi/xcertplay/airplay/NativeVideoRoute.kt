package com.shilapi.xcertplay.airplay

/** One receiver's destinations. Replacing or closing a stream disconnects its old route. */
class NativeVideoRoute internal constructor(private var handle: Long) {
    /** Target handles are retained pipe references, consumed by Rust in this call. */
    @Synchronized internal fun setTargets(retainTargets: () -> LongArray) {
        if (handle != 0L) NativeMedia.videoRouteSetTargets(handle, retainTargets())
    }

    @Synchronized internal fun disconnect() {
        if (handle != 0L) NativeMedia.videoRouteSetTargets(handle, longArrayOf())
    }

    @Synchronized internal fun start(fd: Int, key: ByteArray, callbacks: NativeStreamCallbacks): Long =
        if (handle == 0L) 0L else NativeMedia.startVideo(fd, key, callbacks, handle)

    @Synchronized internal fun close() {
        if (handle == 0L) return
        NativeMedia.videoRouteDestroy(handle)
        handle = 0L
    }

    internal companion object {
        fun create(): NativeVideoRoute? =
            if (NativeMedia.available && android.os.Build.VERSION.SDK_INT >= 28) {
                NativeMedia.videoRouteCreate().takeIf { it != 0L }?.let(::NativeVideoRoute)
            } else null
    }
}
