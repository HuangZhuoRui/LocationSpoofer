@file:Suppress("DEPRECATION")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.suseoaa.locationspoofer"
    compileSdk = 37

    fun getLocalConfig(key: String): String? {
        val localYml = file("../local.yml")
        if (localYml.exists()) {
            val line = localYml.readLines().find { it.startsWith("$key:") }
            if (line != null) {
                return line.substringAfter(":").trim().removeSurrounding("\"")
                    .removeSurrounding("'")
            }
        }
        return null
    }

    val googleMapsApiKey =
        System.getenv("GOOGLE_MAPS_API_KEY") ?: getLocalConfig("GOOGLE_MAPS_API_KEY") ?: ""

    defaultConfig {
        applicationId = "com.suseoaa.locationspoofer"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("APP_VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").get()

        vectorDrawables {
            useSupportLibrary = true
        }

        manifestPlaceholders["googleMapsApiKey"] = googleMapsApiKey

        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = false
            }
        }
    }

    // 模拟方案维度：scoped = 在 LSPosed 作用域里的目标 App 进程内 Hook；global = Hook system_server 等系统进程，对全设备生效。
    // app / app-ui / core-data / xposed 四个模块必须声明完全相同的维度与 flavor，否则变体无法对齐。
    flavorDimensions += "scheme"
    productFlavors {
        create("scoped") {
            dimension = "scheme"
            buildConfigField("boolean", "GLOBAL_SCHEME", "false")
        }
        create("global") {
            dimension = "scheme"
            buildConfigField("boolean", "GLOBAL_SCHEME", "true")
        }
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE_PATH")
                ?: "/Users/vincent/Desktop/SUSE-APP-Key/APP-Key.jks"
            if (file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "LinuxisUbuntu18"
                keyAlias = System.getenv("KEY_ALIAS") ?: "suse-app-key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "LinuxisUbuntu18"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
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
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xskip-metadata-version-check"
        )
    }
}

dependencies {
    implementation(project(":core-geo"))
    implementation(project(":core-data"))
    implementation(project(":service"))
    implementation(project(":app-ui"))
    // 纯打包依赖：:app 不直接调用 :xposed 的代码，只是需要把它的产物
    // （LocationHooker 及 META-INF/xposed/* 资源）一起打进最终 APK，供 LSPosed 扫描加载。
    implementation(project(":xposed"))

    implementation(libs.xposed.service)
    implementation(libs.koin.androidx.compose)
    implementation(libs.amap.map)
    implementation(libs.amap.search)
    implementation(libs.baidu.map)
    implementation(libs.google.places)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)

    debugImplementation(libs.androidx.ui.tooling)
}
