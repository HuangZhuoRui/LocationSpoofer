package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import android.location.Criteria
import android.location.LocationManager
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Timer
import java.util.TimerTask

/** 只补充定位关闭时的可用性判断，不替换通用定位、GNSS、环境数据 Hook。 */
internal class ColorOs16LocationAvailability(
    private val module: LocationHooker,
    private val loader: ClassLoader
) : AutoCloseable {
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val query = ThreadLocal<Boolean>()
    @Volatile private var live = true
    private var timer: Timer? = null
    private var invalidate: Method? = null

    private fun enabled(owner: Any?, uid: Int? = null, pkg: String? = null): Boolean {
        val config = module.readConfig() ?: return false
        return live && config.optBoolean("active", false) &&
            config.optBoolean("force_location_enabled", false) &&
            SystemHookUtils.isTargetCaller(owner, pkg, config, uid)
    }

    fun install() {
        try {
            val service = SystemClassLocator.locate(SystemComponent.LOCATION_MANAGER_SERVICE, loader)
                ?: return
            val manager = SystemClassLocator.locate(SystemComponent.LOCATION_PROVIDER_MANAGER, loader)
                ?: return
            val getManager = XposedHelpers.findMethodExact(service, "getLocationProviderManager", String::class.java)
            val invalidateMethod = LocationManager::class.java.getDeclaredMethod("invalidateLocalLocationEnabledCaches")
                .apply { isAccessible = true }
            invalidate = invalidateMethod
            for (name in listOf("isLocationEnabledForUser", "isProviderEnabledForUser")) {
                handles += XposedHelpers.hookAllMethods(service, name) { chain, method ->
                    val original = chain.proceed(chain.args.toTypedArray())
                    if ((method as Method).returnType != Boolean::class.javaPrimitiveType ||
                        !enabled(chain.thisObject)) original
                    else if (name == "isProviderEnabledForUser" &&
                        getManager.invoke(chain.thisObject, chain.args.firstOrNull()) == null) original
                    else true
                }
            }
            handles += XposedHelpers.hookAllMethods(service, "getProviders") { chain, _ ->
                val previous = query.get()
                query.set(enabled(chain.thisObject))
                try { chain.proceed(chain.args.toTypedArray()) }
                finally { if (previous == null) query.remove() else query.set(previous) }
            }
            handles += XposedHelpers.hookAllMethods(manager, "isEnabled") { chain, method ->
                val original = chain.proceed(chain.args.toTypedArray())
                if (live && query.get() == true && (method as Method).returnType == Boolean::class.javaPrimitiveType) true
                else original
            }
            handles += XposedHelpers.hookAllMethods(manager, "isActive") { chain, method ->
                val identity = chain.args.firstOrNull { value ->
                    value != null && value.javaClass.methods.any { it.name == "getUid" && it.parameterCount == 0 }
                }
                val force = runCatching {
                    identity != null && enabled(chain.thisObject,
                        XposedHelpers.callMethod(identity, "getUid") as Int,
                        XposedHelpers.callMethod(identity, "getPackageName") as? String)
                }.getOrDefault(false)
                val args = chain.args.toTypedArray()
                val signature = method as Method
                // 仅覆盖“定位开关可用”入参，保留原方法中的用户、权限与电源策略判断。
                if (force && signature.parameterTypes.contentEquals(arrayOf(
                        Boolean::class.javaPrimitiveType, identity?.javaClass))) {
                    args[0] = true
                }
                val previous = query.get()
                query.set(force)
                try { chain.proceed(args) }
                finally { if (previous == null) query.remove() else query.set(previous) }
            }
            for (name in listOf("getProviders", "getBestProvider")) {
                module.deoptimize(XposedHelpers.findMethodExact(service, name,
                    Criteria::class.java, Boolean::class.javaPrimitiveType!!))
            }
            var lastKey: String? = null
            timer = Timer("LocationSpoofer-ColorOS16Availability", true).apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        if (!live) return
                        runCatching {
                            val config = module.readConfig()
                            val key = "${config?.optBoolean("active", false)}|${config?.optBoolean("force_location_enabled", false)}|${config?.optBoolean("system_hook_global_mode", false)}|${config?.optJSONArray("system_hook_packages")}"
                            if (key != lastKey) {
                                invalidateMethod.invoke(null)
                                lastKey = key
                            }
                        }.onFailure { HookStatus.error("ColorOS16 availability cache", it) }
                    }
                }, 0L, 1000L)
            }
        } catch (error: Throwable) {
            HookStatus.error("ColorOS16 availability", error)
            close()
        }
    }

    override fun close() {
        live = false
        timer?.cancel()
        timer = null
        handles.asReversed().forEach { handle ->
            runCatching { handle.unhook() }.onFailure { HookStatus.error("ColorOS16 unhook", it) }
        }
        handles.clear()
        query.remove()
        runCatching { invalidate?.invoke(null) }
    }
}
