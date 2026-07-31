import java.util.Properties

/**
 * Release signing credentials, kept out of the build file and out of git —
 * `keystore.properties` and the `.jks` it points at are both gitignored.
 *
 * Absent on a fresh clone, which is deliberate: the release build then produces
 * an *unsigned* APK instead of failing, so anyone can still build and inspect
 * the project without holding the signing key. Only whoever has the keystore can
 * produce an installable release.
 *
 * Passwords fall back to environment variables so CI can sign without a file
 * on disk.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningKey = keystorePropertiesFile.exists() || System.getenv("SAFEWORLD_KEYSTORE") != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Lets `Json.encodeToString(settings)` resolve :core's serializers at
    // compile time instead of falling back to reflection under R8, and covers
    // the app's own @Serializable types (the GitHub release DTOs in
    // update/UpdateChecker.kt).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.safeworld.app"
    compileSdk = 35

    // The packet relay for full-tunnel mode. Native because under `0.0.0.0/0` every packet on the
    // device crosses it, where per-packet allocation in the JVM means GC pauses mid-call.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Only the ABIs a real device uses, plus x86_64 so the emulator can run it. Each adds its
        // own copy of the .so to a universal APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // The relay's parsing has to be tested where the .so actually is — on a device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.safeworld.app"
        // 26 (Android 8.0) is where VpnService behaves consistently enough to
        // rely on; see apps/android/README.md.
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = rootProject.file(
                    System.getenv("SAFEWORLD_KEYSTORE")
                        ?: keystoreProperties.getProperty("storeFile"),
                )
                storePassword = System.getenv("SAFEWORLD_KEYSTORE_PASSWORD")
                    ?: keystoreProperties.getProperty("storePassword")
                keyAlias = System.getenv("SAFEWORLD_KEY_ALIAS")
                    ?: keystoreProperties.getProperty("keyAlias")
                keyPassword = System.getenv("SAFEWORLD_KEY_PASSWORD")
                    ?: keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null without a keystore, which yields app-release-unsigned.apk
            // rather than a build failure — see the note at the top of the file.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    // On-device tests for the native relay: its parsing can only be verified where the .so is.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
