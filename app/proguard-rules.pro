# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Xposed Framework (Legacy & LibXposed)
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep interface io.github.libxposed.** { *; }
-keep class com.vincenthzr.locationspoofer.xposed.** { *; }
# libxposed-api 在 :xposed 模块里是 compileOnly（由 LSPosed 框架在运行时提供，不打进 APK），
# compileOnly 依赖不会传递给依赖方 :app 的 R8，所以这里需要显式 -dontwarn 而不是仅靠 -keep。
-dontwarn io.github.libxposed.api.**

# AMap 3DMap & Location SDK
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# Baidu Map SDK
-keep class com.baidu.** { *; }
-keep class mapsdkvi.com.** { *; }
-keep class vi.com.gdi.bgl.android.** { *; }
-dontwarn com.baidu.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep @kotlinx.serialization.Serializable class * {
    *;
}

# Models / Utils
-keep class com.vincenthzr.locationspoofer.utils.ConfigManager { *; }
-keep class com.vincenthzr.locationspoofer.utils.LSPosedManager { *; }
-keep class com.vincenthzr.locationspoofer.provider.** { *; }
-keep class com.vincenthzr.locationspoofer.data.** { *; }
-keepclassmembers class com.vincenthzr.locationspoofer.data.** { *; }

# General safety for Android lifecycle
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.ContentProvider { *; }