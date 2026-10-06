import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Default API keys are shared with the macOS app (../Resources/secrets.json) and seeded on first launch.
val secretsFile = rootProject.file("../Resources/secrets.json")
val secrets: Map<*, *> = if (secretsFile.exists()) JsonSlurper().parse(secretsFile) as Map<*, *> else emptyMap<String, String>()
val appVersion = rootProject.file("../VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "1.0.0"

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
        buildConfigField("String", "DEEPSEEK_KEY", "\"${secrets["deepseekKey"] ?: ""}\"")
        buildConfigField("String", "MIMO_KEY", "\"${secrets["mimoKey"] ?: ""}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // No Play Store: sign release builds with the debug key so the APK installs anywhere.
            signingConfig = signingConfigs.getByName("debug")
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
