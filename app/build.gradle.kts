// App build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")  // Firebase — must be LAST
}

android {
    namespace = "com.example.groqtranscriber"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.groqtranscriber"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Uses the stable keystore passed in via environment variables from
        // GitHub Actions secrets (see .github/workflows/compile.yml).
        // Falls back gracefully to the default debug keystore when building
        // locally without those env vars set (e.g. in Android Studio).
        create("stableDebug") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            if (keystorePath != null && keystorePassword != null &&
                keyAlias != null && keyPassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
            // If env vars are absent (local build), Gradle falls back to the
            // default debug signing behaviour automatically.
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Networking & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Encrypted local storage (Android Keystore-backed) for the kie.ai API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── Firebase ──────────────────────────────────────────────────────────────
    // BOM manages all Firebase library versions so they stay in sync.
    // Only add libraries actually used — don't pull in the whole SDK.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")   // Realtime Database
    implementation("com.google.firebase:firebase-storage-ktx")    // Storage (TTS audio upload)
    implementation("com.google.firebase:firebase-auth-ktx")       // Authentication (email/password + Google)
    implementation("com.google.android.gms:play-services-auth:21.2.0")  // Google Sign-In
}
