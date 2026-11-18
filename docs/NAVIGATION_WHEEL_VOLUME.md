# Navigation guidance volume keys

Original contribution: 寒叙 (@Hanxu4131). This document describes the current ECARX port; installation uses the [platform permission path](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md).

## Problem and firmware evidence

The target problem is a spoken navigation prompt whose volume keys still change music volume. On ECARX 753 / Qualcomm 820A (Android 9 / API 28), requesting navigation focus alone does not establish the vendor's navigation-volume selection state. This PR adds an optional car-owned backend; the existing DiPlay backend remains the default because the car's focus/DSP policy can attenuate music excessively.

The recovered `CarAudioImplDCY11.getSuggestedAudioUsage()` checks call and priority-voice states first, then returns navigation usage 12 only when `mCPDuckingState && AudioManager.isAlternateAudioOn()` is true. Otherwise it chooses among active playbacks. The CarPlay duck receiver sets the first state; the contentType 33 focus path toggles the second without becoming an ordinary focus-stack owner. A successful request alone therefore does not prove the mode is on. Both `Car.AUDIO_SERVICE` and `Context.AUDIO_SERVICE` name the same `"audio"` service in this firmware.

`setCarPlayDuckVolume()` acts on the group selected by `getCurrentAudioPeekInfo()`, which reflects the current focus-stack top. It does not unconditionally select the media group. Its saved/restored volume and repeated-duck behavior explain why reproducing the original percentage-based broadcast can alter the wrong group or lose a wheel adjustment. DiPlay now sends zero depth and retains navigation focus; it does not infer that removing navigation focus is safe.

The evidence comes from the locally recovered `CarAudioImplDCY11` and `MediaFocusControl` implementations (2026-10-31 / 11-02 / 11-07). These are source-level findings, not acceptance of this PR on a real car. The [platform notes](ECARX_753_PLATFORM_NOTES.zh-CN.md) register the interfaces and their validation boundary.

## Version context

| Baseline | Relevant behavior |
| --- | --- |
| v124 `50829942`, v125 `950e9470` | v125 was the PR #46 Soft AP observer test package, not a new audio backend. Both used configured navigation routing and duck/unduck-driven temporary focus. |
| v128 / v129 `98e99167` | The relevant sink and prompt-focus implementations match v125. The later v129 car investigation recorded inaudible common prompts without duckAudio/focus; that history cannot establish that focus-free route 0 works now. |
| Later `b4d1b805`, `455d719e` | Audible-PCM-based prompt lifetime replaced open-stream lifetime; follow-iPhone routing selects the car or music route. This is part of the retained DiPlay policy, rather than functionality first introduced by this PR. |
| PR base `d1425f52` | The pre-PR comparison for the off mode. The car-owned preference defaults off and preserves the saved focus settings. |

Version codes alone do not identify source: v125 and v129 were package-time overrides. Their local package build-source records establish the commits above; no private package or authentication asset is included here.

## Enable and use

Under **Settings → Advanced**, enable **Wheel volume during navigation guidance (experimental)**. On ECARX, an additional **Let the car control navigation audio (experimental)** switch selects the audio backend. It defaults to **off** and applies at the next CarPlay connection.

| Car audio control | Playback and ducking |
| --- | --- |
| Off (default) | Preserve the pre-PR DiPlay playback, routing, focus and ducking behavior. Existing settings retain their values. No ECARX alternate request or CarPlay duck broadcast is added. |
| On | When the alternate-mode probe passes, route prompts to the car navigation channel (route 0) and let the car lower music while navigation focus is granted. The legacy DiPlay playback and focus policy applies when the probe fails. |

The off mode retains the existing accessibility-service key routing: only an active prompt with an identifiable separate legacy stream can be captured. Automatic/usage routing, media stream 3 and ambiguous routes pass through. That path requires wheel-key accessibility permission. Calls, zoom and joystick retain priority; consumed DOWN/repeats/UP remain paired.

The on mode uses the car's volume-key selection. During guidance and its buffered tail, the vendor marker selects navigation usage 12 (group 1), subject to the car's higher call/voice priorities. Guidance without an iPhone duckAudio command can also be marked. The adapter skips the marker if any group the receiver could write is at zero or unreadable.

## Implementation boundaries

