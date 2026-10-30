import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** The commits on HEAD: each release's versionCode. CI fetches the whole history for it. */
val commitCount: Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()

android {
    namespace = "dev.betteriptv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.betteriptv"
        // Fire OS 6 (Android 7.1) and later.
        minSdk = 25
        targetSdk = 34
        // Every push to main is a release (#21): the commits on main count builds, so each one is newer
        // than the last and a local build of the same commit gets the same number.
        versionCode = commitCount
        versionName = "0.1.$commitCount"
        // The cloud (cloud/) the app gets Title metadata from, set in gradle.properties.
        buildConfigField("String", "CLOUD_URL", "\"${providers.gradleProperty("cloudUrl").get()}\"")
        // The update manifest release builds check on launch (#21), set in gradle.properties; empty turns the check off.
        buildConfigField("String", "UPDATE_URL", "\"${providers.gradleProperty("updateUrl").get()}\"")
        // The VLC engine's native code, for ARM only: the Fire TV Stick, and the emulator on an Apple-silicon Mac.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // Checked in so builds from CI and from any machine can replace each other on the stick.
        // A debug key is not a secret, so debug builds are a separate app (see buildTypes).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The release key only the Viewer holds, given to CI as secrets (docs/releases.md). Without
        // it, release builds come out unsigned.
        val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        // Signed with the public debug key, so a separate app beside the released one: an adb
        // install never clashes with the release key, and never takes the release's data.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "UPDATE_URL", "\"\"")
        }
        // What CI publishes and the in-app updater installs.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release-like for measuring on the stick (#33): R8-minified and not debuggable, but signed
        // with the checked-in debug key so it installs over the debug build and keeps its data.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            buildConfigField("String", "UPDATE_URL", "\"\"")
            matchingFallbacks += "release"
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

    // Robolectric tests read the app's strings, in English and French.
    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressed native code (libVLC is most of it) halves the APK sideloaded over Wi-Fi; the
        // installer unpacks only the stick's own ABI.
        jniLibs.useLegacyPackaging = true
        // libVLC and libass each bring the C++ runtime, but only libVLC links it: keep libVLC's copy.
        jniLibs.pickFirsts += "lib/*/libc++_shared.so"
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // A Channel's Backup is its stream as HLS.
    implementation(libs.media3.hls)
    // DTS and TrueHD audio, which Fire sticks can't decode, and ASS subtitles with their full styling.
    implementation(libs.media3.ffmpeg)
    implementation(libs.ass.media)
    implementation(libs.libvlc)

    testImplementation(libs.junit)
    // A real android.net.Uri for the Media3 classes the player's policies are tested with.
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
