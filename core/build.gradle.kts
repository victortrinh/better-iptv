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

dependencies {
    api(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.sqlite.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    // Small on purpose: Sync must stream the catalog, never hold a whole JSON document in memory.
    maxHeapSize = "128m"
    // ./gradlew :core:test --tests '*SurveyCoverageTest' -Psurvey=/path/to/.survey/raw
    providers.gradleProperty("survey").orNull?.let { systemProperty("survey", it) }
    // ./gradlew :core:test --tests '*SearchIndexBenchmark' -Pbenchmark
    providers.gradleProperty("benchmark").orNull?.let { systemProperty("benchmark", "true") }
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
