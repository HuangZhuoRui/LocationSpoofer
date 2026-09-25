@file:Suppress("SpellCheckingInspection", "unused")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor

import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.vendor.VendorScheme
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.AospVendor
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.ColorOsVendor
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.HyperOsVendor
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.OneUiVendor
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions.SystemVersionVendorTemplate
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge

/**
 * 机型 / 系统适配器的注册表与统一入口。
 *
 * 共享 Hook 代码只跟这个对象（以及基于它的 [SystemClassLocator]）打交道，不直接引用任何具体的
 * [SystemHookVendor] 实现，从而实现"系统各走各的规则"而调用方无需感知分支：
 * - [classCandidates] —— "当前机型候选 → AOSP 基线候选"合并去重后的类名清单，[SystemClassLocator] 按它逐层查找；
 * - [additionalExemptPackages] —— 当前机型追加的豁免包名，与通用基线合并后供
 *   [com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils.EXEMPT_PACKAGES] 使用；
 * - [installExtraHooks] —— 触发当前机型专属的额外 Hook。
 *
 * **注册新适配器**：在下面的 [ALL] 列表里加上你的 `object` 即可，无需改动任何其它文件。
 * 厂商级（`profiles/`）和系统版本级（`profiles/versions/`，继承 [SystemVersionVendor]）适配器
 * 注册方式完全一样，都是往同一个列表里加一项，选择时统一按 [SystemHookVendor.priority] 排序，
 * 不需要区分"厂商层"和"版本层"两套机制。
 *
 * 注意：这里**不**按具体机型（市场型号）分层——同一厂商的子品牌/旗舰机型通常用的是同一套系统，
 * 内部实现差异很小，按机型拆反而会制造大量没必要的重复文件；真正需要单独适配的是**系统大版本
 * 跨度**（比如 HyperOS 3 升到 HyperOS 4 这种可能伴随 Android 基线版本升级、部分系统服务重写的
 * 更新），所以适配粒度停在"厂商 → 系统大版本"两层，不再往下拆到具体机型。
 *
 * **手动覆盖自动识别**：[VendorProfile] 的自动识别基于属性/厂商名判断，遇到属性被裁剪、
 * 或者用户想强制在某台设备上试用另一套适配器时，App 内"厂商适配方案"设置页允许用户手动指定
 * 一个 [SystemHookVendor.id]，通过 [applyManualOverride] 灌进来，[select] 命中后直接返回该适配器、
 * 跳过 [SystemHookVendor.matches] 判断。必须在 [active] 首次被访问前调用（也就是在
 * [com.vincenthzr.locationspoofer.xposed.LocationHooker.handleLoadPackage] 里，早于任何一个查找系统服务类的
 * hookSystemXxxService 调用）——因为 [active] 是 `by lazy`，只求值一次。
 */
object VendorRegistry {

    /**
     * 所有已知适配器。顺序不影响选择结果（选择只看 [SystemHookVendor.priority]），
     * 但请把 [AospVendor] 放在最后，直观表达"它是兜底"。
     *
     * 系统版本适配器（[SystemVersionVendor] 子类）默认比它们的 `parent` 优先级更高，天然排在
     * 对应厂商级适配器之前被选中；未真正配置版本判定逻辑的模板（如 [SystemVersionVendorTemplate]）
     * 永远不会被 `matches()` 选中，留在列表里是安全的。
     */
    internal val ALL: List<SystemHookVendor> = listOf(
        SystemVersionVendorTemplate, // 默认不生效的系统版本级适配器模板，见其类注释
        HyperOsVendor,
        ColorOsVendor,
        OneUiVendor,
        AospVendor, // 兜底基线，priority = Int.MIN_VALUE
    )

    /** 用户手动指定的适配器 id（见类注释"手动覆盖自动识别"），`null`/空/`"auto"` 一律走自动识别。 */
    @Volatile
    private var manualOverrideId: String? = null

