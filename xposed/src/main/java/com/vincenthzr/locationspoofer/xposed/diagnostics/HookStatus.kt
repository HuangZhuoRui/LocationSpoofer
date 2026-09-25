package com.vincenthzr.locationspoofer.xposed.diagnostics

import com.vincenthzr.locationspoofer.vendor.HookStatusFiles
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemProcess
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全局方案的 Hook 运行状态报告：每个系统进程把"选中了哪个适配器、每个组件在哪一层找到了哪个类、
 * 各方法实际挂上了几个重载、部署过程中的异常"写成一份 JSON，App 通过 root 读取后显示在
 * "系统适配 → Hook 运行状态"页。适配新系统、排查用户反馈时不必再让用户导出完整 logcat。
 *
 * 只有调用过 [begin] 的进程（全局方案下的 system_server / com.android.phone / com.android.bluetooth）才会落盘；
 * 其它进程里的记录调用只是写内存，开销可以忽略。写文件在后台线程里合并执行，不阻塞 Hook 部署。
 */
object HookStatus {

    const val FORMAT_VERSION = 1

    /** 各进程报告的落盘位置，与 App 端共用（见 core-geo 的 HookStatusFiles） */
    fun reportPaths(process: SystemProcess): List<String> = HookStatusFiles.paths(process.name)

    private data class Located(val className: String, val source: String, val time: Long)

    @Volatile private var process: SystemProcess? = null
    private val located = ConcurrentHashMap<SystemComponent, Located>()
    private val missing = ConcurrentHashMap.newKeySet<SystemComponent>()
    private val methods = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val errors = java.util.Collections.synchronizedList(ArrayList<String>())

    // 普通 App 进程里 hookAllMethods 也会调到这里，写线程只在真正需要落盘的系统进程里创建
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LocationSpoofer-HookStatus").apply { isDaemon = true }
        }
    }
    private val writePending = AtomicBoolean(false)

    /** 在系统进程开始部署 Hook 时调用，之后的记录才会落盘 */
    fun begin(process: SystemProcess) {
        this.process = process
        scheduleWrite()
    }

    fun classFound(component: SystemComponent, clazz: Class<*>, source: String) {
        missing.remove(component)
        located[component] = Located(clazz.name, source, System.currentTimeMillis())
        scheduleWrite()
    }

    /** 查找失败只在还没找到过时记为缺失——服务稍后通过 addService / 轮询被捕获时会被 [classFound] 覆盖 */
    fun classMissing(component: SystemComponent) {
        if (!located.containsKey(component)) missing.add(component)
        scheduleWrite()
    }

    /** 由 XposedHelpers.hookAllMethods 调用：某个类的某个方法名实际挂上了几个重载（0 表示该版本上没有这个方法） */
    fun methodHooked(className: String, methodName: String, count: Int) {
        methods.getOrPut(className) { ConcurrentHashMap() }.merge(methodName, count) { a, b -> maxOf(a, b) }
        if (process != null) scheduleWrite()
    }

    @Volatile private var configPath: String? = null
    @Volatile private var configModified = 0L

    /** 进程当前读取的配置文件及其修改时间：配置副本写入失败时，这里能直接看出进程在读一份旧配置 */
    fun configLoaded(path: String, modified: Long) {
        if (path == configPath && modified == configModified) return
        configPath = path
        configModified = modified
        scheduleWrite()
    }

    fun error(where: String, t: Throwable) {
        synchronized(errors) {
            if (errors.size < 20) errors += "$where: ${t.javaClass.simpleName}: ${t.message}"
        }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (process == null) return
        if (!writePending.compareAndSet(false, true)) return
        writer.schedule({
            writePending.set(false)
            write()
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun write() {
        val proc = process ?: return
        val json = try {
            toJson(proc)
        } catch (t: Throwable) {
            return
        }
        for (path in reportPaths(proc)) {
            try {
                val file = File(path)
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.toString())
                tmp.setReadable(true, false)
                if (tmp.renameTo(file)) return
            } catch (_: Throwable) {
                // 该目录不可写，尝试下一个位置
            }
        }
    }

    private fun toJson(proc: SystemProcess): JSONObject {
        val profile = VendorProfile.current
        val active = VendorRegistry.active
        val components = JSONArray()
        for (component in SystemComponent.entries.filter { it.process == proc }) {
            val loc = located[component]
            components.put(JSONObject().apply {
                put("name", component.name)
                put("found", loc != null)
                // 查找过但没找到，区别于"还没轮到查找"（例如服务稍后才注册）
                put("searched", loc != null || component in missing)
                if (loc != null) {
                    put("class", loc.className)
                    put("source", loc.source)
                    put("methods", JSONObject(methods[loc.className].orEmpty().toSortedMap() as Map<*, *>))
                }
            })
        }
        return JSONObject().apply {
            put("format", FORMAT_VERSION)
            put("process", proc.name)
            put("updated_at", System.currentTimeMillis())
            put("vendor", JSONObject().apply {
                put("id", active.id)
                put("family", active.family.name)
                put("manual", VendorRegistry.isManualOverride)
            })
            configPath?.let { put("config", JSONObject().put("path", it).put("modified", configModified)) }
            put("system", profile.systemLabel)
            put("model", profile.model)
            put("components", components)
            put("errors", JSONArray(synchronized(errors) { errors.toList() }))
        }
    }
}
