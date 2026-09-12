package com.suseoaa.locationspoofer.xposed.hooks

import android.os.Binder
import android.util.Log
import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.SpoofedMotion
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
        "com.android.server.telecom"
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
     * 通过 Binder.getCallingUid() 反查系统服务调用者的已安装包名集合
     */
    fun resolveCallingPackages(serviceInstance: Any?): Set<String> {
        return try {
            val uid = Binder.getCallingUid()
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
            Log.e(TAG, "[SysHook] resolveCallingPackages failed: $t")
            emptySet()
        }
    }

    /**
     * 从方法参数中稳妥提取调用方传入的包名（过滤掉 provider、类名与标记）
     */
    fun extractPackageName(args: List<Any?>): String? {
        for (arg in args) {
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
     */
    fun isTargetCaller(
        serviceInstance: Any?,
        explicitPackage: String?,
        config: JSONObject
    ): Boolean {
        if (!config.optBoolean("active", false)) return false

        val uid = Binder.getCallingUid()

        // 1. 严格排除自身，绝不模拟自身
        if (explicitPackage == "com.suseoaa.locationspoofer") {
            return false
        }

        // 若是 system_server 内部发起的调用（uid=1000）且无显式外部包名，则不干扰系统内部逻辑
        if (uid <= 1000 && explicitPackage == null) {
            return false
        }

        val callingPackages = resolveCallingPackages(serviceInstance)
        if (callingPackages.contains("com.suseoaa.locationspoofer")) {
            return false
        }

        val isGlobalMode = config.optBoolean("system_hook_global_mode", false)

        // 2. 全局模拟模式：对设备上所有非系统核心应用生效
        if (isGlobalMode) {
            if (explicitPackage != null && !EXEMPT_PACKAGES.contains(explicitPackage)) {
                Log.i(TAG, "[SysHook] GlobalMode match: explicitPkg=$explicitPackage (uid=$uid)")
                return true
            }
            val nonExempt = callingPackages.filter { !EXEMPT_PACKAGES.contains(it) }
            if (nonExempt.isNotEmpty()) {
                Log.i(TAG, "[SysHook] GlobalMode match: callingPkgs=$nonExempt (uid=$uid)")
                return true
            }
            return false
        }

        // 3. 白名单模式：仅对用户在软件内勾选的目标包名生效
        val targetPackages = resolveTargetPackages(config)
        if (targetPackages.isEmpty()) return false

        if (explicitPackage != null && targetPackages.contains(explicitPackage)) {
            Log.i(TAG, "[SysHook] Whitelist match: explicitPkg=$explicitPackage (uid=$uid)")
            return true
        }

        val matched = callingPackages.intersect(targetPackages)
        if (matched.isNotEmpty()) {
            Log.i(TAG, "[SysHook] Whitelist match: callingPkgs=$matched (uid=$uid)")
            return true
        }

        return false
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
            Log.e(TAG, "[SysHook] buildFakeLocation failed: $t")
            null
        }
    }
}
