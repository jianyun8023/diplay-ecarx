package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset

/** Display-only bench experiment; no vehicle signals or theme artwork are bundled. */
class CarPlayUltraExperiment private constructor(
    val asset: Iap2ClusterAsset,
    val sdkVersion: String,
    val mainPanel: String,
    val clusterPanel: String,
    val layout: Layout?,
) {
    data class Layout(val id: String, val palette: String, val activeComponents: Map<String, String>)

    companion object {
        /** Asset:version:sdk and two panel IDs, optionally followed by layout, palette and zone:component lines. */
        fun parse(text: String): CarPlayUltraExperiment? {
            val lines = text.trim().lines().map(String::trim)
            if (lines.size != 3 && lines.size < 5) return null
            val theme = lines[0].split(':')
            if (theme.size != 3 || !theme[0].matches(Regex("[A-Za-z0-9_-]+"))) return null
            val version = theme[1].toLongOrNull()?.takeIf { it in 0..0xffff_ffffL } ?: return null
            if (!theme[2].matches(Regex("[0-9]+\\.[0-9]+"))) return null
            if (lines.subList(1, 3).any { it.isBlank() || it.any(Char::isISOControl) } || lines[1] == lines[2]) return null
            val layout = if (lines.size == 3) null else {
                if (lines.drop(3).any { it.isBlank() || it.any(Char::isISOControl) }) return null
                val components = linkedMapOf<String, String>()
                for (line in lines.drop(5)) {
                    val pair = line.split(':')
                    if (pair.size != 2 || pair.any(String::isBlank) || components.put(pair[0], pair[1]) != null) return null
                }
                Layout(lines[3], lines[4], components)
            }
            return CarPlayUltraExperiment(Iap2ClusterAsset(theme[0], version), theme[2], lines[1], lines[2], layout)
        }
    }
}
