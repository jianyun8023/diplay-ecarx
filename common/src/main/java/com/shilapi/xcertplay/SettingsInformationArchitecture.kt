// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, LANGUAGE, DIAGNOSTICS, ADVANCED, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    DISPLAY_AND_PERFORMANCE,
    EXPERIMENTAL_DISPLAY,
    ECARX_CLUSTER_DISPLAY,
    ADVANCED_MEDIA,
    MEDIA_OUTPUT,
    CAR_BUTTON,
    AUDIO_ROUTING,
    AUTHENTICATION,
    EXTERNAL_CONTROLLER,
    NAVIGATION_WHEEL,
    LOCATION,
    LAUNCHER_BUBBLE,
    PERMISSIONS_AND_HELP,
    LANGUAGE,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to emptySet(),
        SettingsCategory.LANGUAGE to setOf(SettingsSection.LANGUAGE),
        SettingsCategory.ABOUT to emptySet(),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.USB_CONNECTION,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.MEDIA_OUTPUT, SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.LOCATION),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.CAR_BUTTON,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.LAUNCHER_BUBBLE,
            SettingsSection.AUTHENTICATION,
            SettingsSection.EXTERNAL_CONTROLLER,
            SettingsSection.EXPERIMENTAL_DISPLAY,
            SettingsSection.ECARX_CLUSTER_DISPLAY,
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.NAVIGATION_WHEEL,
        ),
    )
}
