package com.suseoaa.locationspoofer.xposed.hooks

import android.os.Binder
import android.util.Log
import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.SpoofedMotion
import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject

/**
 * 系统服务（system_server）调用方识别与权限判定工具
 */
object SystemHookUtils {

    private const val TAG = "LocationSpoofer"

    /** 系统豁免包名：绝对不进行模拟，保证系统基础运行与自身数据采集不受污染 */
    val EXEMPT_PACKAGES = setOf(
        "com.suseoaa.locationspoofer",
        "android",
        "system_server",
        "system",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.xiaomi.metoknlp",
        "com.google.android.gms",
        "com.qualcomm.location",
        "com.qualcomm.atfwd",
        "com.android.ons",
        "com.xiaomi.location.fused",
        "com.android.location.fused",
        "com.xiaomi.aicr",
        "com.xiaomi.hypercomm",
        "com.miui.powerinsight",
        "com.miui.powerkeeper"
    )

    @Volatile
    private var cachedSystemContext: android.content.Context? = null

    fun getSystemContext(): android.content.Context? {
        cachedSystemContext?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val ctx = atClass.getMethod("getSystemContext").invoke(currentAt) as? android.content.Context
            cachedSystemContext = ctx
            ctx
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 通过 Binder.getCallingUid() 或传入的 overrideUid 反查系统服务调用者的已安装包名集合
     */
    fun resolveCallingPackages(serviceInstance: Any?, overrideUid: Int? = null): Set<String> {
        return try {
            val uid = if (overrideUid != null && overrideUid > 1000) overrideUid else Binder.getCallingUid()
            if (uid <= 1000) return emptySet() // 忽略 root (0) 和 system (1000) 核心调用

            // 优先使用 ActivityThread 的系统上下文（在 system_server 中最稳定）
            val sysCtx = getSystemContext()
            if (sysCtx != null) {
                val pkgs = sysCtx.packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    return pkgs.toSet()
                }
            }

            // 降级使用 serviceInstance 中的 mContext
            if (serviceInstance != null) {
                val context = XposedHelpers.getObjectField(serviceInstance, "mContext") as? android.content.Context
                if (context != null) {
                    val pkgs = context.packageManager.getPackagesForUid(uid)
                    if (!pkgs.isNullOrEmpty()) {
                        return pkgs.toSet()
                    }
                }
            }
            emptySet()
        } catch (t: Throwable) {
            XposedBridge.log("[SysHook] resolveCallingPackages failed: $t")
            emptySet()
        }
    }

