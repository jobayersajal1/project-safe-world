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

    // Forwarded to the forked test JVM. Gradle's own `-D` only reaches the
    // daemon, so without this `FeedParserCorpusTest` silently skips itself and
    // reports success — which is how a cross-platform check quietly stops
    // checking. Absent by default, keeping the normal run offline.
    System.getProperty("safeworld.feedCache")?.let {
        systemProperty("safeworld.feedCache", it)
    }
}
