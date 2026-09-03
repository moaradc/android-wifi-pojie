import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
    id("com.mikepenz.aboutlibraries.plugin")
    id("com.google.devtools.ksp") version "2.3.4"
}

android {
    namespace = "com.wifi.toolbox"
    compileSdk {
        version = release(36)
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.wifi.toolbox"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion 注:WifiManager需要
        targetSdk = 28
        versionCode = 7
        versionName = "v3.0.0_Alpha-001"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildTime = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val buildNumber = getAndIncrementBuildNumber()
        val gitId = getGitCommitId()

        buildConfigField("String", "BUILD_DATE", "\"$buildTime\"")
        buildConfigField("String", "BUILD_COUNT", "\"${buildNumber}\"")
        buildConfigField("String", "GIT_ID", "\"$gitId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // R8 代码裁剪 + 资源裁剪：移除未引用资源（配合 res/raw/keep.xml
            // 保留 aboutlibraries 等经 getIdentifier 动态查找的资源）
            isShrinkResources = true
            manifestPlaceholders["shizukuAuthority"] = "com.wifi.toolbox.shizuku"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["shizukuAuthority"] = "com.wifi.toolbox.debug.shizuku"
        }

        buildFeatures {
            buildConfig = true
            aidl = true
        }
    }

    packaging {
        resources {
            // 小幅瘦身（实测约 1.4MB）：
            // - tables/**：desugar_jdk_libs 2.x 携带的旧字符集转码表（Big5/GBK/
            //   EUC-JP/EUC-KR 等，675 文件未压缩 2.88MB）——本应用全部 IO 均
            //   为 UTF-8（密码字典/JS 脚本/日志/命令输出），不触达旧字符集转换；
            // - src/**：第三方库误打包的 .java 源文件（运行时永不加载）；
            // - kotlin_builtins：仅 kotlin-reflect 反射使用，本应用无该依赖
            excludes += listOf(
                "tables/**",
                "src/**",
                "kotlin/*.kotlin_builtins",
                "kotlin/**/*.kotlin_builtins"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.appcompat)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.api)
    implementation(libs.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom.v20251200))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.preference)
    implementation(libs.androidx.compose.runtime.annotation)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.materialKolor)
    implementation(libs.miuix)
    implementation(libs.coil.compose)
    implementation(libs.hiddenapibypass)
    implementation(libs.tinypinyin) //注:汉字 SSID 拼音首字母分组（A-Z 索引栏）

    implementation(platform(libs.editor.bom))
    implementation(libs.editor)
    implementation(libs.languageTextmate)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)

    implementation(libs.play.services.location) //注:依赖play服务，打开系统定位（也许有点臃肿，算了不管了）
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.core)
    implementation(libs.service)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.nio)
}

afterEvaluate {
    // 签名瘦身：minSdk 24 起系统仅校验 v2/v3 签名，v1 签名块（META-INF/
    // MANIFEST.MF/.SF/.RSA，实测约 280KB）在现有 APK 中本就未被校验——
    // 属死重，显式关闭不再生成。CI 正式签名（注入属性生成）与本地构建同走此处
    android.buildTypes.getByName("release").signingConfig?.enableV1Signing = false
}

fun getAndIncrementBuildNumber(): Int {
    val buildPropsFile = file("build.properties")
    val props = Properties()

    if (buildPropsFile.exists()) {
        buildPropsFile.inputStream().use { props.load(it) }
    }

    val currentNumber = props.getProperty("BUILD_COUNT", "0").toInt()
    val nextNumber = currentNumber + 1

    props.setProperty("BUILD_COUNT", nextNumber.toString())
    buildPropsFile.outputStream().use { props.store(it, null) }

    return nextNumber
}

fun getGitCommitId(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text.ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}