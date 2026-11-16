import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// ADR 0020's first step (#577): the Core is a Kotlin Multiplatform module whose only target is the
// JVM Android uses. src/commonMain holds what compiles without the JVM; src/jvmMain holds what still
// needs it (java.time, OkHttp, Moshi, java.text...), until those are swapped one at a time.
// With one target Gradle never compiles commonMain on its own, so a JVM call there still builds. To
// check a file belongs in commonMain, add a second target for a moment (macosArm64() below jvm) and
// run ./gradlew :core:compileCommonMainKotlinMetadata.
kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        // The app runs this code on Fire OS 7 (Android 9), whose Java library is about Java 8's: a JDK 9+
        // method compiles here and then crashes the app on the stick (URLEncoder.encode(String, Charset) did,
        // #378). So the main code compiles against the Java 8 API; the tests run on the JDK and may use more.
        compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME) {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                    freeCompilerArgs.add("-Xjdk-release=1.8")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            // The HTTP client the Core reads the Provider and the Cloud with (#579); each platform gives it an engine.
            api(libs.ktor.client.core)
        }
        jvmMain.dependencies {
            // Android's engine: OkHttp, which the app's player and artwork read with too (OkHttpNetwork.kt).
            api(libs.okhttp)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.moshi)
            implementation(libs.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.coroutines.test)
        }
    }
}

// The hand-written Provider tables ship with the app, by host hash: a device uses its Provider's until
// the cloud has answered for it once, so a first launch on a new build looks the same (#431).
tasks.named<ProcessResources>("jvmProcessResources") {
    from(rootProject.file("cloud/provider-tables")) {
        include("*.txt")
        into("dev/betteriptv/core/provider-tables")
    }
}

tasks.named<Test>("jvmTest") {
    // Small on purpose: Sync must stream the catalog, never hold a whole JSON document in memory.
    maxHeapSize = "128m"
    // Four test JVMs, each with that heap: these tests were CI's critical path, seven minutes in one
    // JVM, most of it real waits (delays and timeouts) rather than work. The fakes bind ephemeral
    // ports and each test gets its own TemporaryFolder, so they don't meet.
    maxParallelForks = 4
    // ./gradlew :core:jvmTest --tests '*SurveyCoverageTest' -Psurvey=/path/to/.survey/raw
    providers.gradleProperty("survey").orNull?.let { systemProperty("survey", it) }
    providers.gradleProperty("surveyCloud").orNull?.let { systemProperty("surveyCloud", it) }
    // ./gradlew :core:jvmTest --tests '*SearchIndexBenchmark' -Pbenchmark
    // Its made-up catalog has more names than the real one (up to 40 translations a Title), so more heap.
    providers.gradleProperty("benchmark").orNull?.let {
        systemProperty("benchmark", "true")
        maxHeapSize = "512m"
    }
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
