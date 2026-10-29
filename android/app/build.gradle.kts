import groovy.json.JsonSlurper

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Portal credentials come from the web player's config.json (gitignored), so both apps share one config.
@Suppress("UNCHECKED_CAST")
val portalConfig: Map<String, Any?> = rootProject.file("../config.json").let { f ->
  if (f.exists()) JsonSlurper().parse(f) as Map<String, Any?> else emptyMap()
}

fun cfg(key: String, default: String = "") =
  "\"" + (portalConfig[key] ?: default).toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "com.iptv.tv"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.iptv.tv"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
    buildConfigField("String", "PORTAL", cfg("portal"))
    buildConfigField("String", "MAC", cfg("mac"))
    buildConfigField("String", "TIMEZONE", cfg("timezone", "Europe/London"))
    buildConfigField("String", "STB_MODEL", cfg("stbModel", "MAG250"))
    buildConfigField("String", "SERIAL", cfg("serialNumber"))
    buildConfigField("String", "DEVICE_ID", cfg("deviceId"))
    buildConfigField("String", "DEVICE_ID2", cfg("deviceId2"))
    buildConfigField("String", "SIGNATURE", cfg("signature"))
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.tv.material)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.exoplayer.hls)
  implementation(libs.media3.ui)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
}