- The host offers the adapter when navigation volume keys and car audio control are enabled on an identified ECARX unit. The sink verifies that it can open and release alternate mode before choosing the car route, focus and ducking policy. An unreadable state, a failed request, a grant without a state change, or an already-on mode results in `modeProbe=unsupported` and the saved DiPlay policy. A mode already on may belong to another client.
- Rust `media::audio::scheduler` retains routing and gain policy. `NavigationPromptFocus` uses the existing duck, audible PCM, tail and timeout decisions. A granted navigation focus transfers attenuation to the MCU, so a CAN_DUCK loss during that grant maps to `PlatformDuckingLoss`. Transient-loss mute and denied-focus mute remain in force.
- Every marker uses `volume=0`. The firmware still saves the current focus group's index and clamps the result to at least 1. The adapter requires groups 0, 1, 2 and 4 to have readable positive levels before sending duck. It sends only unduck at the end. If the ordered broadcast completes and the navigation group has returned to its starting value, the adapter preserves the driver's changed level, including 0. That correction applies to the navigation group only.
- Parking Assist can ignore unduck and leave the firmware flag set. On prompt release, the app releases its own alternate request independently of broadcast delivery. This removes one of the two conditions for the firmware's navigation key targeting. Broadcast completion gives no confirmation of the raw firmware flag.

## Validation

Automated checks cover the default-off preference, malformed backup values, the sink's legacy route and focus on probe failure, volume protection across focus-group changes, navigation adjustment to zero, discarded Parking Assist cleanup, alternate ownership, and audible tail, timeout and disconnect. The settings screenshots below were generated at 1920×1080.

```sh
./gradlew --offline --no-daemon \
  :shared:testDebugUnitTest \
  --tests '*EcarxCarPlayAudioHandshakeTest' --tests '*NavigationPromptFocusTest' \
  --tests '*AudioFocusAutoYieldTest' --tests '*NavigationAudioRouteTest' \
  --tests '*AndroidMediaSinkStateTest' --tests '*AndroidMediaSinkDetachTest' --tests '*AudioRouteAttributesTest' \
  :common:testDebugUnitTest \
  --tests '*AudioFocusSettingsTest' --tests '*SettingsBackupCodecTest' \
  --tests '*EcarxSettingsScreenshotsTest.navigationAudioBackendChoiceFitsTheCurrentHeadUnit' \
  --tests '*SettingsTranslationsTest' --tests '*TraditionalChineseTranslationsTest' \
  --tests '*SettingsLayoutSourceRulesTest' :mobile:lintDebug :mobile:assembleDebug
cargo test --locked --offline --manifest-path shared/src/main/rust/diplay-media/Cargo.toml media::audio::scheduler::tests
# ANDROID_NDK_HOME must point to the installed NDK 28.2.13676358.
scripts/check_rust_android_native.sh arm64-v8a
```

![Car-owned backend enabled, 1920×1080](screenshots/navigation-audio-car-1920x1080.png)

![Existing DiPlay backend, 1920×1080](screenshots/navigation-audio-diplay-1920x1080.png)

## Real-car acceptance

1. Compare the off setting to the pre-PR package with the same preferences. On a firmware that fails the probe, compare the on setting with off for route, focus and ducking, and check `modeProbe=unsupported`.
2. Verify that the first prompt after cold boot is audible, with and without music. Include iPhone coexist guidance, the actual MCU attenuation and focus notifications, buffered tails, missing-unduck timeout and disconnect.
3. Verify the platform-signed app can read and restore the real car audio group. Check group 1 key targeting and confirm a newly adjusted level persists after the prompt, including an adjustment to 0. Start separate prompts with media or navigation at 0 and verify the firmware marker is skipped and the level stays at 0.
4. Let a Parking Assist warning overlap the end of a marked prompt. Verify alternate turns off and the physical keys return to their normal target even if the receiver ignores unduck. Check a new prompt after the warning. Record the raw duck flag separately from the key target.
5. Record the vendor mode and group state after killing and restarting the app mid-prompt. The out-of-stack mode has no verified process-death cleanup. Also verify that a mode already owned by another vendor client is left intact.

Historical confirmation of the accessibility feature does not establish acceptance of the new car backend. See the [acceptance matrix](RUST_CORE_REMAINING.zh-CN.md).