    /**
     * 从方法参数中提取 CallerIdentity 或请求对象中的 UID
     */
    fun extractCallerUid(args: List<Any?>): Int? {
        for (arg in args) {
            if (arg == null) continue
            val className = arg.javaClass.simpleName
            if (className.contains("Identity") || className.contains("Request") || className.contains("Registration")) {
                try {
                    val uid = XposedHelpers.callMethod(arg, "getUid") as? Int
                    if (uid != null && uid > 1000) return uid
                } catch (_: Throwable) {}
                try {
                    val uid = XposedHelpers.getIntField(arg, "mUid")
                    if (uid > 1000) return uid
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    /**
     * 从方法参数中稳妥提取调用方传入的包名（过滤掉 provider、类名与标记，支持 CallerIdentity）
     */
    fun extractPackageName(args: List<Any?>): String? {
        for (arg in args) {
            if (arg == null) continue
            if (arg is String) {
                // 包名特征：必须包含点号，且不是 provider 名称，不是系统内部类名
                if (arg.contains(".") &&
                    !arg.startsWith("android.") &&
                    !arg.startsWith("com.android.server.") &&
                    arg != "gps" && arg != "network" && arg != "passive" && arg != "fused"
                ) {
                    return arg
                }
            }
            // 支持 CallerIdentity / Identity 对象
            val className = arg.javaClass.simpleName
            if (className.contains("Identity") || className.contains("Request") || className.contains("Registration")) {
                try {
                    val pkg = XposedHelpers.callMethod(arg, "getPackageName") as? String
                    if (!pkg.isNullOrEmpty() && pkg.contains(".") && !pkg.startsWith("android.")) {
                        return pkg
                    }
                } catch (_: Throwable) {}
                try {
                    val pkg = XposedHelpers.getObjectField(arg, "mPackageName") as? String
                    if (!pkg.isNullOrEmpty() && pkg.contains(".") && !pkg.startsWith("android.")) {
                        return pkg
                    }
                } catch (_: Throwable) {}
            }
            if (arg is android.app.PendingIntent) {
                val pkg = arg.creatorPackage
                if (!pkg.isNullOrEmpty() && pkg.contains(".")) return pkg
            }
        }
        return null
    }

    /**
     * 从方法参数中稳妥提取 provider（如 "gps", "network", "fused"）
     */
    fun extractProvider(args: List<Any?>): String {
        for (arg in args) {
            if (arg is String) {
                when (arg.lowercase()) {
                    "gps", "network", "passive", "fused" -> return arg
                }
            }
            if (arg != null && (arg.javaClass.simpleName.contains("Request") || arg.javaClass.simpleName.contains("Registration"))) {
                try {
                    val p = XposedHelpers.callMethod(arg, "getProvider") as? String
                    if (!p.isNullOrEmpty()) return p
                } catch (_: Throwable) {}
                try {
                    val p = XposedHelpers.getObjectField(arg, "mProvider") as? String
                    if (!p.isNullOrEmpty()) return p
                } catch (_: Throwable) {}
            }
        }
        return android.location.LocationManager.GPS_PROVIDER
    }

    /**
     * 解析配置中的白名单目标包名列表
     */
    fun resolveTargetPackages(config: JSONObject): Set<String> {
        return config.optJSONArray("system_hook_packages")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }.toSet()
        } ?: emptySet()
    }

    /**
     * 核心判定方法：当前调用方是否属于需要被虚拟模拟的目标应用
     *
     * @param serviceInstance 系统服务实例（用于获取 Context 与 Binder UID）
     * @param explicitPackage 方法参数中显式传入的包名（如 callingPackage），若没有可传 null
     * @param config 当前全局配置 JSON
     * @param overrideUid 可选的调用方真实 UID（如来自 CallerIdentity），解决 clearCallingIdentity 后的 UID 漂移
     */
    fun isTargetCaller(
        serviceInstance: Any?,
        explicitPackage: String?,
        config: JSONObject,
        overrideUid: Int? = null
    ): Boolean {
        if (!config.optBoolean("active", false)) return false

        val uid = if (overrideUid != null && overrideUid > 1000) overrideUid else Binder.getCallingUid()
        val cleanPkg = explicitPackage?.substringBefore(":")?.trim()

        // 1. 严格排除自身，绝不模拟自身
        if (cleanPkg == "com.suseoaa.locationspoofer" || explicitPackage == "com.suseoaa.locationspoofer") {
            return false
        }

        // 若是 system_server 内部发起的调用（uid=1000）且无显式外部包名，则不干扰系统内部逻辑
        if (uid <= 1000 && explicitPackage == null) {
            return false
        }

        // 严格排除系统核心组件与定位服务
        if (cleanPkg != null && EXEMPT_PACKAGES.contains(cleanPkg)) {
            return false
        }

        val callingPackages = resolveCallingPackages(serviceInstance, uid)
        if (callingPackages.any { it.substringBefore(":") == "com.suseoaa.locationspoofer" }) {
            return false
        }
        if (callingPackages.any { EXEMPT_PACKAGES.contains(it.substringBefore(":")) }) {
            return false
        }

        val isGlobalMode = config.optBoolean("system_hook_global_mode", false)

        // 2. 全局模拟模式：只对普通用户应用（UID >= 10000）生效，绝不污染系统级服务
        if (isGlobalMode) {
            if (uid >= 10000) {
                if (cleanPkg != null && !EXEMPT_PACKAGES.contains(cleanPkg)) {
                    XposedBridge.log("[SysHook] GlobalMode match: explicitPkg=$explicitPackage (cleanPkg=$cleanPkg, uid=$uid)")
                    return true
                }
                val nonExempt = callingPackages.filter { !EXEMPT_PACKAGES.contains(it.substringBefore(":")) }
                if (nonExempt.isNotEmpty()) {
                    XposedBridge.log("[SysHook] GlobalMode match: callingPkgs=$nonExempt (uid=$uid)")
                    return true
                }
            }
            return false
        }

        // 3. 白名单模式：仅对用户在软件内勾选的目标包名生效
        val targetPackages = resolveTargetPackages(config)
        if (targetPackages.isEmpty()) return false

        if (cleanPkg != null && targetPackages.contains(cleanPkg)) {
            XposedBridge.log("[SysHook] Whitelist match: cleanPkg=$cleanPkg (explicitPkg=$explicitPackage, uid=$uid)")
            return true
        }

        if (explicitPackage != null && targetPackages.contains(explicitPackage)) {
            logWhitelistMatch("exp:$explicitPackage", "[SysHook] Whitelist match: explicitPkg=$explicitPackage (uid=$uid)")
            return true
        }

        val matched = callingPackages.filter { pkg ->
            targetPackages.contains(pkg) || targetPackages.contains(pkg.substringBefore(":"))
        }
        if (matched.isNotEmpty()) {
            logWhitelistMatch("call:${matched.first()}", "[SysHook] Whitelist match: callingPkgs=$matched (uid=$uid)")
            return true
        }

        return false
    }

    private val lastWhitelistLogTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun logWhitelistMatch(key: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastWhitelistLogTimes[key] ?: 0L
        if (now - last > 5000L) {
            lastWhitelistLogTimes[key] = now
            XposedBridge.log(message)
        }
    }

    /**
     * 就地改写 Location 对象的坐标、精度、速度、航向、时间戳，并彻底抹除 mock 标志位
     */
    fun applyFakeLocationFields(
        locObj: Any,
        motion: SpoofedMotion,
        altitude: Double,
        accuracy: Float
    ) {
        try {
            XposedHelpers.callMethod(locObj, "setLatitude", motion.lat)
            XposedHelpers.callMethod(locObj, "setLongitude", motion.lng)
            XposedHelpers.callMethod(locObj, "setAccuracy", accuracy)
            XposedHelpers.callMethod(locObj, "setSpeed", motion.speed)
            XposedHelpers.callMethod(locObj, "setBearing", motion.bearing)
            XposedHelpers.callMethod(locObj, "setAltitude", altitude)
            XposedHelpers.callMethod(locObj, "setTime", System.currentTimeMillis())
            XposedHelpers.callMethod(
                locObj, "setElapsedRealtimeNanos",
                android.os.SystemClock.elapsedRealtimeNanos()
            )
            try {
                XposedHelpers.callMethod(locObj, "setVerticalAccuracyMeters", 1.5f)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(locObj, "setSpeedAccuracyMetersPerSecond", 0.3f)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(locObj, "setBearingAccuracyDegrees", 2.0f)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(locObj, "setMock", false)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(locObj, "setIsFromMockProvider", false)
            } catch (_: Throwable) {}
            try {
                val extras = XposedHelpers.callMethod(locObj, "getExtras") as? android.os.Bundle
                if (extras != null) {
                    extras.remove("mockLocation")
                    extras.putInt("satellites", 20)
                    extras.putInt("satellites_in_view", 20)
                    extras.putInt("satellites_used_in_fix", 18)
                } else {
                    val bundle = android.os.Bundle()
                    bundle.putInt("satellites", 20)
                    bundle.putInt("satellites_in_view", 20)
                    bundle.putInt("satellites_used_in_fix", 18)
                    XposedHelpers.callMethod(locObj, "setExtras", bundle)
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * 构造一个新的伪造 Location 对象
     */
    fun buildFakeLocation(
        classLoader: ClassLoader,
        provider: String,
        motion: SpoofedMotion,
        altitude: Double,
        accuracy: Float
    ): Any? {
        return try {
            val locClass = Class.forName("android.location.Location", false, classLoader)
            val fakeLoc = locClass.getConstructor(String::class.java).newInstance(provider)
            applyFakeLocationFields(fakeLoc, motion, altitude, accuracy)
            fakeLoc
        } catch (t: Throwable) {
            XposedBridge.log("[SysHook] buildFakeLocation failed: $t")
            null
        }
    }
}
