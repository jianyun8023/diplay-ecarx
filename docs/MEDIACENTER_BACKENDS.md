# CarPlay media output backends (dev; awaiting car validation)

**Settings → Advanced → Media information and controls (experimental) → Media control mode** selects one of three paths:

- **UNI MediaCenter (default):** registers a real CarPlay media owner with the car's MediaCenter service and publishes its media state through that service.
- **DIM direct:** retains the instrument-cluster media channel used before this backend selector.
- **Standard (HY / third-party):** keeps Android MediaSession metadata, artwork, playback state and play/pause/previous/next callbacks. DiPlay does not publish through MediaCenter or DIM; HY or another listener owns the car integration. In HY, select its `mediaSisson` scheme and authorize access to media sessions (normally notification access).

This is an actual media/control integration selection. Android MediaSession remains available in all three modes; the **Cluster media card** master preference controls only the extra UNI/DIM output. Changing the mode saves the choice and marks a reconnect as needed; the current session is not switched underneath the driver. Use the existing explicit reconnect action or connect next time to apply it. UNI remains the default.

Standard mode yields vendor media-key subscriptions and direct wheel-media forwarding to avoid sending a HY-forwarded key twice. Siri and telephone handling remain independent. The standard session starts when media audio first becomes active, remains active while paused, and is released on detach. No fabricated song, legacy metadata broadcast or second media session is added.

## Boundaries

- UNI MediaCenter requires `ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION` to be granted and the firmware's service/interfaces to match. Declaring a permission or matching the platform signature does not guarantee registration or playback authorization.
- A failed MediaCenter registration remains a reported failure; it does **not** silently start DIM as a second publisher. Select DIM explicitly and reconnect when comparing the two paths.
- Registration belongs to an eligible CarPlay media owner with real media information. It is not an empty placeholder source, and it is not immediately removed after a successful registration just to test permission. Session removal, cancellation and teardown must release that owner's registration. Late callbacks must not take over a newer owner.
- MediaCenter and DIM are not interchangeable wire formats: the MediaCenter playback status uses **1 = playing / 0 = paused**, while the existing DIM adapter uses **1 = playing / 2 = paused**. Both media paths use source type 6; equal source numbers do not make their service contracts identical.
- The UI's **Inspect MediaCenter access (read-only)** action reads permissions and interface details only. Rendering the settings, refreshing status, or choosing a backend never initializes or registers MediaCenter. Diagnostic tokens and credentials must not appear in reports.
- No HY AppId/AppKey, identity asset, signing key or system UID is copied or changed. UNI's source is not copied into this repository; the adapter is independently implemented from interface facts. The UNI reference has its own license.
- This work does not establish a fix for iPhone A2DP output selection, Siri microphone routing, HiCar exclusive key interception, automatic phone reconnection, or internet access. It does not change navigation protocols.

## Parked-car comparison

1. Note the selected backend and whether the existing media master switch is enabled. Keep the same CarPlay build, song and device while comparing.
2. Inspect permission/interface diagnostics. If access is denied or a contract is unsupported, do not infer success from platform-signature matching alone.
3. Select UNI MediaCenter, explicitly reconnect, then play a real CarPlay track. Check registration/publication diagnostics, the car's media card, artwork, play/pause, progress and steering-wheel commands. A local method return alone is not proof of visible output or correct audio routing.
4. Stop playback, leave the session, and reconnect. Verify the old owner is cleaned up and cannot issue commands or overwrite the new song. Check that an empty session does not occupy a music source.
5. Select DIM direct and explicitly reconnect. Compare the existing DIM media behavior; confirm that MediaCenter was not left publishing alongside it.
6. If a call times out or cleanup cannot be confirmed, retain that distinction in the report. Do not repeatedly retry or switch routes to hide an uncertain registration.
7. Select Standard and reconnect. Play a track once, then check HY reads title/artist/artwork/progress and can play, pause and skip while DiPlay is paused or in the background. Each wheel press must produce one action. Confirm no DiPlay MediaCenter registration or DIM media updates remain, and that Siri/telephone keys still work. Standard → UNI/DIM and UNI/DIM → Standard must apply only after reconnecting.

Cloud fixtures validate permission gates, call sequencing, ownership and UI behavior; they do not prove ECARX firmware authorization or real-car output. Source-only Debug builds still require legitimate identity prerequisites for an end-to-end CarPlay session.

Switching from UNI to DIM is also cleanup-gated: saving DIM and reconnecting does not bypass an outstanding MediaCenter token. DIM remains blocked while registration/unregistration is pending or uncertain; only confirmed cleanup opens the DIM path. A timeout is not proof that the remote call was cancelled.
