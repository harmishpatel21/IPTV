import groovy.json.JsonSlurper

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Debug builds pick up the web player's config.json (gitignored) so your own TV needs no setup.
// Release builds never contain an account: the app asks for it on first launch, so one APK can be shared.
@Suppress("UNCHECKED_CAST")
val portalConfig: Map<String, Any?> = rootProject.file("../config.json").let { f ->
  if (f.exists()) JsonSlurper().parse(f) as Map<String, Any?> else emptyMap()
}

fun cfg(key: String) = "\"" + (portalConfig[key] ?: "").toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val portalFields = mapOf(
  "PORTAL" to "portal", "MAC" to "mac", "TIMEZONE" to "timezone", "STB_MODEL" to "stbModel", "SERIAL" to "serialNumber",
  "DEVICE_ID" to "deviceId", "DEVICE_ID2" to "deviceId2", "SIGNATURE" to "signature",
)

android {
  namespace = "com.iptv.tv"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.iptv.tv"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "0.2"
  }

  // Release signing: a real keystore from env vars (CI secrets) when provided, else the local debug key.
  // Android only installs an update signed with the same key, so published releases should use the real one.
  val releaseKeystore = System.getenv("RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
  signingConfigs {
    if (releaseKeystore != null) create("release") {
      storeFile = releaseKeystore
      storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
      keyAlias = System.getenv("RELEASE_KEY_ALIAS")
      keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    }
  }

  buildTypes {
    debug {
      portalFields.forEach { (field, key) -> buildConfigField("String", field, cfg(key)) }
    }
    release {
      portalFields.keys.forEach { field -> buildConfigField("String", field, "\"\"") }
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
