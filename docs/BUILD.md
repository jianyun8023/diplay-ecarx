# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This repository contains several Android applications.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-debug.apk` |
| `:maphost` | Sample application that displays a map from DiPlay | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:home` | Optional DiPlay Home launcher | `samples/home/build/outputs/apk/debug/home-debug.apk` |

The `:common` and `:shared` modules are libraries.
The applications use these libraries.

If the installed app is **DiPlay map host**, you selected `:maphost` or its APK.
Build `:mobile` and select `mobile-debug.apk` to install the main app.
A command such as `./gradlew assembleDebug` can build more than one application.
Use the module name in each build command to select the required application.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.0.0.
- Android SDK Platform-Tools.
- Rust 1.97.1 for core unit tests, JVM JNI tests, and cross-compiling the Android native library.
- Android NDK 28.2.13676358 for CI native-library validation and any local native rebuild. Ordinary Gradle APK builds still package the checked-in libraries.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.
ECARX adapters resolve the head unit's interfaces through reflection and dynamic proxies.
No ECARX compilation JAR, private Maven repository or vendor SDK download is needed for builds or tests.
The unused recovered GKUI Policy declaration module has been removed.
The test fixtures cannot catch a misspelled vendor method. To check the adapters' method names and signatures against a head-unit AdaptAPI JAR you keep outside the repository, add `-PecarxAdaptapiJar=<path>` to `:shared:testDebugUnitTest`. Without it, `EcarxAdaptapiAbiTest` is skipped.
See [the SDK context](ECARX_SDK_CONTEXT.zh-CN.md) for the runtime contract and remaining provenance limits.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

For a source build without runtime authentication assets:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-debug.apk
```

The debug application ID is `com.shihab.diplay.hudtest`.
The release application ID is `com.shihab.diplay`.

The source APK contains no accessory identity unless you supply runtime authentication assets.
Standalone CarPlay needs these assets to connect to an iPhone.
Use the car-test procedure below for that purpose.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Click **Run** to install the main debug app.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Check the source build

Run the Rust core unit tests, then the Android/JVM tests, lint checks, and main debug build:

```sh
cargo test --locked --lib --manifest-path shared/src/main/rust/diplay-media/Cargo.toml
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Check that Gradle reports `BUILD SUCCESSFUL`.
JVM test tasks build and load the host Rust JNI library with `cargo build --locked`; the separate
`cargo test` command executes Rust's own unit tests. Android APK builds package the checked-in
ARM64, ARMv7 and x86_64 libraries. Before testing a native-source change, cross-compile all three
Android ABIs and verify that the checked-in binaries match:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358" scripts/check_rust_android_native.sh
```

The check builds every library into a temporary output directory, validates ELF architecture,
the 494 Java JNI exports, `JNI_OnLoad`, and identical JNI export sets across ABIs. CI then stages
those freshly built libraries into `shared/src/main/jniLibs` before building the Debug and Release
APKs, and retains them plus the source revision as an Actions artifact. It does not require
byte-for-byte equality with the checked-in copies: build-host metadata can differ even when the
ABI and JNI contract is unchanged. Ordinary local Gradle builds still use the checked-in files,
so regenerate them with `build.sh` after changing Rust source.
Shared-only contracts that access internal core owners live in `shared/src/testCoreInternal/kotlin`
and are included only in shared JVM/Android tests. Common/home reuse the general test references and
real-host JNI shadows without compiling those internal contracts.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).
That workflow also checks and builds the `home` and `maphost` applications.

## Build a standalone car-test APK

Prepare an external directory with these runtime authentication files:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Use only the intended runtime files in this directory.
Keep the directory and its private key outside Git.
The build rejects unexpected credential containers in APK assets.

