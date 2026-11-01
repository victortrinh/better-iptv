import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app's Baseline Profile rules and the browse Macrobenchmarks (#63), run on a device or
// emulator, never in CI: see docs/releases.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.betteriptv.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Fire OS 7 (Android 9), the stick's.
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

baselineProfile {
    // The connected device or emulator (Android 13+, or rooted) generates the profile.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