    /**
     * 灌入用户手动选择的适配器 id。必须在 [active] 首次被求值前调用，晚了会被忽略并打日志——
     * 详见类注释"手动覆盖自动识别"一节。
     */
    fun applyManualOverride(id: String?) {
        if (activeResolved) {
            XposedBridge.log("[Vendor] applyManualOverride($id) 被忽略：active 已经求值过，调用时机太晚了")
            return
        }
        manualOverrideId = id
    }

    /** [active] 是否已经被求值过——`by lazy` 本身不暴露这个状态，用这个标记位补上，供 [applyManualOverride] 判断时机。 */
    @Volatile
    private var activeResolved = false

    /** 当前设备命中的适配器：手动覆盖优先；否则取所有 [SystemHookVendor.matches] 为真者中 [SystemHookVendor.priority] 最大的一个。 */
    val active: SystemHookVendor by lazy {
        activeResolved = true
        select(VendorProfile.current, manualOverrideId)
    }

    /** 当前命中的适配器是否来自用户手动指定 */
    val isManualOverride: Boolean
        get() = !manualOverrideId.isNullOrBlank() && manualOverrideId != VendorScheme.AUTO.id && active.id == manualOverrideId

    private var logged = false

    /** 选择逻辑本身是纯函数，便于单元测试直接喂伪造的设备画像 */
    internal fun select(profile: VendorProfile, overrideId: String?): SystemHookVendor {
        if (!overrideId.isNullOrBlank() && overrideId != VendorScheme.AUTO.id) {
            val manual = ALL.find { it.id == overrideId }
            if (manual != null) {
                XposedBridge.log("[Vendor] 命中手动覆盖：$overrideId")
                return manual
            }
            XposedBridge.log("[Vendor] 手动覆盖 id '$overrideId' 未在 ALL 列表中找到匹配项，回退到自动识别")
        }

        val chosen = ALL.filter {
            try {
                it.matches(profile)
            } catch (t: Throwable) {
                XposedBridge.log("[Vendor] matches() of ${it.id} threw: $t")
                false
            }
        }.maxByOrNull { it.priority } ?: AospVendor
        return chosen
    }

    /**
     * 打印一次当前设备画像与命中的适配器，便于排查用户反馈时快速确认"这台设备走了哪套规则"。
     * 幂等：多次调用只会真正输出一次。
     */
    fun logSelectionOnce() {
        if (logged) return
        logged = true
        try {
            XposedBridge.log("[Vendor] active=${active.id} (family=${active.family}, manual=$isManualOverride) profile=${VendorProfile.current}")
        } catch (_: Throwable) {}
    }

    /**
     * 某系统组件的候选实现类名清单：当前机型候选在前，AOSP 基线候选在后，去重。
     * [SystemClassLocator] 在每一层查找时都只试这份名单。
     */
    fun classCandidates(component: SystemComponent): List<String> {
        val vendor = try {
            active.classCandidates(component)
        } catch (t: Throwable) {
            XposedBridge.log("[Vendor] classCandidates(${component}) of ${active.id} threw: $t")
            emptyList()
        }
        return (vendor + AospVendor.classCandidates(component)).distinct()
    }

    /** 触发当前机型专属的额外 Hook；异常自行吞掉，不影响主流程。 */
    fun installExtraHooks(hooker: LocationHooker, classLoader: ClassLoader) {
        try {
            active.installExtraHooks(hooker, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[Vendor] installExtraHooks of ${active.id} threw: $t")
        }
    }

    /**
     * 当前机型追加的豁免包名（见 [SystemHookVendor.additionalExemptPackages]）。
     * 首次访问时求值并缓存——[SystemHookUtils.EXEMPT_PACKAGES] 在 `isTargetCaller` 这条热路径上
     * 每次系统 IPC 调用都会读取，不能在这里重复分配 Set。
     */
    val additionalExemptPackages: Set<String> by lazy {
        try {
            active.additionalExemptPackages()
        } catch (t: Throwable) {
            XposedBridge.log("[Vendor] additionalExemptPackages of ${active.id} threw: $t")
            emptySet()
        }
    }
}
