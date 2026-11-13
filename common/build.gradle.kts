plugins {
    id("com.android.library")
}

val mockitoAgent by configurations.creating {
    isTransitive = false
}

android {
    namespace = "com.shihab.diplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes. 1g sat right at the
        // ceiling: adding one more Robolectric class pushed a full run into OutOfMemoryError in
        // unrelated tests, on CI and locally both, which is what BydSettingsDecoyTest measured.
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    val mockito = "org.mockito:mockito-core:5.20.0"
    testImplementation(mockito)
    mockitoAgent(mockito)
    testImplementation(libs.jmdns)
}

abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val agentJar: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-javaagent:${agentJar.singleFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    // Load instrumentation at startup; inline mocks must not rely on dynamic JVM attachment.
    jvmArgumentProviders.add(objects.newInstance<MockitoAgentProvider>().apply {
        agentJar.from(mockitoAgent)
    })
}
