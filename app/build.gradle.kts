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
        versionCode = 6
        versionName = "1.4.1"

        // Read MiniMax-M3 API key from local.properties (not committed)
        // gradleProperty 只读 Gradle 属性(不含 local.properties!) —— 之前编译进去的是 "null"
        // 双路读取: -P 传参优先, 否则从 local.properties 读 (2026-09-22 修复)
        // (kts 里 java.util.Properties 的 java 会解析成 Java 插件扩展, 直接手写解析)
        val lp = rootProject.file("local.properties")
        val lpKey = if (lp.exists()) {
            lp.readLines()
                .mapNotNull { line -> val i = line.indexOf('='); if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim() }
                .toMap()["MINIMAX_CN_API_KEY"]
        } else null
        val minimaxKey = providers.gradleProperty("MINIMAX_CN_API_KEY").orNull ?: lpKey
        buildConfigField("String", "MINIMAX_API_KEY", "\"$minimaxKey\"")
        buildConfigField("String", "MINIMAX_BASE_URL", "\"https://api.minimaxi.com/anthropic\"")
        buildConfigField("String", "MINIMAX_MODEL", "\"MiniMax-M3\"")

        // Volcengine ARK Agent Plan key (同一个 local.properties 读取方式) — 2026-09-26 大模型切火山
        val lpVolc = if (lp.exists()) {
            lp.readLines()
                .mapNotNull { line -> val i = line.indexOf('='); if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim() }
                .toMap()["VOLC_API_KEY"]
        } else null
        val volcKey = providers.gradleProperty("VOLC_API_KEY").orNull ?: lpVolc
        buildConfigField("String", "VOLC_API_KEY", "\"$volcKey\"")
        buildConfigField("String", "VOLC_BASE_URL", "\"https://ark.cn-beijing.volces.com/api/plan/v3\"")
        buildConfigField("String", "VOLC_MODEL", "\"deepseek-v4-1-flash\"")
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

    // [手机自足] llama-server 以 lib*.so 形式放进 jniLibs，必须解压到 nativeLibraryDir 才能被 App 执行
    // （Android 10+ 禁止执行 App 可写目录中的文件，nativeLibraryDir 只读且 SELinux 允许执行）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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

    // JVM 单元测试（课堂总结 JSON 解析加固回归）
    testImplementation("junit:junit:4.13.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // sherpa-onnx on-device ASR (offline mode, P2)
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
}
