plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "dev.opencaller.app"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.opencaller.app"
    // CallScreeningService role exists since API 29 (Android 10) — PRD §7.
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"

    // Only ABIs we cross-compile the Rust core for (cargo ndk -t ...);
    // shipping other ABIs would crash on loadLibrary.
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  buildTypes {
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
  buildFeatures {
    compose = true
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.core:core-ktx:1.13.1")
}
