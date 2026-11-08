# Privacy and diagnostics

This policy describes this fork's current Lynk & Co 01 / ECARX 753 configuration, updated on 2026-11-07.

DiPlay's default product flow uses local authentication and a direct USB/Wi-Fi connection to the iPhone. No account, remote authentication service or automatic diagnostic upload is used in that flow. The iPhone's CarPlay apps have their own internet and privacy behavior.

The head unit stores app preferences, paired-device selections, pairing data and bounded diagnostic logs in app storage. Authentication and pairing material are kept out of Android backup. Uninstalling removes app-private data; exported reports in Downloads remain until you delete them.

Opening **Backup** or the phone-report page starts a temporary HTTP service on the head unit.
A browser with the displayed QR link or an accepted pairing code can export/import the allowed settings
and request a diagnostic report directly from the head unit. The settings document includes app version,
export time and selected preferences; the export whitelist excludes pairing keys, authentication material,
hotspot names/passwords and device-specific consents. These transfers use HTTP to the browser, so keep
the link/code private and review downloaded reports before sharing them. The service has an idle timeout.

Diagnostic export is initiated by you. Reports include app/device versions, display settings and negotiation, connection transitions, Wi-Fi band/channel and state, and decoder recovery events. With the optional wheel key service on, they also list the last hardware keys it was given, as key and scan codes with what DiPlay did with each; a key that writes text is counted and never listed, and no input device name is recorded. The exporter filters protocol payloads, credential-bearing lines and common identifiers. Redaction cannot promise to recognize every vendor-specific string: review reports before posting them publicly. A GitHub issue is public.

On Android 9 and earlier, **Save diagnostic report** asks for storage access and saves to `Download/DiPlay`; if you decline, the fallbacks below apply. When the document picker or Downloads storage is unavailable, reports save under `Android/data/<package>/files/diagnostic-reports/` on primary external storage without requesting storage permission. The confirmation shows the actual TXT file path. The release package is `com.shihab.diplay`; debug builds use `com.shihab.diplay.hudtest`. File managers on newer Android versions may restrict access to `Android/data`; use **View** or **Share** in DiPlay instead. If external storage is also unavailable, reports save in private app storage. Each fallback location retains its newest eight exports, and uninstalling removes them. Sharing grants read access to the selected report only; nothing is sent automatically. On Android 9 and older, other apps with storage permission may be able to read the external reports.

Microphone access supports Siri and calls. Bluetooth/Nearby devices and Wi-Fi/Location permissions support discovery and transport. The optional local VPN permission supports the USB link; it does not provide a remote internet VPN.

If you enable **Report location to iPhone**, grant precise location access and the connected iPhone requests location data, DiPlay reads Android location fixes and sends them to that iPhone as CarPlay GPS data. This can include latitude, longitude, time, accuracy and available speed, bearing or altitude. This forwarding uses the local CarPlay link; subsequent use by iPhone apps is governed by those apps' policies.

When HUD/cluster guidance or vehicle media integration is enabled, navigation instructions, destination names, lane data, song information and artwork may be passed between the connected iPhone and the head unit's selected integration. HY proxy mode passes the relevant media or key-control data to the installed HYWebview app. Vehicle day/night state and steering-wheel key events are processed locally for the selected features.

Optional Android Usage Access supports automatic hiding/showing of the center-map overlay when the home screen is in front. With that feature enabled and access granted, DiPlay reads foreground-activity events, initially looking back up to ten minutes, and identifies the most recently resumed app package. The monitor holds that package in memory to decide whether a launcher is visible; it does not write the activity history or package names to its diagnostic log or upload them. Disabling the feature stops the monitor. Usage Access can be revoked in Android system settings.

This fork currently disables the in-app update channel (`UpdateAvailability.ENABLED = false`). It does not schedule GitHub update checks or offer update download controls in About. Releases can be obtained manually from [this repository](https://github.com/jianyun8023/diplay-ecarx). Visiting an external download or source link is subject to that service's own privacy policy.

The inherited static download website and its automatic Pages deployment have been removed from this repository. Reading the bundled license texts is offline; opening a source or download URL uses the external service and its privacy policy. This repository cleanup does not change any previously deployed remote website.
