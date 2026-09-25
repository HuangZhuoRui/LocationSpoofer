import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

android {
    namespace = "com.vincenthzr.locationspoofer.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        // 库模块没有自己的 versionName，这里手动生成一个同名 BuildConfig 字段，
        // 和 :app 共用 gradle.properties 里的同一个版本号来源，
        // 供 UpdateScreen/InfoTab/MainScaffoldScreen 里的 BuildConfig.VERSION_NAME 读取当前版本号
        buildConfigField(
            "String",
            "VERSION_NAME",
            "\"${providers.gradleProperty("APP_VERSION_NAME").get()}\""
        )

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 与 app/build.gradle.kts 中的 scheme 维度保持一致
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
        compose = true
        buildConfig = true
    }
}

/** 把仓库根目录的适配进度文档复制进 assets，App 联网取不到最新版时显示这份 */
abstract class BundleAdaptationProgress : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = source.get().asFile
        src.copyTo(outputDir.get().asFile.resolve(src.name), overwrite = true)
    }
}

val bundleAdaptationProgress = tasks.register<BundleAdaptationProgress>("bundleAdaptationProgress") {
    source.set(rootProject.layout.projectDirectory.file("ADAPTATION_PROGRESS.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleAdaptationProgress, BundleAdaptationProgress::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xskip-metadata-version-check",
            "-opt-in=kotlinx.serialization.InternalSerializationApi"
        )
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-geo"))

    implementation(libs.koin.androidx.compose)
    implementation(libs.amap.map)
    implementation(libs.amap.search)
    implementation(libs.baidu.map)
    implementation(libs.baidu.location)
    implementation(libs.baidu.search)
    implementation(libs.google.maps)
    implementation(libs.google.places)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
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

    testImplementation(libs.junit)
}
