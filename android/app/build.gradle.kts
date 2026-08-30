import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives OUTSIDE the repo: android/keystore.properties
// (gitignored) points at the keystore. CI reconstructs both from secrets.
// Without it, release builds are unsigned (debug builds unaffected).
val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

android {
  namespace = "dev.opencaller.app"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.opencaller.app"
    // CallScreeningService role exists since API 29 (Android 10) — PRD §7.
    minSdk = 29
    targetSdk = 35
    versionCode = 3
    versionName = "0.2.1"

    // Only ABIs we cross-compile the Rust core for (cargo ndk -t ...);
    // shipping other ABIs would crash on loadLibrary.
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  signingConfigs {
    create("release") {
      if (keystoreProps.isNotEmpty()) {
        storeFile = file(keystoreProps["storeFile"] as String)
        storePassword = keystoreProps["storePassword"] as String
        keyAlias = keystoreProps["keyAlias"] as String
        keyPassword = keystoreProps["keyPassword"] as String
      }
    }
  }

  buildTypes {
    debug {
      // Sign debug builds with the release key when available so field
      // testers can switch debug<->release without uninstalling
      // (per-machine auto debug keys would break upgrades otherwise).
      if (keystoreProps.isNotEmpty()) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      if (keystoreProps.isNotEmpty()) {
        signingConfig = signingConfigs.getByName("release")
      }
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
    buildConfig = true // debug-only test hooks gate on BuildConfig.DEBUG
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
}
