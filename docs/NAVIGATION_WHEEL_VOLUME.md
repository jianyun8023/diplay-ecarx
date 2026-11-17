# Navigation guidance volume keys

Original contribution: 寒叙 (@Hanxu4131). This document describes the current ECARX port; its installation uses the platform permission path.

## Enable and use

Under **Settings → Advanced**, enable **Wheel volume during navigation guidance (experimental)**. It defaults to off and uses the existing wheel-key accessibility service. Enable that service from Android settings or the application's platform-permission button; this port does not require ADB. See [platform permissions](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md).

On ECARX units the setting also replays the head unit's own CarPlay handshake, so the car can route the volume keys to its navigation group while following the iPhone: a `com.lge.ivi.cp.action.CAR_PLAY_DUCK_COMMAND` broadcast for each iPhone duckAudio/unduckAudio (with the prompt's dB volume converted to the car's 0–100 duck percentage), plus a one-shot `contentType=33` audio-focus request that opens the car's alternate-audio mode. The car then selects `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` (group 1) itself; DiPlay does not write the group.

Outside that ECARX mode, the setting only redirects volume keys while an active CarPlay navigation prompt has an identifiable separate legacy audio stream. Automatic/usage routing, a rejected legacy route, ambiguous simultaneous routes and media stream 3 pass through to the car. Changes to the selected audio route use the existing reconnect flow. This control does not create a separate navigation stream.

A missing session, inactive guidance, an unbound service, a call or priority voice playback prevents new navigation captures. Existing call, zoom and joystick handling retain their priority. A consumed DOWN keeps repeats and UP paired; a passed DOWN is never captured halfway through the press.

## Implementation boundaries

- `NavigationWheelSettings` owns the saved opt-in and authorization; `WheelKeyService` selects the eligible stream and changes its volume. `NavigationPlayback` tracks actual PCM playback and its quiet tail, scoped to renderer lifetimes.
- `EcarxCarPlayAudioHandshake` owns the ECARX broadcast and alternate-audio request. It is created with the session sink, opens the vendor mode only when the setting and ECARX detection both hold, sends `unduckAudio` when a prompt is left open or the sink closes, and never writes a volume group. The alternate request is out-of-stack: it does not own focus and does not enter `getCurrentAudioPeekInfo()`.
- Duck ownership on the car's own navigation channel: while the handshake is active and the prompt plays on route 0, the car's duck broadcast lowers the music group itself (`setGroupVolume` in `setCarPlayDuckVolume`), so DiPlay stops asking for its own prompt focus there (`carOwnsNavigationDuck`). One prompt therefore has one duck, and the car restores its saved level on unduck. On the music route (3) nothing changes: DiPlay keeps its existing skip and focus behavior. `AudioControlMode=01123101` may change the head unit's routing/DSP; turning the experimental setting off closes the mode at the next sink close.
- The service uses `AudioManager` stream get/set operations and reads the value back. An unsupported route, failed write or mismatch passes the initial key through; reaching a confirmed volume limit still consumes it. Successful navigation captures do not change media volume.
- On API 28 and later, all streams use the platform minimum; a rejected stream follows the existing pass-through behavior.
- Accessibility restoration uses the existing platform-permission path and preserves unrelated services. The current implementation is in [WheelKeyService](../common/src/main/java/com/shihab/diplay/WheelKeyService.kt); the old upstream ADB restore instructions and one-attempt budget do not apply.

## Validation

The current [acceptance matrix](RUST_CORE_REMAINING.zh-CN.md) records the user's 2026-11-14 confirmation of this feature. For a new change, check the affected key-pairing, routing, permission and playback contracts; keep actual vehicle conclusions tied to their tested package.
