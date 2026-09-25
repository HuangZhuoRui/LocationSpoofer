@file:Suppress("SpellCheckingInspection", "unused", "MemberVisibilityCanBePrivate")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor

import com.vincenthzr.locationspoofer.vendor.RomFamily
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.xposed.LocationHooker

/**
 * 系统大版本适配器基类：在某个厂商级适配器（如 [com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.HyperOsVendor]）
 * 之上，针对该厂商系统的某个大版本跨度做进一步覆盖。
 *
 * ## 解决的问题
 * 同一厂商的子品牌 / 旗舰机型通常用的是同一套系统，内部差异很小，不需要（也不应该）按具体机型拆分
 * 适配器——真正会让 framework 内部实现发生跨度性变化的，往往是**系统大版本升级**（比如 HyperOS 3
 * 升到 HyperOS 4 这种跨大版本更新，可能伴随 Android 基线版本升级、部分系统服务重写）。继承这个
 * 基类只需要：
 *
 * 1. 覆写 [matchesVersion]，判断当前设备是否落在这个大版本范围内（通常解析厂商自己暴露的版本号
 *    属性，例如 HyperOS 的 `ro.mi.os.version.name` / `ro.mi.os.version.code`）；
 * 2. **只覆写"和父适配器不一样的那部分"**——[classCandidates]、[additionalExemptPackages]、
 *    [installExtraHooks] 默认全部透传给 [parent]，不需要重复声明父适配器已有的规则。
 *
 * ## 怎么拿到真实的版本号属性
 * ```bash
 * adb shell getprop ro.mi.os.version.name   # HyperOS 版本名，如 "OS2.0.1.0"（示例，务必用真机实测值）
 * adb shell getprop ro.mi.os.version.code   # HyperOS 版本号（若存在）
 * adb shell getprop ro.build.version.sdk    # Android API Level，作为辅助信号
 * ```
 * 各厂商暴露版本号的属性名和取值格式都不一样，**必须在目标真机上实测确认格式后再写解析逻辑**，
 * 不要凭空假设某个属性一定存在、或者假设版本号格式是纯数字。
 *
 * ## 选择顺序
 * [VendorRegistry] 按 `priority` 选出唯一命中的适配器，本基类默认 `priority = parent.priority + 10`，
 * 保证系统版本适配器天然比它所属的厂商级适配器更精确、优先命中；该厂商下其它没有被精确匹配到大版本
 * 范围的设备（比如版本号解析失败，或者是尚未单独适配的更新版本），仍然会落回厂商级适配器（[parent]）。
 *
 * 具体范例见 `vendor/profiles/versions/` 目录。
 */
abstract class SystemVersionVendor(
    /** 本适配器要在其基础上做覆盖的厂商级（或另一个更粗粒度的版本级）适配器。 */
    protected val parent: SystemHookVendor,
) : SystemHookVendor {

    /**
     * 精确判断当前设备是否属于这个系统大版本。
     *
     * 实现应该解析 [VendorProfile.props] 里厂商自己暴露的版本号属性，而不是只依赖 Android SDK
     * Level——同一个 Android 基线版本上，不同 ROM 大版本仍可能共存（设备升级了系统但没升级 Android
     * 基线，或者反过来），单看 [android.os.Build.VERSION.SDK_INT] 容易判断错。
     */
    protected abstract fun matchesVersion(profile: VendorProfile): Boolean

    override val family: RomFamily get() = parent.family

    /** 默认比父适配器精确 10 个优先级，保证同厂商下"命中具体大版本"总是优先于"只命中厂商"。 */
    override val priority: Int = parent.priority + 10

    /**
     * 必须先满足 [parent] 的判定（保证不会跨厂商误匹配），再满足 [matchesVersion]。
     */
    final override fun matches(profile: VendorProfile): Boolean {
        if (!parent.matches(profile)) return false
        return try {
            matchesVersion(profile)
        } catch (_: Throwable) {
            false
        }
    }

    /** 默认透传给 [parent]；只有真正需要覆盖某个组件类名时才整体覆写此方法，未覆盖的分支记得调用 `super`。 */
    override fun classCandidates(component: SystemComponent): List<String> = parent.classCandidates(component)

    /** 默认透传给 [parent]；只有真正需要追加该版本专属豁免包名时才覆写，通常写成 `super.additionalExemptPackages() + setOf(...)`。 */
    override fun additionalExemptPackages(): Set<String> = parent.additionalExemptPackages()

    /** 默认先执行 [parent] 的额外 Hook，再执行本版本自己的——覆写时记得调用 `super.installExtraHooks(hooker, classLoader)`。 */
    override fun installExtraHooks(hooker: LocationHooker, classLoader: ClassLoader) {
        parent.installExtraHooks(hooker, classLoader)
    }

    /**
     * 辅助方法：从形如 `"OS4.0.12.0.XXXCNXM"` 这类版本号字符串里提取开头的整数主版本号（这里是 `4`）。
     * 提取不到时返回 `null`。
     *
     * 这只是一个通用的"数字前缀提取"工具，**不代表对某个具体厂商版本号格式的保证**——写
     * [matchesVersion] 之前，务必先在真机上用 `adb shell getprop <属性名>` 打印出原始值确认格式，
     * 确认这个工具函数真的适用，而不是想当然直接用。
     */
    protected fun extractLeadingMajorVersion(raw: String): Int? {
        val digits = raw.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }
}
