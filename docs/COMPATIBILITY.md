# Compatibility

This fork targets only Android 9 (API 28), ECARX 753, Qualcomm 820A with its existing platform signature. Packages contain ARM64 and ARMv7 only. Other Android releases are development/debug environments, not production support commitments. The app has no ADB runtime or authorization dependency; see [the permission checklist](ADB_AND_PLATFORM_PERMISSIONS.zh-CN.md).

The upstream/model reports below are historical references, not this fork’s production support scope.

This public preview is an independent receiver, not an Apple-certified CarPlay accessory. The experimental bundled accessory identity is extractable and its future acceptance is not guaranteed.

| Area | Current scope |
| --- | --- |
| Head unit | Android 9 / API 28, ECARX 753 / 820A; ARM64 and ARMv7; vehicle platform signature required for protected features |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Physical evidence | Previous private builds: wired and wireless picture, touch and audio confirmed on the development car with iPhone XS / iOS 18.7.10 |
| Other cars | Mixed community reports across DiLink generations; not a certified model support list |
| 0.2.14 evidence | Automated source/build validation and attributed contributor tests; no new complete-release vehicle test or universal model support is claimed. Earlier DiLink5.1 HUD/hotspot results remain historical evidence. |
| Wi-Fi | Auto uses eligible saved/aligned channels; beside a 5 GHz station, explicit 2.4 GHz precedes other 5 GHz/unpinned default fallbacks. Manual channels stay explicit; no band/performance guarantee. |
| Video | Default H.264 / 30 fps; 60 fps and HEVC increase device-specific demands |

## Geely / Lynk & Co steering-wheel keys

On the Geely/Lynk & Co head unit (`geely_fx11`, model `CS11_A4`, DesaySV, Android 11) the steering wheel does not produce standard `KEYCODE_MEDIA_*` events, so the media session and the accessibility path never see it. DiPlay detects this head unit and subscribes to the wheel's vendor key codes on the car's own input service (`com.geely.inputservice`), then forwards play/pause and skip to the active CarPlay session, and the microphone key's long press to Siri. The subscription exists only while a CarPlay session is active, and on every other head unit nothing is bound and behaviour is unchanged.

Two limits come from the car, not from DiPlay:

- While CarPlay is connected on ECARX, DiPlay registers the voice-key broadcast at maximum priority in either Android or ECARX media mode. Only an unmarked short press (`ACTION_TYPE=0`) in an ordered broadcast is consumed. It requests Siri immediately by default; when the voice key is assigned to custom Siri, the broadcasts follow its single/double-press setting, interval and double-press delay even without Android key events. Long presses, unknown actions and calls keep the factory behavior. Registration follows the session through background use and is released on disconnect, cancelling pending Siri requests. Raw ECARX/Geely and duplicate Android voice callbacks cannot request Siri again while this route owns the key. The diagnostic report records registration, gesture decisions and whether Siri was requested. Disable HY's voice-key takeover during testing to avoid competing owners; factory wakeup before broadcast delivery remains a vehicle-test limitation.
- This firmware opens the microphone only for the voice-communication audio source, so DiPlay uses it for Siri on this head unit. Voice-recognition input elsewhere is unchanged.

When the car's own music app is playing as well, the wheel reaches both it and CarPlay: the head unit routes the key to its current audio source before any app sees it.

Tested on the car named above; other Geely/Lynk firmware is untested.

## Lynk 01/05 820A factory HiCar

**Settings → Vehicle → Wheel keys → Disable factory HiCar** appears when `com.ecarx.hmi` is
installed, including when it is disabled. The control reads the package's actual state. Changing it
requires the system permission `android.permission.CHANGE_COMPONENT_ENABLED_STATE`; builds without
that permission show a read-only switch. Opening settings does not change HiCar.

Turning the switch on disables factory HiCar to reduce long-press voice-key conflicts with Siri.
It stays disabled after DiPlay closes. Turning the switch off restores HiCar; the head unit may
need a restart before HiCar works again. DiPlay verifies the package state after each change.
This control does not change microphone routing. Host tests cover the settings and package-state
handling; the permission grant and actual voice-key behavior still need an 820A vehicle check.

## Android 9 Wi-Fi Direct and experimental features

The Android 9 path creates or reuses a persistent system profile with the public legacy group API, reads generated credentials and uses the firmware channel setter where available. It serializes pinning/cancellation, avoids foreign groups and removes only its owned active group; persistent profiles are not deleted. Unknown setter outcomes stop startup, and unreliable pinning/cleanup should use the manual car hotspot. See [legacy limits](ANDROID9_WIFI_DIRECT.md); the contributor's Redmi K20 Pro result does not validate every BYD Android 9 firmware.

DiLink 3 call controls/dashboard cards and AAC-LC buffered music are independent default-off experiments. They appear in **Settings → Navigation → BYD navigation** (or **Advanced → Advanced vehicle data** when that card is unavailable), and **Advanced → Video and audio** for buffered music. Their corrected source has regression coverage; full call/audio/microphone/restoration and music-interruption acceptance remains device work. DiLink 4 casting/picture fixes describe the tested 2022 Seal setup, not a Qin/Seal-wide guarantee. The experimental Android 13+ hotspot join helper has been removed; use the system hotspot controls and standard connection flow.

## Current settings and opt-in limits

A first launch with no saved iPhone opens the **Setup guide**; it can be skipped and reopened from **Settings → Overview**. It reads the DiLink version from the system build name (for example `DiLink3.0`), lets the driver correct it, and only offers features that apply to that generation, labelled `Tested on some cars` or `Experimental`. On DiLink 3 it hides the DiLink 4 cluster route and offers to turn it off, because that route stops the DiLink 3 dashboard map. The labels summarise the reports above; they are not a certified support list.

