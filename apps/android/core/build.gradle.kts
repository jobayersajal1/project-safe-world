plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately a plain Kotlin/JVM module, not an Android library: nothing here
// touches the Android SDK, so `./gradlew :core:test` runs without an emulator —
// the same property that lets apps/ios/SafeWorldCore run under `swift test`.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
