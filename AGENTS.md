# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Vehicle scope

Production targets Android 9 / API 28, ECARX 753 / Qualcomm 820A, with the vehicle platform signature. Ship ARM64 and ARMv7 only. Other Android releases are debugging environments. App runtime, permission setup and recovery must not depend on ADB. Use [the permission checklist](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md) and issue #21 as the implementation and validation reference.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug :siribridge:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :siribridge:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Source ownership

- `CarPlayHostActivity` owns the foreground session and window lifecycle. `CarPlaySidePanel` owns its panel views, gestures, animation callbacks and cleanup; the host supplies the current session geometry and actions.
- `DiPlayActivity` owns page navigation and settings composition. `DiPlayControls` holds its custom text controls, and `DiPlayUpdates` owns update checks, downloads, verification and installation for that Activity lifetime.
- `CarPlayController` owns session generations and resource teardown. `WirelessBluetoothConnection` selects and validates Bluetooth targets and connects RFCOMM using the caller's cancellation predicate. `CarPlayStatus` holds the status contract.
- `AndroidMediaSink` owns stream routing and renderer lifetimes. `VideoDecoder`, `AudioRenderer` and `AudioFocusCoordinator` own decoding, playback and focus respectively. Keep codec worker ownership and release ordering with those implementations.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` in
`common/src/main/java/com/shilapi/xcertplay/SettingsInformationArchitecture.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, head-unit HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot depends on vehicle permissions and appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(sperimentale)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`, `（実験的機能）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test menus and panels on the current head unit at 1920×1080 pixels.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include a screenshot at the current head unit size: 1920×1080 pixels,
   landscape, normal font scale. Do not add other screen sizes, portrait, large-font or RTL
   screenshot variants. Keep protocol and instrument-display dimension tests for their own formats.
