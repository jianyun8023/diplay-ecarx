package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager

/** Read-only OEM sound-stage evidence; device IDs and seat optimization are not per-track seat routes. */
internal object AudioDiagnosticSnapshot {
    fun report(
        context: Context,
        readParameter: (AudioManager, String) -> String? = { manager, key ->
            AudioManager::class.java.getMethod("getParameters", String::class.java)
                .invoke(manager, key) as? String
        },
    ): String = buildString {
        appendLine("--- Audio output snapshot (read-only; device IDs do not identify seats) ---")
        appendLine("Saved audio settings (may differ from active session): " +
            "advancedMapping=${AirPlayPersistence.loadAdvancedAudioChannelMapping(context)} " +
            "mediaChannel=${AirPlayPersistence.loadMediaAudioChannel(context)} " +
            "navigationChannel=${AirPlayPersistence.loadNavigationAudioChannel(context)} " +
            "navigationOemContent=${AirPlayPersistence.loadNavigationOemContent(context)} " +
            "focus=${AirPlayPersistence.loadAudioFocusEnabled(context)} " +
            "navigationPromptFocus=${AirPlayPersistence.loadNavigationPromptAudioFocus(context)}")
        val manager = context.getSystemService(AudioManager::class.java)
        if (manager == null) {
            appendLine("Audio manager: unavailable")
            return@buildString
        }
        appendLine(runCatching {
            val mode = manager.mode
            val name = when (mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "UNKNOWN"
            }
            "Audio mode: value=$mode name=$name scope=global"
        }.getOrElse { "Audio mode: unavailable=${it.javaClass.simpleName}" })
        appendLine("OEM audio effects (global configured state; DSP processing not verified):")
        for ((effect, key) in listOf("Logic7" to "audask_logic7", "Clari-Fi" to "audask_clarifi")) {
            appendLine(runCatching {
                val raw = readParameter(manager, key)
                val state = when (raw) {
                    "$key=01" -> "on"
                    "$key=00" -> "off"
                    else -> "unknown"
                }
                val safe = raw?.takeIf { it.isNotEmpty() }?.let(DiagnosticRedactor::redact)?.take(160) ?: "unavailable"
                "OEM audio effect: effect=$effect query=$key raw=$safe state=$state scope=global"
            }.getOrElse { "OEM audio effect: effect=$effect query=$key state=unknown unavailable=${it.javaClass.simpleName}" })
        }
        appendLine(runCatching {
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            "Available audio outputs: count=${devices.size} " + devices.joinToString { device ->
                DiagnosticRedactor.redact("id=${device.id} type=${device.type} address=${device.address}")
                    ?: "redacted"
            }
        }.getOrElse { "Available audio outputs: unavailable=${it.javaClass.simpleName}" })
        appendLine(runCatching {
            val raw = readParameter(manager, "audask_seatopt")
            val value = raw?.substringAfter('=', "")?.takeIf { it.length == 2 }?.toIntOrNull(16)
            val mode = when (value) {
                0 -> "off"
                1 -> "front"
                2 -> "rear"
                3 -> "all"
                else -> "unknown"
            }
            val safe = raw?.let(DiagnosticRedactor::redact)?.take(160) ?: "unavailable"
            "OEM sound stage: query=audask_seatopt raw=$safe mode=$mode scope=global"
        }.getOrElse { "OEM sound stage: query=audask_seatopt unavailable=${it.javaClass.simpleName}" })
        for (key in listOf("audask_balance", "audask_fader")) {
            appendLine(runCatching {
                val raw = readParameter(manager, key)
                "OEM sound stage: query=$key raw=${raw?.let(DiagnosticRedactor::redact)?.take(160) ?: "unavailable"} scope=global"
            }.getOrElse { "OEM sound stage: query=$key unavailable=${it.javaClass.simpleName}" })
        }
        append(runCatching {
            val type = Class.forName("com.ecarx.xui.adaptapi.audio.audiofx.Audio", false, context.classLoader)
            val audio = type.getMethod("create", Context::class.java).invoke(null, context)
            val state = type.getMethod("getAudioState").invoke(audio)
            val seat = Class.forName("com.ecarx.xui.adaptapi.audio.audiofx.IAudioState", false, context.classLoader)
                .getMethod("getSoundStageOptimizedSeat").invoke(state)
            "OEM sound-stage API: available=true reportedSeat=$seat (SDK may default to 119; compare raw response)"
        }.getOrElse { "OEM sound-stage API: unavailable=${it.javaClass.simpleName}" })
    }
}
