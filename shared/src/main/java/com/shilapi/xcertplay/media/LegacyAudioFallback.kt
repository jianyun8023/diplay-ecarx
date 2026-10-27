package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log

/** A ROM can reject a legacy stream by throwing or returning an uninitialized track. */
internal object LegacyAudioFallback {
    /** Standard legacy routes only; vendor-only stream IDs keep usage-based focus attributes. */
    fun attributes(streamType: Int): AudioAttributes? {
        if (streamType !in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) return null
        return try {
            AudioAttributes.Builder().setLegacyStreamType(streamType).build()
        } catch (error: Exception) {
            Log.w("xcertplay-usb", "legacy audio stream $streamType rejected; keeping usage routing", error)
            null
        }
    }

    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T {
        val candidate = try { createLegacy() } catch (_: RuntimeException) { null }
        if (candidate != null) {
            if (isInitialized(candidate)) return candidate
            release(candidate)
        }
        return createFallback()
    }
}
