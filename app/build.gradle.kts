import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jamessu.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "jamessu.voiceassistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Spotify redirect URI
        manifestPlaceholders["redirectSchemeName"] = "voiceassistant"
        manifestPlaceholders["redirectHostName"] = "callback"

        // 👇 從 local.properties 讀取環境變數
        val localPropertiesFile = rootProject.file("local.properties")
        val properties = Properties()

        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }

        // 讀取 Access Key
        val porcupineAccessKey = properties.getProperty("PORCUPINE_ACCESS_KEY")
            ?: "YOUR_ACCESS_KEY_HERE"
        buildConfigField("String", "PORCUPINE_ACCESS_KEY", "\"$porcupineAccessKey\"")

        // 讀取 Server URL
        val serverUrl = properties.getProperty("SERVER_URL")
            ?: "http://10.0.2.2:8080"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")

        val clientId = properties.getProperty("CLIENT_ID")
            ?: "YOUR_CLIENT_ID_HERE"
        buildConfigField("String", "CLIENT_ID", "\"$clientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Porcupine
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // Spotify SDK
    implementation("com.spotify.android:auth:2.1.1")

    // Spotify App Remote - 改成你下載的檔案名稱
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

    // Gson - Required by Spotify SDK  👈 加這行
    implementation("com.google.code.gson:gson:2.10.1")
}