// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

apply(from = "gradle/native-core-tests.gradle")
// Vehicle packages support ARM only, including native libraries from dependencies.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            defaultConfig.ndk.abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            buildTypes.named("release") {
                optimization {
                    enable = true
                }
            }
        }
    }
}
