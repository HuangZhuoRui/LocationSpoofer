package com.vincenthzr.locationspoofer.xposed.hooks.vendor

import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers

/**
 * 系统服务实现类的统一查找：按 [VendorRegistry.classCandidates]（当前适配器候选 → AOSP 基线候选）逐层查找。
 *
 * Android 12 起 Wi-Fi、Connectivity、蓝牙等服务搬进了 APEX 模块，运行在独立的 ClassLoader 里，
 * system_server 的默认 ClassLoader 加载不到——真正找到它们的往往是后面几层兜底（相关线程的
 * contextClassLoader、LocalServices 里已注册的实例、ServiceManager 缓存、ApplicationLoaders、APEX jar）。
 * 这些兜底层同样只使用适配器给出的候选类名，所以适配器里填的定制类名在 APEX 服务上一样生效。
 *
 * 每次查找的结果（在哪一层找到、找到的类名，或者没找到）都会记进 [HookStatus]。
 */
object SystemClassLocator {

    /**
     * @param extraLoaders 在默认 ClassLoader 之后优先尝试的 ClassLoader（如已找到的 WifiServiceImpl 所在的 APEX ClassLoader）
     * @param threadKeywords 线程名包含任一关键字时，扫描它的 contextClassLoader（APEX 服务常挂在专属 Handler 线程上）
     * @param serviceNames ServiceManager 里的服务名，从已注册实例的 ClassLoader 里找
     * @param deepScan 是否扫描 LocalServices 与 ApplicationLoaders 里的全部 ClassLoader（开销较大，只给确实在 APEX 里的服务用）
     * @param apexJars 上面都找不到时，直接用这些 APEX jar 新建 ClassLoader 加载
     */
    fun locate(
        component: SystemComponent,
        classLoader: ClassLoader,
        extraLoaders: List<ClassLoader?> = emptyList(),
        threadKeywords: List<String> = emptyList(),
        serviceNames: List<String> = emptyList(),
        deepScan: Boolean = false,
        apexJars: List<String> = emptyList(),
    ): Class<*>? {
        val names = VendorRegistry.classCandidates(component)
        fun find(cl: ClassLoader?): Class<*>? = names.firstNotNullOfOrNull { XposedHelpers.findClassIfExists(it, cl) }
        fun found(clazz: Class<*>, source: String): Class<*> {
            XposedBridge.log("[Vendor] $component -> ${clazz.name} (vendor=${VendorRegistry.active.id}, via $source)")
            HookStatus.classFound(component, clazz, source)
            return clazz
        }

        // 1. 默认 ClassLoader 与调用方指定的 ClassLoader
        for (cl in listOf(classLoader) + extraLoaders) {
            find(cl)?.let { return found(it, "classLoader") }
        }

        // 2. 相关线程的 contextClassLoader
        if (threadKeywords.isNotEmpty()) {
            try {
                for (t in Thread.getAllStackTraces().keys) {
                    if (threadKeywords.none { t.name.contains(it, ignoreCase = true) }) continue
                    val cl = t.contextClassLoader ?: continue
                    find(cl)?.let { return found(it, "thread:${t.name}") }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[Vendor] $component thread scan error: $t")
            }
        }

        // 3. ServiceManager 缓存里已注册实例的 ClassLoader
        if (serviceNames.isNotEmpty()) {
            try {
                val sm = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
                val cache = sm?.let { XposedHelpers.getStaticObjectField(it, "sCache") as? Map<*, *> }
                cache?.forEach { (name, service) ->
                    if (name !in serviceNames) return@forEach
                    val cl = service?.javaClass?.classLoader ?: return@forEach
                    find(cl)?.let { return found(it, "ServiceManager[$name]") }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[Vendor] $component ServiceManager scan error: $t")
            }
        }

        if (deepScan) {
            // 4. LocalServices 里登记的所有本地服务（键和值的 ClassLoader 都试）
            try {
                val ls = XposedHelpers.findClassIfExists("com.android.server.LocalServices", classLoader)
                val objects = ls?.let { XposedHelpers.getStaticObjectField(it, "sLocalServiceObjects") as? Map<*, *> }
                objects?.forEach { (key, service) ->
                    for (cl in listOf((key as? Class<*>)?.classLoader, service?.javaClass?.classLoader)) {
                        if (cl == null) continue
                        find(cl)?.let { return found(it, "LocalServices") }
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[Vendor] $component LocalServices scan error: $t")
            }

            // 5. ApplicationLoaders 缓存的全部 ClassLoader
            try {
                val loadersClass = Class.forName("android.app.ApplicationLoaders")
                val instance = loadersClass.getMethod("getDefault").invoke(null)
                for (field in loadersClass.declaredFields) {
                    if (!Map::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val map = field.get(instance) as? Map<*, *> ?: continue
                    for (cl in map.values) {
                        if (cl !is ClassLoader) continue
                        find(cl)?.let { return found(it, "ApplicationLoaders") }
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[Vendor] $component ApplicationLoaders scan error: $t")
            }
        }

        // 6. 直接从 APEX jar 加载
        for (path in apexJars) {
            if (!java.io.File(path).exists()) continue
            try {
                find(dalvik.system.PathClassLoader(path, classLoader))?.let { return found(it, "apex:$path") }
            } catch (t: Throwable) {
                XposedBridge.log("[Vendor] $component load $path failed: $t")
            }
        }

        XposedBridge.log("[Vendor] $component not found, candidates=$names")
        HookStatus.classMissing(component)
        return null
    }
}
