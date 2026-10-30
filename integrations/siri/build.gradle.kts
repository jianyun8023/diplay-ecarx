plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shihab.diplay.siri"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.shihab.diplay.siri"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file(providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .getOrElse("missing-release-keystore.jks"))
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
