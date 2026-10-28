# Manual car hotspot control (dev; awaiting device validation)

This experimental control is a **user-triggered Android 9 / API 28 platform attempt**, not an ECARX-specific hotspot setter. It does not establish support on the 2021 Lynk & Co 01 until tested on that firmware.

## Scope and safety

- The Connection settings show AP state separately from a request's result. ON only means the access point is reported on: it does not prove internet access, iPhone association, DHCP readiness, authentication, or a working CarPlay session.
- Each explicit action issues at most one start/stop request. Closing requires confirmation because other devices may disconnect. Active/connecting CarPlay and an ongoing startup/control operation block manual changes; exit the session first.
- No new automatic startup/retry policy, ADB fallback, hidden-API exemption, permission auto-grant, OEM broadcast, TCAM setting, SSID/password/band change, or Bluetooth change is introduced.
- Existing automatic hotspot opt-in is independent and is not silently changed. It can turn the hotspot on at a future connection. Turning the AP off now is not a permanent disable of that existing preference.
- The control uses the normal Modify system settings permission screen. Permission being granted is only a prerequisite: platform provisioning requirements, firmware policy, or hidden API restrictions can still deny a call. Unsupported/unknown/denied cases retain the native settings entry.
- Cancellation stops waiting; it does not undo an already submitted platform request. A timeout/unknown reading is not reported as success. A later screen refresh reads the current AP state without another write.

## Device checks (parked)

1. With CarPlay disconnected and no connection being attempted, inspect the AP state. Compare it to the car's native settings. A missing/hidden state must show unknown, not off.
2. Without Modify system settings access, tap the permission entry. Declining it must not change the hotspot. No automatic permission grant is expected.
3. With access granted, explicitly start the saved hotspot. Record whether the app reports ON, denied, unsupported, failed, or timed out. Cross-check in native settings; do not treat an accepted request as a successful state change.
4. Confirm a manual close. Check that native settings report OFF. Cancelling the confirmation must leave it unchanged. Observe whether other connected devices lose access as warned.
5. During active or connecting CarPlay, both manual writes must be blocked. During an in-flight manual operation, a new connection must not take ownership. After cancellation or leaving the screen, there must be no automatic reverse request/retry.
6. Repeat with English, Simplified Chinese, and Traditional Chinese; check compact/full windows and large text. Keep generic RTL layout behavior even though Arabic is not an application translation.
7. On another API level or firmware that rejects reflection, use the native settings fallback; failure to control directly is expected, not permission to add guessed vendor commands.

A source-only Debug APK without legitimate identity material cannot exercise the application's CarPlay handshake. UI/AP/native-system checks are distinct from an end-to-end CarPlay test. Do not include passwords, SSIDs, addresses, device identifiers, or raw personal logs in reports.

## Evidence and references

- [UNI SystemStatusAdapter](https://github.com/flzyup/uni-launcher/blob/b1f68d3/app/android/app/src/main/java/xyz/i4o/uni/launcher/integration/SystemStatusAdapter.java) is a **read-only design reference** (AP state and client observation), not a setter implementation. No UNI source was copied; its custom license is not imported into this project.
- Android 9's [IConnectivityManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/net/IConnectivityManager.aidl) defines the platform start/stop signatures. [ConnectivityService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/core/java/com/android/server/ConnectivityService.java#L3232-L3246) performs permission enforcement; WRITE_SETTINGS is not a universal guarantee of access.

Automated fixtures validate software behavior only. Device validation and the final full-suite result must be reported separately; no real-car success is claimed here.
