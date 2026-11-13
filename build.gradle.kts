// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

apply(from = "gradle/native-core-tests.gradle")
// Shipped ABIs: ARM64 (default production package), ARMv7 (fallback) and x86_64 (test-only, emulator).
// The mobile app splits these into one APK each; the sample apps keep the same filters so their
// native libraries are not stripped.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            defaultConfig.ndk.abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
            buildTypes.named("release") {
                optimization {
                    enable = true
                }
            }
        }
    }
}
