import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// The app runs this code on Fire OS 7 (Android 9), whose Java library is about Java 8's: a JDK 9+
// method compiles here and then crashes the app on the stick (URLEncoder.encode(String, Charset) did,
// #378). So the main code compiles against the Java 8 API; the tests run on the JDK and may use more.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}
tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
}

dependencies {
    api(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.sqlite.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// The hand-written Provider tables ship with the app, by host hash, until the cloud serves them (#431).
tasks.processResources {
    from(rootProject.file("cloud/provider-tables")) {
        include("*.txt")
        into("dev/betteriptv/core/provider-tables")
    }
}

tasks.test {
    // Small on purpose: Sync must stream the catalog, never hold a whole JSON document in memory.
    maxHeapSize = "128m"
    // ./gradlew :core:test --tests '*SurveyCoverageTest' -Psurvey=/path/to/.survey/raw
    providers.gradleProperty("survey").orNull?.let { systemProperty("survey", it) }
    providers.gradleProperty("surveyCloud").orNull?.let { systemProperty("surveyCloud", it) }
    // ./gradlew :core:test --tests '*SearchIndexBenchmark' -Pbenchmark
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
