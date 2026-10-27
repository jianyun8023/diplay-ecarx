import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    if (name in listOf("shared", "common")) {
        val testList = layout.projectDirectory.file("src/test/lynk-android9-tests.txt")
        val patterns = testList.asFile.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        require(patterns.isNotEmpty()) { "Empty Lynk test list: $testList" }
        tasks.withType<Test>().configureEach {
            inputs.file(testList)
            filter { patterns.forEach { includeTestsMatching(it) } }
            // Method-level tests for other Android versions stay in source, outside this vehicle run.
            systemProperty("robolectric.enabledSdks", "28")
            maxParallelForks = 1
        }
    }
}