**Settings → Display** holds resolution, frame rate, icon/text size, appearance and Interface size; Interface size changes DiPlay controls, not CarPlay geometry. **Audio** holds routing and the ordinary music-buffer choice. **Navigation** holds location/BYD guidance, **Vehicle** holds gestures/wheel keys/car-button controls, **Connection** holds transport/startup/permissions, **Advanced** holds experimental display/media and vehicle data, and **Diagnostics** exports reports.

**Smooth video (experimental)**, **Call echo cancellation (experimental)** and **Clearer call voices (experimental)** are off by default under **Settings → Advanced → Video and audio**. Smooth video adds adaptive pacing delay, can delay touch response, and uses a SurfaceView path without picture adjustments. Changing it reconnects a running session. The call-processing controls apply at the next connection; source/JNI tests do not establish acoustic quality on a car. Optional rotation, split-screen areas and side panel are off by default under **Advanced → Display (experimental)**. Existing opted-in settings are retained during an in-place update.

The specifically observed 1280×480 DiLink 3 projection surface is now recognized by the existing cluster-map path. Actual map output still needs an on-car retest; the measured 1920×720 calibration is not applied to that smaller panel. Narrow USB framing/queue repairs and Bluetooth receive fixes have regression coverage, but they do not establish a fix for every connection report. A charging-only USB port cannot supply the data transport.

## Known limitations

- If the iPhone leaves its current Wi-Fi network but does not join the car hotspot, check that Auto-Join is enabled for that hotspot. If selecting it manually starts CarPlay without a password prompt, record that distinction in the diagnostic report. AP broadcast information elements can affect automatic joining even when credentials and the subsequent CarPlay transport work.
- Some units stutter, particularly under higher video load. A 2.4 GHz link alone does not prove the cause: interference, firmware and decoder stalls can all contribute. Try Default icons, 30 fps and a lower resolution, then attach a report.
- Wi-Fi Direct can stutter every few seconds while the car's own Wi-Fi client searches for networks. The car has one radio, so each search takes it off the CarPlay channel and audio packets sent meanwhile are lost; a larger music buffer cannot replace them. Reports from DiLink 3, 4.0 and 5.0 show the search every 10 s when the client is not joined to a network, and every 20–30 s on some other firmware or while it is joined. The built-in car hotspot runs with the client off and is the recommended connection. On the Android 9 target, DiPlay uses CONNECTIVITY_INTERNAL to pause automatic joining during Wi-Fi Direct sessions. Its serial worker, leases, durable restore marker and retry/reopen recovery remain active. Missing permission leaves Wi-Fi unchanged. Other apps can still request scans; this native path requires vehicle validation.
- Some iOS/head-unit combinations do not visibly apply icon and text size. Reconnection is implemented; that does not guarantee the iPhone chooses the requested layout.
- A radio that supports joining a 5 GHz network may still reject a 5 GHz Wi-Fi Direct group. The capability flag is diagnostic, not proof of group-owner support.
- Automatic startup depends on the car's firmware and startup permissions.
- Wireless CarPlay cannot start on a head unit that keeps calls and music on the maker's own Bluetooth module, because Android's Bluetooth there accepts connections that reach no phone. A common sign: the iPhone is paired in the maker's own Bluetooth or Phone app, not in Android's Bluetooth settings. Since 0.2.18 DiPlay tests for this and stops with the cause instead of retrying ([#590](https://github.com/shihabal3amri/DiPlay/pull/590)). Confirmed on one Allwinner T8 with TopWay firmware; [#330](https://github.com/shihabal3amri/DiPlay/issues/330) and [#354](https://github.com/shihabal3amri/DiPlay/issues/354) show the same symptom. USB is the only route left to try on such a unit.
- Wireless CarPlay also cannot start on a head unit whose Bluetooth service gives apps no socket manager: every RFCOMM connect fails inside Android within a few milliseconds, while the car's own calls and music keep working. Seen on a Changan CS35 Plus with an Allwinner T7 on Android 9 ([#709](https://github.com/shihabal3amri/DiPlay/issues/709)). DiPlay stops with that cause instead of retrying. Use USB on such a unit.
- A head unit whose Android has no Bluetooth adapter at all is told so, when the driver chooses an iPhone and when a connection is tried, instead of being asked to turn Bluetooth on. Where the adapter exists but its switch will not stay on, as on the GAC unit in [#581](https://github.com/shihabal3amri/DiPlay/issues/581), the request to turn Bluetooth on adds that this means wireless CarPlay cannot work there once the driver has been to Bluetooth settings and come back with it still off.
- USB requires a data port and correct host/device-role behavior.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.

For a current report, reproduce the problem on the latest release, then open **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 9 uses a document picker. The confirmation identifies fallback storage and offers **View report** and **Share**. Attach the reviewed `.txt` to a matching [existing issue](https://github.com/jianyun8023/diplay-ecarx/issues), or [create an issue](https://github.com/jianyun8023/diplay-ecarx/issues/new/choose). Include car/head-unit, Android/firmware, iPhone/iOS, connection mode, steps and failure time. Nothing uploads automatically.

Reports record requested frequencies, station association state, fallback failures and remembered-configuration events. Android 10+ can verify the actual group frequency; Android 9 reports its accepted channel request as unverified and system-default as channel 0. Wi-Fi credentials and protocol payloads are excluded. A successful hotspot is not itself a successful CarPlay session.

Android references: [SupplicantState](https://developer.android.com/reference/android/net/wifi/SupplicantState), [explicit P2P operating frequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setGroupOperatingFrequency(int)).
