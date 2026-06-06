import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baseline.profile)
}

// Keystore-Konfiguration aus externer Datei laden (NICHT im Git)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    println("keystore.properties GEFUNDEN und geladen!")
    println("storeFile = ${keystoreProperties.getProperty("storeFile")}")
    println("keyAlias = ${keystoreProperties.getProperty("keyAlias")}")
} else {
    println("WARNUNG: keystore.properties NICHT gefunden unter: ${keystorePropertiesFile.absolutePath}")
}

android {
    namespace = "com.messenger.crisix"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.messenger.crisix"
        minSdk = 30
        targetSdk = 36
        versionCode = 105
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_OWNER", "\"MachuraHarry\"")
        buildConfigField("String", "GITHUB_REPO", "\"Crisix\"")
        buildConfigField("String", "SENTRY_DSN", "\"${System.getenv("SENTRY_DSN") ?: ""}\"")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        // Pre-create nonMinifiedRelease with debug signing before the baseline-profile plugin
        // tries to create it with initWith(release) which would copy release's signing config
        register("nonMinifiedRelease") {
            initWith(buildTypes.getByName("release"))
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Timber Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Bouncy Castle for Ed25519 crypto (libp2p compatible)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.77")

    // MessagePack for Hyperswarm-compatible serialization
    implementation("org.msgpack:msgpack-core:0.9.8")

    // CameraX for QR code scanning
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    // ZXing (Signal-Style)
    implementation("com.google.zxing:core:3.5.3")

    // Room database
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("androidx.room:room-paging:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Paging 3
    implementation("androidx.paging:paging-runtime:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    // OkHttp for WebSocket relay
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // Markdown rendering for chat messages
    implementation(libs.markdown.renderer.m3)
    
    // Google ML Kit (als Fallback/Alternative im Code behalten)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // Encrypted SharedPreferences (Google Security/TINK)
    // Für E2EE-Session-Verschlüsselung
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Baseline Profile Installer (AOT compilation for smooth scrolling)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    "baselineProfile"(project(":macrobenchmark"))

    // Sentry Crash Reporting
    implementation(libs.sentry.android)
    implementation(libs.sentry.android.timber)

    // WorkManager (Background cleanup)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.3")

    // Biometric for app lock
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.json:json:20231013")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
