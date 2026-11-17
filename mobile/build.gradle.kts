plugins {
    alias(libs.plugins.android.application)
}

// Optional local-only inputs. An environment variable wins; otherwise a git-ignored `.env` at the
// repository root supplies the same names for local signing and MFi identity builds. CI has neither,
// so ordinary source builds still contain no keys and no accessory identity.
val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.let { env ->
    env.readLines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val split = line.indexOf('=')
        if (split <= 0) return@mapNotNull null
        val value = line.substring(split + 1).trim()
            .removeSurrounding("\"").removeSurrounding("'")
            .replace("\${HOME}", System.getProperty("user.home"))
        line.substring(0, split).trim() to value
    }.toMap()
} ?: emptyMap()

fun localInput(name: String): String? =
    providers.environmentVariable(name).orNull ?: dotEnv[name]?.takeIf { it.isNotEmpty() }

val localAuthenticationAssets = localInput("DIPLAY_AUTH_ASSETS_DIR")
    ?.let { file(it.replaceFirst("~", System.getProperty("user.home"))).canonicalFile }

// Default ships ARM64 only; request others with -PdiplayAbis=arm64-v8a,armeabi-v7a,x86_64.
val diplayAbis: List<String> = (providers.gradleProperty("diplayAbis").orNull ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.shihab.diplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 24
        targetSdk = 37
        // ECARX release sequence; upstream baseline is v0.2.22.
        versionCode = 151
        versionName = "0.2"
        testInstrumentationRunner = "com.shihab.diplay.BluetoothMediaProbeInstrumentation"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    // Default ships a single ARM64 APK. ARMv7 (fallback) and x86_64 (emulator) are opt-in via
    // -PdiplayAbis=arm64-v8a,armeabi-v7a,x86_64; each split APK carries exactly one native
    // ABI, so the in-app updater selects the package that matches the device's own ABI.
    // splits.abi owns the ABI selection here, so the inherited ndk.abiFilters must be cleared first.
    defaultConfig.ndk.abiFilters.clear()
    splits {
        abi {
            isEnable = true
            reset()
            include(*diplayAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                localInput("ANDROID_KEYSTORE_PATH")
                    .orEmpty().ifEmpty { "missing-release-keystore.jks" },
            )
            storePassword = localInput("ANDROID_KEYSTORE_PASSWORD").orEmpty()
            keyAlias = localInput("ANDROID_KEY_ALIAS").orEmpty()
            keyPassword = localInput("ANDROID_KEY_PASSWORD").orEmpty()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = providers.gradleProperty("carTestLabel")
                .map { "-hud-test-$it" }.getOrElse("-hud-test")
        }
        release {
            if (providers.gradleProperty("carTestLabel").isPresent) {
                versionNameSuffix = "-car-test-${providers.gradleProperty("carTestLabel").get()}"
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// APKs are named DiPlay-v<versionCode>-<buildType>.apk, the name the GitHub Release carries.
base.archivesName = "DiPlay-v${android.defaultConfig.versionCode}"

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a release-package APK with explicitly provisioned authentication, for a first install " +
        "that public GitHub releases then upgrade."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}
