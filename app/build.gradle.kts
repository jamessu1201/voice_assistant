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
        buildConfig = true  // 👈 重要：啟用 BuildConfig
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
}