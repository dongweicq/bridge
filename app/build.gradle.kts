import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 获取版本信息：优先使用环境变量，否则尝试 git tag，最后使用默认值
fun getVersionInfo(): Pair<Int, String> {
    // 优先从环境变量获取（GitHub Actions 构建）
    val envVersionName = System.getenv("APP_VERSION_NAME")
    val envVersionCode = System.getenv("APP_VERSION_CODE")

    if (!envVersionName.isNullOrEmpty() && !envVersionCode.isNullOrEmpty()) {
        val versionCode = envVersionCode.toIntOrNull() ?: 1
        return Pair(versionCode, envVersionName)
    }

    // 尝试从 git tag 获取
    try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)

        if (output.startsWith("v")) {
            // 解析 v1.0.36 格式
            val parts = output.removePrefix("v").split(".")
            if (parts.size >= 3) {
                val major = parts[0].toIntOrNull() ?: 1
                val minor = parts[1].toIntOrNull() ?: 0
                val patch = parts[2].toIntOrNull() ?: 0
                val versionCode = major * 10000 + minor * 100 + patch
                val versionName = "$major.$minor.$patch"
                return Pair(versionCode, versionName)
            }
        }
    } catch (e: Exception) {
        // 忽略错误，使用默认值
    }

    // 默认值（本地开发）
    return Pair(1, "1.0.0-dev")
}

val (appVersionCode, appVersionName) = getVersionInfo()

android {
    namespace = "com.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bridge"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // NanoHTTPD - 轻量级 HTTP 服务器
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
}
