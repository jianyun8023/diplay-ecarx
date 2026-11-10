# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

This repository retains component-specific licenses. [GPLv3 section 13](https://www.gnu.org/licenses/gpl.en.html#section13)
permits combination with AGPLv3 code; the GPL portion retains its terms and AGPL network-source requirements
apply to the combination. The AGPL portions must not be relabeled as GPL.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

The app retains `common/src/main/res/drawable/ic_carplay.png`; the old website and its icon copy have been removed.
The repository records the download source but no separate Apple graphic-use authorization.
Public availability of the download is not an open-source license; redistribution must be checked against
[Apple's trademark and copyright guidelines](https://www.apple.com/legal/intellectual-property/guidelinesfor3rdparties.html).

## ECARX and Geely runtime interfaces

AdaptAPI calls use runtime reflection and dynamic proxies. The vendor compilation JAR is no longer
included or needed for application builds or tests. Test-only endpoint fixtures contain app-owned
test behavior and are excluded from APKs.

The unused `sdk/ecarx-policy` module of declarations, constants and throwing stubs recovered from
GKUI 3.3.9 has been removed, including its Gradle registration. Historical Git revisions may still
contain these files and the former AdaptAPI JAR; this cleanup does not rewrite history or establish
a vendor redistribution grant for those historical copies.

`shared/src/main/aidl/com/geely/lib/oneosapi/input/` contains two reverse-engineered Binder interface
declarations. These declarations describe IPC signatures rather than the vendor service implementation.
Their provenance and rights should be evaluated as such, separately from firmware binaries.

## Runtime dependencies

- AndroidX — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.
- ZXing Core 3.5.3 — ZXing authors; Apache License 2.0. See [the upstream project](https://github.com/zxing/zxing).

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution, also copied to [KISS-FFT-LICENSE.txt](licenses/dependencies/KISS-FFT-LICENSE.txt). The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Software Opus microphone encoder

On head units without a MediaCodec Opus encoder, DiPlay prefers prebuilt [libopus 1.5.2](https://opus-codec.org/)
from the `io.voxkit:kopus-android:1.0.1` AAR, with a small app-owned JNI bridge. ARM64 and ARMv7 binaries
are checked in and used directly by APK builds; no Opus compilation or additional runtime download is required.
The full Opus license is retained in [COPYING](../shared/src/main/jni/opus/COPYING), and
[native provenance](../shared/src/main/jniLibs/NOTICE.md) records the artifact URL, hashes and bridge rebuild command.

DiPlay also vendors [Concentus](https://github.com/lostromb/concentus), Logan Stromberg's pure-Java port of the Opus reference library from Xiph.Org Foundation, Skype Limited, Microsoft Corporation and other contributors. It remains the fallback when the native encoder cannot load or initialize.

Concentus uses the Opus BSD-style license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored LICENSE file](../shared/src/main/java/org/concentus/LICENSE). The [vendoring notes](../shared/src/main/java/org/concentus/README.md) identify the source commit.

## Experimental authentication data

Historical upstream preview APKs included an accessory certificate/key pair recovered from public
Carlinkit C2Air Allwinner V821 firmware. Those data were not newly issued credentials for DiPlay
and were not relicensed as project source code; their continued acceptance and general redistribution
were unresolved.

Current public CI APKs from this fork contain no MFi identity. Local standalone car-test builds may
explicitly inject externally provisioned identity files; that input must be assessed separately for
permitted use and redistribution. The source archive contains no authentication private key, and
Android signing keys are not distributed in the APK.

## Retired download website

The old DiAuto-derived static website, generator and Pages deployment workflow have been removed.
The retained upstream README archives and their historical screenshot preserve attribution and
context; their upstream links are not this fork's distribution channel. DiAuto-derived app UI and
its AGPL license text remain in the project. Removing the workflow does not unpublish any already
deployed remote Pages site.

## Binary release delivery

Source distributions include the GPL text at `LICENSE`, the AGPL text at `docs/licenses/DiAuto-AGPL-3.0.txt`,
this file, `NOTICE.md`, and the component license files linked above. Keep those texts and applicable
copyright notices with redistributions.

The shared library's asset task copies the GPL/AGPL texts, these notices, `NOTICE.md`, native
provenance, Apache/Bouncy Castle/SLF4J/KISS FFT licenses, SpeexDSP/Opus COPYING, Concentus LICENSE and
source availability information into every consuming APK's `assets/licenses/`. The main app's
**About → Licenses and source** entry reads these files offline, including selectable text and
clickable source URLs. It does not replace the component-specific license terms.

The Android release workflow supplies `DiPlay-licenses.zip` extracted from those APK assets and
`DiPlay-source.tar.gz` archived from the same Git commit as the APK, together with the repository
and commit in `SOURCE-REVISION.txt` and checksums in `SHA256SUMS.txt`. All files accompany the APK
in published Releases and the manual signed-build artifact. The separate Android TV source APK
workflow also includes the Debug APK with its embedded notices, the source archive, revision and
checksums. Local distributors must include their actual modified sources; a link to the default branch does not identify an uncommitted local build.

The archive includes the retained native sources and build scripts, but the source correspondence
of extracted native binaries remains unverified; see [the native provenance record](../shared/src/main/jniLibs/NOTICE.md).
License delivery does not establish rights to the Apple icon or vendor/trademark assets.
