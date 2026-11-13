# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE`; the original README is in the upstream repository.

This repository retains component-specific licenses. [GPLv3 section 13](https://www.gnu.org/licenses/gpl.en.html#section13)
permits combination with AGPLv3 code; the GPL portion retains its terms and AGPL network-source requirements
apply to the combination. The AGPL portions must not be relabeled as GPL.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)

The bplist, control-cipher, HID and pair-verify primitive tests reuse LIVI's protocol golden-vector data at revision `6ac88bab7351a39b0cffac20c5dcf92ca848319b`; their implementations are ported from this repository's Kotlin code. Pair-verify reuses HKDF, Ed25519, labelled ChaCha and TLV8 vectors, not a complete handshake transcript. LIVI is GPL-3.0-or-later, with the attribution requirement in its [NOTICE](https://github.com/f-io/LIVI/blob/6ac88bab7351a39b0cffac20c5dcf92ca848319b/NOTICE). This notice is also packaged in the app's open-source notices.

## Home and settings UI

`common/src/main/java/com/shihab/diplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

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
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.
- ZXing Core 3.5.3 — ZXing authors; Apache License 2.0. See [the upstream project](https://github.com/zxing/zxing).

Bouncy Castle 1.79 (The Legion of the Bouncy Castle Inc.; MIT-style license) is retained only for independent tests, not the production runtime.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution, also copied to [KISS-FFT-LICENSE.txt](licenses/dependencies/KISS-FFT-LICENSE.txt). The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Opus codec (libopus)

Opus audio is decoded and the microphone is encoded by the reference [libopus](https://github.com/xiph/opus)
(1.5 series), statically linked into `libdiplay_media.so` from the sources vendored by
[libopus_sys 0.3.3](https://crates.io/crates/libopus_sys/0.3.3). libopus is BSD-3-Clause and keeps the
copyright notices of Xiph.Org, Skype Limited, Octasic, Jean-Marc Valin, Timothy B. Terriberry, CSIRO,
Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo, Mozilla and Amazon; the full text is in
[Opus-COPYING.txt](licenses/dependencies/Opus-COPYING.txt). libopus_sys itself is MIT licensed.

DiPlay retains [Concentus](https://github.com/lostromb/concentus), Logan Stromberg's pure-Java port of the Opus reference library from Xiph.Org Foundation, Skype Limited, Microsoft Corporation and other contributors, only as an independent test oracle. It is excluded from production source sets; the production software Opus encoder uses the statically linked libopus.

Concentus uses the Opus BSD-style license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored LICENSE file](../shared/src/testCore/java/org/concentus/LICENSE). The [vendoring notes](../shared/src/testCore/java/org/concentus/README.md) identify the source commit.

## Media data plane (ring)

The CarPlay screen and audio streams are received and opened in `libdiplay_media.so`, with ChaCha20-Poly1305 from [ring 0.17.14](https://github.com/briansmith/ring)
by Brian Smith, which carries ChaCha20 and Poly1305 assembly from Google's [BoringSSL](https://boringssl.googlesource.com/boringssl)
and, through it, OpenSSL. They are compiled with the app-owned `diplay-media` crate into `libdiplay_media.so`;
ARM64 and ARMv7 binaries are checked in and used directly by APK builds. The crate also links jni-sys, libc, cfg-if
and getrandom, each dual-licensed MIT or Apache-2.0.

ring's own code and `untrusted` use ISC-style licenses; code taken from BoringSSL keeps its Apache-2.0, ISC or
OpenSSL notices. The full texts are in [the Rust crate licenses](licenses/dependencies/Rust-crates-LICENSES.txt),
and [native provenance](../shared/src/main/jniLibs/NOTICE.md) records the source, hashes and rebuild command.
Migrated screen/protocol/crypto facades require the native core. Remaining audio fallback retirement is in progress; Android codec/output capability fallbacks are separate platform behavior.

## Pairing and protocol cryptography

`curve25519-dalek 5.0.0` supplies raw X25519 arithmetic under BSD-3-Clause; `subtle 2.6.1`
uses BSD-3-Clause. SRP uses `num-bigint 0.5.1`, `num-integer 0.1.47` and `num-traits 0.2.19`
under the MIT option of their dual licenses. The license bundle also retains MIT notices for
build/target-specific dependencies `autocfg 1.5.1`, `cpufeatures 0.3.1`,
`curve25519-dalek-derive 0.1.1`, `fiat-crypto 0.3.0`, `rustc_version 0.4.1` and `semver 1.0.28`.
Verbatim notices from the locked crate versions are included in
[the Rust crate licenses](licenses/dependencies/Rust-crates-LICENSES.txt), packaged with the APK.

The MfiSAP `/auth-setup` runtime uses RustCrypto [aes 0.8.4](https://github.com/RustCrypto/block-ciphers/tree/f2dbee516b4d0cf4cb4f3045d09e35b5fd80087b/aes)
and [ctr 0.9.2](https://github.com/RustCrypto/block-modes/tree/65320945adb1b6f7290e61b033f9e04ccdb2581b/ctr)
for AES-128-CTR. Their additional locked dependencies are `cipher 0.4.4`, `crypto-common 0.1.7`,
`inout 0.1.4`, `generic-array 0.14.7`, `typenum 1.20.1`, `version_check 0.9.5` and
`cpufeatures 0.2.17` (separate from the `cpufeatures 0.3.1` above; the set includes build/target-specific dependencies).
These nine crate notices use MIT, choosing the MIT option where dual-licensed. Their unmodified
upstream license texts and copyrights, source archive checksums and revision links are retained in
[the same license bundle](licenses/dependencies/Rust-crates-LICENSES.txt); exact package versions and
registry checksums are fixed in [Cargo.lock](../shared/src/main/rust/diplay-media/Cargo.lock).

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
