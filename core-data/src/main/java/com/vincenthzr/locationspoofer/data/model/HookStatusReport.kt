package com.vincenthzr.locationspoofer.data.model

import org.json.JSONObject

/**
 * 全局方案某个系统进程写出的 Hook 运行状态报告（格式见 xposed 模块的 `diagnostics/HookStatus`）。
 */
data class HookStatusReport(
    /** xposed 模块 `SystemProcess` 的枚举名：SYSTEM_SERVER / PHONE / BLUETOOTH */
    val process: String,
    val updatedAt: Long,
    val vendorId: String,
    val manualVendor: Boolean,
    val system: String,
    /** 进程当前读取的配置文件与其修改时间，未读到配置时为空 */
    val configPath: String,
    val configModified: Long,
    val components: List<Component>,
    val errors: List<String>
) {
    enum class State {
        /**
         * 找到了类并挂上了方法。个别方法名 ×0 是正常的：Hook 代码为兼容不同 Android 版本会同时列出新旧方法名，
         * 每个系统上只有其中一部分存在
         */
        HOOKED,
        /** 找到了类，但一个方法都没挂上 */
        NO_METHODS,
        /** 各层都查找过，没找到实现类 */
        NOT_FOUND,
        /** 还没轮到查找（服务可能稍后才启动） */
        PENDING
    }

    data class Component(
        /** xposed 模块 `SystemComponent` 的枚举名 */
        val name: String,
        val found: Boolean,
        val searched: Boolean,
        val className: String,
        val source: String,
        /** 方法名 → 实际挂上的重载数，0 表示该系统版本上没有这个方法 */
        val methods: Map<String, Int>
    ) {
        val state: State
            get() = when {
                !found -> if (searched) State.NOT_FOUND else State.PENDING
                methods.values.none { it > 0 } -> State.NO_METHODS
                else -> State.HOOKED
            }
    }

    val hookedCount: Int get() = components.count { it.state == State.HOOKED }

    /** 报告写于本次开机之前；这只能说明尚未读到新报告，不能据此断定模块未生效 */
    fun isStale(bootTimeMs: Long): Boolean = updatedAt < bootTimeMs

    companion object {
        fun parse(text: String): HookStatusReport? = try {
            val json = JSONObject(text.trim())
            val vendor = json.optJSONObject("vendor") ?: JSONObject()
            val components = json.optJSONArray("components")
            val errors = json.optJSONArray("errors")
            HookStatusReport(
                process = json.getString("process"),
                updatedAt = json.optLong("updated_at"),
                vendorId = vendor.optString("id"),
                manualVendor = vendor.optBoolean("manual"),
                system = json.optString("system"),
                configPath = json.optJSONObject("config")?.optString("path").orEmpty(),
                configModified = json.optJSONObject("config")?.optLong("modified") ?: 0L,
                components = (0 until (components?.length() ?: 0)).map { i ->
                    val c = components!!.getJSONObject(i)
                    val methods = c.optJSONObject("methods")
                    Component(
                        name = c.getString("name"),
                        found = c.optBoolean("found"),
                        searched = c.optBoolean("searched"),
                        className = c.optString("class"),
                        source = c.optString("source"),
                        methods = methods?.keys()?.asSequence()?.associateWith { methods.optInt(it) }.orEmpty()
                    )
                },
                errors = (0 until (errors?.length() ?: 0)).map { errors!!.getString(it) }
            )
        } catch (_: Exception) {
            null
        }
    }
}
