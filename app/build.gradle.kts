import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.betteriptv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.betteriptv"
        // Fire OS 6 (Android 7.1) and later.
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // The cloud (cloud/) the app gets Title metadata from, set in gradle.properties.
        buildConfigField("String", "CLOUD_URL", "\"${providers.gradleProperty("cloudUrl").get()}\"")
    }

    signingConfigs {
        // Checked in so builds from CI and from any machine can replace each other on the stick.
        // A debug key is not a secret; release signing comes with the in-app updater (#21).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // java.time (used by the core) needs API 26; desugaring brings it to Fire OS 6.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
