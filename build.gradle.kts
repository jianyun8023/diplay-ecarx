// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

apply(from = "gradle/native-core-tests.gradle")
// Default shipped ABI: ARM64 (arm64-v8a) only. ARMv7 (fallback) and x86_64 (emulator) are no
// longer built by default; request them explicitly with
// `-PdiplayAbis=arm64-v8a,armeabi-v7a,x86_64` when needed. The sample apps keep the same
// filters so their native libraries are not stripped.
val diplayAbis: List<String> = (providers.gradleProperty("diplayAbis").orNull ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            defaultConfig.ndk.abiFilters += diplayAbis.toSet()
            buildTypes.named("release") {
                optimization {
                    enable = true
                }
            }
        }
    }
}
