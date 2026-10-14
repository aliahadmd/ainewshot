import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// No API keys are built into the app: AI and voice go through the private Cloudflare Worker (../cloud),
// which you reach by signing in with Cloudflare Access.
val appVersion = rootProject.file("../VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "1.0.0"

// Dedicated signing key (mobile/signing). Published APKs additionally carry a rotation lineage from the old
// debug key — see scripts/release.sh — so installs of <= 2.1.0 update in place.
val signingProps = Properties().apply {
    rootProject.file("signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.aliahad.ainewshot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.aliahad.ainewshot"
        minSdk = 29
        targetSdk = 36
        // 2.0.0 -> 20000: monotonic, so newer APKs always install over older ones.
        versionCode = appVersion.split(".").map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }.let { it[0] * 10000 + it[1] * 100 + it[2] }
        versionName = appVersion
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file("signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Debug and release share the dedicated key, so either can be installed over the other.
        val key = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = key
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = key
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