1. Set `DIPLAY_AUTH_ASSETS_DIR` to the absolute directory path.
2. Run the standalone debug task.

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/DiPlay-v<versionCode>-debug.apk`.
Check both files in the APK against your selected local inputs:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

Use this APK for a standalone connection test.
Update the existing test app with the same signing key to keep its settings.
Different signing keys cannot update the same installed application.

## Build a release APK

Release automation signs the APK without bundling MFi authentication files.
For a local build, set these signing variables and leave `DIPLAY_AUTH_ASSETS_DIR` unset:

| Variable | Value |
| --- | --- |
| `ANDROID_KEYSTORE_PATH` | Path to your Android signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore and passwords outside Git.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is one APK per shipped ABI under `mobile/build/outputs/apk/release/`:
`DiPlay-v<versionCode>-arm64-v8a-release.apk` (default production package),
`DiPlay-v<versionCode>-armeabi-v7a-release.apk` (fallback) and
`DiPlay-v<versionCode>-x86_64-release.apk` (emulator testing only). Each APK carries exactly one ABI.
Release builds enable R8 code and resource optimization. The main app disables name obfuscation and
retains `SourceFile`/`LineNumberTable` metadata because its diagnostics identify app frames by package
and report exception class names. Inlining, class merging and line-number remapping still require the
matching `mobile/build/outputs/mapping/release/mapping.txt` for accurate retracing; save it with each APK.
For example, run `retrace DiPlay-v146-mapping.txt crash.txt` with the SDK command-line tools.
The APK excludes the Android signing key.
An in-place update can reuse valid MFi files already installed in the app's private directory.
A fresh install or cleared app data needs separately provisioned authentication or a supported hardware authenticator.
For an explicit standalone local car-test build only, set `DIPLAY_AUTH_ASSETS_DIR` as described above.
That opt-in embeds the runtime identity in the APK, where recipients can extract it.

### GitHub Release automation

The Android checks workflow runs locked Rust core unit tests, cross-compiles all three Rust Android ABIs, validates their ELF/JNI contracts, and packages those fresh libraries into Debug and Release APKs on `dev`/`main` pushes and pull requests. It also verifies that each split Debug APK contains only its own Rust ABI.
Publishing a GitHub Release also runs those checks, then signs one Release APK per ABI and attaches
`DiPlay-v<versionCode>-arm64-v8a-release.apk`, `DiPlay-v<versionCode>-armeabi-v7a-release.apk`,
`DiPlay-v<versionCode>-x86_64-release.apk`, `DiPlay-v<versionCode>-mapping.txt` and `SHA256SUMS.txt` to that Release.
`update.json` carries an `apks` map so each device downloads the package built for its own ABI; the
top-level `apk` remains the ARM64 default.
The mapping is included in the checksums and retained as a Release asset beyond the Actions artifact retention period.
A manually dispatched run builds the selected ref
and uploads the same files as the `DiPlay-signed-release` Actions artifact without publishing a Release.

The released tag must include the updated `.github/workflows/android.yml`.
Creating a release branch, pushing a tag alone, or saving an unpublished draft does not publish an APK.
Increment `mobile`'s `versionCode` before each new version; the workflow uses the checked-out source value.
Version 146 supersedes the existing v145 vehicle package.

Configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded existing platform signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Existing signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Only the Release job uses the secrets, after the checks pass. It restores the keystore in the runner's temporary
directory, removes it after use, verifies the APK signature for Android 9, and rejects bundled MFi or key containers.
No MFi secrets are required or uploaded. Keep the same signing identity for updates and vehicle platform permissions.

The public source archive matches the release tag.
It excludes runtime identities, signing keys, local configuration, and build output.
The retired `build-beta.py` helper is no longer part of the build procedure.

APKs include GPL, AGPL, component license texts, copyright notices and source information in
`assets/licenses/`. In the main app, open **About → Licenses and source** to read them offline.
The Android release workflow attaches `DiPlay-licenses.zip`, `DiPlay-source.tar.gz`,
`SOURCE-REVISION.txt` and `SHA256SUMS.txt` beside the APK. The source archive is generated from the
same commit as the APK; the revision file identifies that commit. Manual Actions builds include
the same files in the `DiPlay-signed-release` artifact.

When distributing a locally modified build, provide the source actually used, including local
changes. Check the remaining native source correspondence limits in [the notices](THIRD_PARTY_NOTICES.md)
and [the native provenance record](../shared/src/main/jniLibs/NOTICE.md); attaching the repository
archive does not establish correspondence for extracted native binaries.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Installed app is DiPlay map host | Select `mobile` in Android Studio. Build and install the APK from the `mobile` output directory. |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Source APK cannot start standalone CarPlay | Supply runtime authentication assets. Use `:mobile:assembleStandaloneDebug`. |
| Standalone task reports missing authentication files | Check the external directory path and both required files. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |
