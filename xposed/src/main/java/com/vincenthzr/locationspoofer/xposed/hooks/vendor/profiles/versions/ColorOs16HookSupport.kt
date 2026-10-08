package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import android.os.Binder
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.lang.reflect.*
import java.util.Timer
import java.util.TimerTask

internal data class ColorOsCaller(val uid: Int, val pkg: String?)

/** Owned hooks/timers and exact, hierarchy-aware reflection for the inspected ColorOS classes. */
internal open class ColorOs16HookSupport(
    protected val module: LocationHooker,
    protected val loader: ClassLoader,
    private val feature: String
) : AutoCloseable {
    @Volatile protected var live = true
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val timers = mutableListOf<Timer>()

    protected fun type(name: String): Class<*> = Class.forName(name, false, loader)
    protected fun field(type: Class<*>, name: String): Field {
        // 两代 ColorOS16 固件字段命名差异：PJX110 用无 m 前缀，重构固件用 m 前缀。
        // 找不到原名时自动补试 "m"+首字母大写，两代通吃。
        val candidates = if (name.startsWith("m") || name.isEmpty()) listOf(name)
            else listOf(name, "m" + name.replaceFirstChar { it.uppercase() })
        var current: Class<*>? = type
        while (current != null) {
            for (c in candidates) {
                try { return current.getDeclaredField(c).apply { isAccessible = true } }
                catch (_: NoSuchFieldException) { }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}.${candidates.joinToString()}")
    }
    protected fun getOf(obj: Any, vararg names: String): Any? {
        // 结构化差异字段（如 mScanHelper/mScanController、mContext/mAdapterService）按候选逐个探测
        var current: Class<*>? = obj.javaClass
        while (current != null) {
            for (name in names) {
                try { return current.getDeclaredField(name).apply { isAccessible = true }.get(obj) }
                catch (_: NoSuchFieldException) { }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${obj.javaClass.name}.${names.joinToString()}")
    }
    protected fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            try { return current.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }
            catch (_: NoSuchMethodException) { current = current.superclass }
        }
        throw NoSuchMethodException("${type.name}.$name")
    }
    protected fun get(obj: Any, name: String): Any? = field(obj.javaClass, name).get(obj)
    protected fun call(obj: Any, name: String): Any? = method(obj.javaClass, name).invoke(obj)
    protected fun caller(args: List<Any?>): ColorOsCaller = ColorOsCaller(
        Binder.getCallingUid(), SystemHookUtils.extractPackageName(args)
    )
    protected fun identity(obj: Any): ColorOsCaller = ColorOsCaller(
        call(obj, "getUid") as Int, call(obj, "getPackageName") as? String
    )
    protected fun target(who: ColorOsCaller, config: JSONObject, owner: Any? = null): Boolean =
        live && SystemHookUtils.isTargetCaller(owner, who.pkg, config, who.uid)

    protected fun hook(m: Method, body: (XposedInterface.Chain) -> Any?) {
        val installed = XposedHelpers.hookAllMethods(m.declaringClass, m.name) { chain, hooked ->
            if (live && hooked == m) body(chain) else chain.proceed(chain.args.toTypedArray())
        }
        synchronized(handles) { handles.addAll(installed) }
        check(installed.isNotEmpty()) { "Unable to hook $m" }
    }

    protected fun section(name: String, body: () -> Unit): Boolean {
        val start = synchronized(handles) { handles.size }
        val timerStart = synchronized(timers) { timers.size }
        try { body(); return true }
        catch (error: Throwable) {
            synchronized(handles) {
                while (handles.size > start) runCatching { handles.removeAt(handles.lastIndex).unhook() }
            }
            synchronized(timers) {
                while (timers.size > timerStart) timers.removeAt(timers.lastIndex).cancel()
            }
            HookStatus.error("ColorOS16/$feature/$name", error)
            XposedBridge.log("[ColorOS16/$feature] $name disabled: $error")
            return false
        }
    }
    protected fun <T> guard(name: String, body: () -> T): T? = try { body() }
    catch (error: Throwable) {
        XposedBridge.logOpenCellIdEvery("ColorOS16/$feature/$name", "$name: $error", 10000L)
        null
    }
    protected fun every(interval: Long, body: () -> Unit) {
        val timer = Timer("LocationSpoofer-$feature", true)
        timer.schedule(object : TimerTask() {
            override fun run() { if (live) guard("tick", body) }
        }, interval, interval)
        synchronized(timers) { timers.add(timer) }
    }

    override fun close() {
        live = false
        synchronized(timers) { timers.forEach { it.cancel() }; timers.clear() }
        synchronized(handles) {
            handles.asReversed().forEach { runCatching { it.unhook() } }; handles.clear()
        }
    }
}
