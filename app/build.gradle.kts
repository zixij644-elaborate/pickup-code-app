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
        // 版本史（详见 .scratch/project-review.md 与 RELEASE_NOTES_*.md）：
        //   24 / 1.0.9  —— 与已发布版同号，导致含 DB v7 迁移的修复发不出去
        //   25 / 1.0.10 —— 首次可下发的升级安全修复版（DB v7 迁移 + 截图治理 + 识别准确率/隐私/竞态修复）
        //   26 / 1.1.0  —— 功能版本：常用取件地址（预存地址）、身份码一键跳转、身份码页面拒采
        // ⚠️ 打 tag 时必须让 tag 落在版本号 bump 的提交（或其之后）上 —— v1.0.9 出现过 tag 与产物错位；
        //    release.yml 现已加"APK versionName == tag"校验来拦截这类失误。
        versionCode = 26
        versionName = "1.1.0"
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
        unitTests.all {
            it.useJUnitPlatform()
            // 让 corpus 指标（println）与失败详情出现在 gradle 控制台，无需翻 HTML 报告
            it.testLogging {
                events("failed", "skipped")
                showStandardStreams = true
            }
        }
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

    // EXIF 旋转（ImageUtils.decodeSampledBitmap 用 ExifInterface.rotationDegrees）。
    // 该库由 ML Kit 的 vision-common 传递引入，并被其 strictly 锁在 1.0.0（升级会与 ML Kit 约束冲突）；
    // 显式声明是为了避免将来 ML Kit 移除这条传递依赖时，分享图片的 EXIF 旋转编译失败。
    implementation("androidx.exifinterface:exifinterface:1.0.0")


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

    // Baseline Profile 安装器：把 APK 里打包的 baseline profile 交给系统做 AOT 编译。
    // - Android 12+（API 31+）系统在安装时自行处理；Android 12 以下必须由本库在首次启动时接手，
    //   本项目 minSdk=26，所以这条链不能断。
    // - 实测背景：release 包 status=speed-profile 时冷启动首滑卡顿帧 0.7%，
    //   而 debug 包 status=verify（ART 不允许 debuggable 包 AOT）时是 34.6%。
    // - 注意：本库此前已被其它 androidx 库**传递引入**（1.3.1）。这里显式声明只是为了
    //   声明自己真正依赖它并钉住版本（升到 1.4.1），并不是新增能力——别误以为是它修了卡顿。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Tests (JUnit 5 — 纯 Kotlin 单元测试，无 Android 依赖)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    // 真 JSON 实现：android.jar 里的 org.json 在 JVM 单测中是桩（方法返回 null），
    // 无法断言请求体结构/返回解析 —— 补一个纯 JVM 实现供 AIExtractorTest 使用（不进 APK）。
    testImplementation("org.json:json:20240303")
}
