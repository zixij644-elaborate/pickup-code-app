plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 读取本地签名配置（keystore.properties 已 .gitignore，不进仓库)
import java.util.Properties

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

fun String?.orEnv(name: String): String? = this?.ifBlank { null } ?: System.getenv(name)

// 标记 release 签名是否可用（有 keystore.properties 且文件存在）
var storeFileConfigured = false

android {
    namespace = "com.pickupcode.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pickupcode.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.0.9"
    }

    signingConfigs {
        create("release") {
            val cfgStoreFile = keystoreProperties.getProperty("STORE_FILE")?.orEnv("PICKUP_STORE_FILE")
            val cfgStorePw = keystoreProperties.getProperty("STORE_PASSWORD")?.orEnv("PICKUP_STORE_PASSWORD")
            val cfgKeyAlias = keystoreProperties.getProperty("KEY_ALIAS")?.orEnv("PICKUP_KEY_ALIAS")
            val cfgKeyPw = keystoreProperties.getProperty("KEY_PASSWORD")?.orEnv("PICKUP_KEY_PASSWORD")
            // 签名文件存在且有密码才配置（否则 release 走 unsigned，避免 CI/无密钥环境炸构建）
            if (cfgStoreFile != null && File(cfgStoreFile).exists() && !cfgStorePw.isNullOrEmpty()) {
                storeFile = file(cfgStoreFile)
                storePassword = cfgStorePw
                keyAlias = cfgKeyAlias ?: "pickup"
                keyPassword = cfgKeyPw ?: cfgStorePw
                storeFileConfigured = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (storeFileConfigured) signingConfigs.getByName("release") else null
        }
    }

    // 按 CPU 架构拆分：只打真机常用的 arm64/armv7，砍掉 x86 等用不上的原生库，显著减重
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
            // 注：AGP 8.x 已移除 splits.abi.versionCodes（3.x/4.x 旧 API）。当前按架构拆包、
            // 各分包共用默认 versionCode，适合侧载分发；若需上架 Play，改用 App Bundle（AAB）由 Play 自动分发。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room schema 导出目录：配合 @Database(exportSchema = true) 生成版本化 schema JSON，
// 让 Room 能校验迁移链正确性（提交到仓库，配合 MigrationTestHelper 做迁移测试）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ML Kit Text Recognition (offline, free)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // ML Kit Barcode Scanning (bundled, offline, detects+decodes QR/barcode)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")


    // Room for history storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Kotlin coroutines + play-services (for Task.await)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Core KTX
    implementation("androidx.core:core-ktx:1.15.0")

    // Tests (JUnit 5 — 纯 Kotlin 单元测试，无 Android 依赖)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}
