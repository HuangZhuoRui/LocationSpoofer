import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    namespace = "com.vincenthzr.locationspoofer.xposed"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    // 与 app/build.gradle.kts 中的 scheme 维度保持一致。
    // META-INF/xposed/scope.list 按 flavor 放在 src/scoped/resources 与 src/global/resources 下。
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        prefab = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.bytedance.android:shadowhook:2.0.1")
    implementation(project(":core-geo"))
    compileOnly(libs.xposed.api)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
