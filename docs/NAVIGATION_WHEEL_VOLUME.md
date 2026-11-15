# Navigation guidance volume keys

Original contribution: 寒叙 (@Hanxu4131). This document describes the current ECARX port; its installation uses the platform permission path.

## Enable and use

Under **Settings → Advanced**, enable **Wheel volume during navigation guidance (experimental)**. It defaults to off and uses the existing wheel-key accessibility service. Enable that service from Android settings or the application's platform-permission button; this port does not require ADB. See [platform permissions](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md).

The setting only redirects volume keys while an active CarPlay navigation prompt has an identifiable separate legacy audio stream. Automatic/usage routing, a rejected legacy route, ambiguous simultaneous routes and media stream 3 pass through to the car. Changes to the selected audio route use the existing reconnect flow. This control does not create a separate navigation stream.

A missing session, inactive guidance, an unbound service, a call or priority voice playback prevents new navigation captures. Existing call, zoom and joystick handling retain their priority. A consumed DOWN keeps repeats and UP paired; a passed DOWN is never captured halfway through the press.

## Implementation boundaries

- `NavigationWheelSettings` owns the saved opt-in and authorization; `WheelKeyService` selects the eligible stream and changes its volume. `NavigationPlayback` tracks actual PCM playback and its quiet tail, scoped to renderer lifetimes.
- The service uses `AudioManager` stream get/set operations and reads the value back. An unsupported route, failed write or mismatch passes the initial key through; reaching a confirmed volume limit still consumes it. Successful navigation captures do not change media volume.
- On API 28 and later, all streams use the platform minimum; a rejected stream follows the existing pass-through behavior.
- Accessibility restoration uses the existing platform-permission path and preserves unrelated services. The current implementation is in [WheelKeyService](../common/src/main/java/com/shihab/diplay/WheelKeyService.kt); the old upstream ADB restore instructions and one-attempt budget do not apply.

## Validation

The current [acceptance matrix](RUST_CORE_REMAINING.zh-CN.md) records the user's 2026-11-14 confirmation of this feature. For a new change, check the affected key-pairing, routing, permission and playback contracts; keep actual vehicle conclusions tied to their tested package.
