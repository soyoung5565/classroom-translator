plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.classroom.translator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.classroom.translator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Read MiniMax-M3 API key from local.properties (not committed)
        // gradleProperty 只读 Gradle 属性(不含 local.properties!) —— 之前编译进去的是 "null"
        // 双路读取: -P 传参优先, 否则从 local.properties 读 (2026-09-22 修复)
        val lpProps = java.util.Properties().apply {
            val lp = rootProject.file("local.properties")
            if (lp.exists()) lp.inputStream().use { load(it) }
        }
        val minimaxKey = providers.gradleProperty("MINIMAX_CN_API_KEY").orNull
            ?: lpProps.getProperty("MINIMAX_CN_API_KEY")
        buildConfigField("String", "MINIMAX_API_KEY", "\"$minimaxKey\"")
        buildConfigField("String", "MINIMAX_BASE_URL", "\"https://api.minimaxi.com/anthropic\"")
        buildConfigField("String", "MINIMAX_MODEL", "\"MiniMax-M3\"")
    }

    buildFeatures {
        buildConfig = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // OkHttp + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // sherpa-onnx on-device ASR (offline mode, P2)
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
}
