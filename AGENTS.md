# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Vehicle scope

Production targets Android 9 / API 28, ECARX 753 / Qualcomm 820A, with the vehicle platform signature. Ship ARM64 and ARMv7 only. Other Android releases are debugging environments. App runtime, permission setup and recovery must not depend on ADB. Use [the permission checklist](docs/ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md) and issue #21 as the implementation and validation reference. Vendor interfaces, firmware behavior and what has been verified on the car are recorded in [the platform notes](docs/ECARX_753_PLATFORM_NOTES.zh-CN.md); register a new interface, property id or broadcast there before using it.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug :siribridge:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :siribridge:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Source ownership

The module layout, current ownership and migration target are described in [the architecture notes](docs/ARCHITECTURE.zh-CN.md). The `feat/rust-carplay-core` branch implements [issue #96](https://github.com/jianyun8023/diplay-ecarx/issues/96): Rust will own protocols, session state, media queues and scheduling, recovery, codec/route decisions, navigation state, vehicle-data conversion and input protocols. Kotlin remains the Android host and platform adapter: UI, lifecycle, permissions, focus requests, Bluetooth/hotspot, USB/VPN, storage and vehicle SDK calls. Platform adapters report events/capabilities and execute core requests; they do not keep a second copy of migrated core state.

The ownership below describes the current implementation, not a completed migration. Move one coherent owner and its callers/tests at a time; preserve Android 9 calibration, routing and recovery behavior, and update these rules with each completed move. Reuse existing Rust media code. For protocol migration, first compare the relevant [LIVI Rust crates](https://github.com/f-io/LIVI/tree/main/native/crates) and test vectors against our wire behavior; record the source revision, preserve required attribution, and check API 28 and both shipped ABIs before importing code or dependencies. Do not import its complete application/runtime merely to reuse a protocol module.

During development, run affected-module tests. Run the complete Checks command for a deliverable batch; do not repeat global checks for documentation-only changes. Track outstanding device/car acceptance in issue #96.

Kotlin core fallbacks are temporary migration code. Retire production fallbacks module by module after acceptance, retain independent test references, and make a required native-core load failure explicit at startup. This retirement is still pending. Bouncy Castle remains required by unmigrated PairSetup/SRP, identity and MFi ASN.1; hardware capability fallbacks have separate platform requirements.

Rust sources live under `shared/src/main/rust/diplay-media/src/`: `protocols` (wire codecs/crypto/pairing), `session` (lifecycle/recovery/deadlines), `media/audio` and `media/video` (media owners), `vehicle` (HID/vehicle/navigation), `platform` (JVM/NDK/shared stream lifetime), and `transport/ncm` (NCM codec/engine). `lib.rs` only assembles these six namespaces. Keep an owner's private JNI module with that owner; do not add root compatibility re-exports, `#[path]` aliases, a catch-all utilities layer or split private state merely to relocate FFI. Preserve target-specific cfg, JNI/C ABI and existing public/private boundaries when moving code.

- `CarPlayHostActivity` owns the foreground session and window lifecycle. `CarPlaySidePanel` owns its panel views, gestures, animation callbacks and cleanup; the host supplies the current session geometry and actions. `CarPlaySafeAreaEditor` owns the safe-area switches, summary and editor overlay; the host supplies the activity size, palette and settings baseline. `CarPlayBackgroundSession` (its own file) hands the running session between hosts.
- `DiPlayActivity` owns page navigation and settings composition. `DiPlayControls` holds its custom text controls, and `DiPlayUpdates` owns update checks, downloads, verification and installation for that Activity lifetime. `DiPlayDiagnosticReport` builds the diagnostic report text from the application context.
- `CarPlayController` owns session generations, shared service lifetimes and wired teardown. Its private `WirelessRun` owns one wireless generation’s state, resources, callbacks and teardown; local Bluetooth retries stay within that run. `WirelessRunState` defines its lifecycle and handoff transitions. Wi-Fi scan suppression stays with the controller across runs. `WirelessBluetoothConnection` selects and validates Bluetooth targets and connects RFCOMM using the caller's cancellation predicate. `CarPlayStatus` holds the status contract. Its debug-log line for each status lives next to it (`debugLogMessage`), and `OrchestrationText` holds the text helpers shared across the package.
- `AndroidMediaSink` owns stream routing and renderer lifetimes. It updates native receiver destinations under the video ownership lock and disconnects old routes before retiring a stream. Rust receiver routes retain decoder references; video packets and mirror references stay in Rust until fed or dropped. `VideoDecoder`, `AudioRenderer` and `AudioFocusCoordinator` own decoding, playback and focus respectively. Keep codec worker ownership and release ordering with those implementations.
- Rust `media::audio::{buffered, buffered_state}` own buffered music TCP framing, decryption, queued and inaudible fed packets, RTP/NTP anchors, SETRATE/FLUSH, feed deadlines and bounded/paced intake. `media::audio::queue` owns renderer compressed packets, sustained RTP backlog recovery and arrival-weighted clock input; `media::audio::clock_trim` owns music PI control and learned drift. Kotlin gates platform output callbacks against pause/flush/close, commits a feed only if its core generation is still current, and destroys/wakes the buffered owner before stopping its receiver. Renderer queue handles are serialized against close; clock handles stay on the output worker. Kotlin core fields/algorithms are used only when the native library cannot load, never as a second active state copy. Keep AAC/AudioTrack/AAudio, focus, route and head-unit calibration behavior when changing this boundary.
- Rust `protocols::rtsp` owns control request framing and response encoding behind `RtspMessage`; its private `jni` constructs existing DTOs with bounded local references and UTF-16 strings. Keep the DTO constructor R8 rules, US_ASCII behavior, ProtocolException and receive limits. Rust `protocols::bplist` owns binary plist layout, types and decoding budgets behind `BplistCodec`; its private `jni` maps Java collections/scalars and delegates malformed UTF-16 replacement to the platform charset. Encoding has a shared native/fallback depth limit of 128; decoding retains its 32-level and work/payload limits. Rust `protocols::control_cipher` owns control/event framing, AEAD keys and independent wrapping counters. `ControlCipher` serializes operations and close; `AirPlaySession` closes both owners and rejects late installation. Close the event socket before waiting for its write lock. Keep the Decrypted DTO R8 constructor rule. Rust `iap2_body` owns CSM parameter framing and builder schema rules (type, unknown/repeated/required fields); `iap2_wire` owns scalars, list/terminator layout and typed field sizes. Kotlin retains immutable DTOs, descriptive schema metadata, its builder/reader API and platform UTF-8 conversion; schema errors carry input indexes so UTF-16 field names remain intact. `iap2_csm` owns bounded outer framing, resynchronization and link splitting; `iap2_link` owns synchronization, sequence/ACK/window state, retransmission, bounded queues and timers. These protocol facades require native; CSM/link serialize use with close and the link pump owns its engine with `use`. Old Kotlin algorithms are test-only.
- Rust `protocols::pair_verify` owns the complete M1/M2/M3/M4 handshake, ephemeral/shared/encryption keys, verification result and control keys. `PairVerify` serializes all handle use and close; `AirPlaySession` closes it after transport teardown. Kotlin supplies the live PairingStore lookup and platform UTF-8 conversion. Keep the lookup callback, PairingStore class name and ControlKeys constructor R8 rules. Preserve partial M1 commits, prior verified keys across new/failed handshakes, raw transcripts with first-32-byte crypto keys, and BC 1.79's canonical/low-order Ed25519 rejection boundary. PairSetup/SRP, identity storage and MFi remain Kotlin and still use Bouncy Castle.
- Rust `session::wireless_state` defines the opaque value token, queries and transitions for `WirelessRunState`; `session::wireless_recovery` owns run retry budgets, attempt validity/stage/timeout and failure decisions. Kotlin serializes tokens without decoding or duplicating active state. These are values, not heap handles. Kotlin extracts Java Throwable facts and assembles exceptions, and retains validation order, cancellable waits, watchdog scheduling, controller generations and platform resource actions. Read validity under the attempt monitor, then call the external current predicate after releasing the getter lock.
- Rust `session::control_deadline` owns the wired Iap2ControlDeadline authentication window, unlimited mode, rounding and bounded polls. Kotlin passes clocks and executes existing wired I/O. Its opaque token has zero or two Long values, interpreted only by the selected core; no heap handle is allocated. Preserve Kotlin wrapping arithmetic, query snapshots before reentrant clock calls and the absence of clock reads after unlimited authentication. Wireless deadlines and watchdog scheduling are separate owners.
- Rust `media::audio::scheduler` owns output-buffer accounting, unsigned playback-head wrap, rebuffer decisions and the safe start threshold. Its facade is confined to the renderer worker, allocated only when that worker runs, reset in place and closed by worker release. Kotlin retains actual writes, playback/prebuffer state, focus and output routing.
- Rust `vehicle::{vehicle_status, identification, vehicle_speed}` encode EV status, IdentificationInformation groups 20/21 and PASCD layout/checksum. Identification names use the existing platform string codec; PASCD numbers use the existing platform Formatter, including rounding, signed zero and non-finite spelling. Kotlin retains SDK queries, capability gates and subscriptions. PASCD still has no production speed-source assembly point.
- Rust `vehicle::route_guidance` owns navigation TLV parsing, maneuver cache, expiry, clearing and ETA state behind `RouteGuidanceState`. Its Glance and ECARX output owners are process singletons; temporary owners close their handles, serialized with queries. Kotlin supplies clocks and live-session state, and retains SDK providers, subscriptions, frame wrapping and platform outputs. Keep the maneuver DTO R8 constructor rules; Kotlin navigation state exists only when the library cannot load.
- Rust `vehicle::hid` encodes reports/descriptors, `touch_slots` owns pointer-to-slot state, and `cluster_arrow`, `lane_icon`, `day_night` own pure OEM conversion tables. Kotlin retains Android input, display dimensions, HID UID/metadata and head-unit adapters. Serialize touch report/use/close at the facade; a late report on a closed owner returns no report and Session returns false. An unhighlighted lane may still derive its direction from the raw highlight angle when its angles are empty. Other vehicle conversions remain pending.

Wired USB results belong to their diagnostic run. Reject and release stale Connected results, transfer a pipe exactly once to the mux factory, and order mux publication with close/run replacement under the existing resource monitor. Do not close resources or perform I/O under that monitor. A run's finally block terminates only its locally owned startup watchdog, never a replacement run's watchdog.

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
