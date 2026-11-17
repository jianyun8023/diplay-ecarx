# Vehicle and navigation ownership

Read for HUD/DIM, route guidance/lanes, location/EV/speed reports, identification, HID/touch or wheel keys. For dashboard session content also read [Session and transport](SESSION_AND_TRANSPORT.md#dashboard-content-lifetimes); for NowPlaying and MediaCenter read [Media](MEDIA.md#nowplaying-artwork-and-auto-play). Register vendor interfaces and real-car evidence in the [platform notes](../ECARX_753_PLATFORM_NOTES.zh-CN.md).

These rules are part of [AGENTS.md](../../AGENTS.md). Apply its global ownership and validation rules as well. Paths in code spans are relative to the repository root.

## HUD and route guidance

- Rust `vehicle::hud` owns DIM presentation intent, retry phases, lane cache/selection, applied publication snapshots, refresh and cleanup plans. Kotlin retains DIM objects/callbacks, successful-call commits and persistent settings. Persist release responsibility before opening; clear it only after an accepted hide. Preserve SDK call order and partial-publication cleanup. The process singleton owns its native state; temporary owners serialize use with close. Lane prefetch remains bounded to 32 entries while protecting the selected index.

- Rust `vehicle::route_guidance::lane_update` owns complete 0x5204 lane parsing through the shared Rust iAP2 body parser. Kotlin only assembles immutable lane DTOs. Preserve first outer/inner scalar fields, all known duplicate-field widths, stable lane-index sorting, wire angle order, ±1000 unknown-angle omission and empty-body-only clear semantics.

- Rust `vehicle::route_guidance` owns navigation TLV parsing, maneuver cache, expiry, clearing and ETA state behind `RouteGuidanceState`. Its Glance and ECARX output owners are process singletons; temporary owners close their handles, serialized with queries. Kotlin supplies clocks and live-session state, and retains SDK providers, subscriptions, frame wrapping and platform outputs. Keep the maneuver DTO R8 constructor rules; The navigation facade requires native; the independent Kotlin algorithm is test-only.

## Vehicle conversion and reporting

- Rust `vehicle::{vehicle_status, identification, vehicle_speed}` encode EV status, IdentificationInformation groups 20/21 and PASCD layout/checksum. Identification names use the existing platform string codec; PASCD numbers use the existing platform Formatter, including rounding, signed zero and non-finite spelling. Kotlin retains SDK queries, capability gates and subscriptions. PASCD production speed is fed by `EcarxVehicleSpeedSource` from ECARX `ISensor` 1048832 (m/s) and VHAL `GEAR_SELECTION`; real-car subscription paths have been observed; dynamic PASCD output and actual service-disconnect recovery remain unverified (see `docs/benchmarks/vehicle-speed-2026-11-14.md`).

- Rust `session::report_subscription` owns location/EV reporting subscriptions and deadlines; `vehicle::location` owns NMEA conversion. Location commits its one-second attempt before provider/send; EV commits its thirty-second deadline after successful send. Kotlin retains Calendar/Formatter and provider/transport actions, including their original failure ordering.

## HID, touch and wheel keys

- Rust `vehicle::hid` encodes reports/descriptors (including the media/telephony key press(index)-then-release(0) byte pair), `touch_slots` owns pointer-to-slot state, and `cluster_arrow`, `lane_icon`, `day_night` own pure OEM conversion tables. Kotlin retains Android input, display dimensions, HID UID/metadata and head-unit adapters. Serialize touch report/use/close at the facade; a late report on a closed owner returns no report and Session returns false. An unhighlighted lane may still derive its direction from the raw highlight angle when its angles are empty. Other vehicle conversions require actual production callers before adding new adapters.

Rust `vehicle::wheel_keys` owns WheelKeyPresses disposition/commit rules and WheelSiriKey timing state. Each consumer retains independent opaque values; Kotlin owns the Any-key map with its existing equality, null-key handling and callback execution. Keep absent, recorded PASS and recorded consumption distinct. Remove on UP before querying its disposition; call decide only for an unrecorded first DOWN and commit only after it returns normally, preserving its original Action and reentrant outer-write order. No global key registry, lossy hash identity, timeout or implicit session reset. Single/double Siri calls share one nullable timestamp per instance: rejected single presses refresh it, confirmed doubles clear it, 400 ms single and inclusive 180 ms/configured maximum double boundaries preserve wrapping Long subtraction. Invalid maximum intervals throw before state mutation. Kotlin retains time reads, KeyEvent/settings/session references and Handler; Host/service pending decisions, the 1500 ms release window and VoiceKeyPair remain outside this batch.
