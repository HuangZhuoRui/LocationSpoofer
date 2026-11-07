@file:Suppress("SpellCheckingInspection", "unused")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor

import com.vincenthzr.locationspoofer.vendor.RomFamily
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.xposed.LocationHooker

/**
 * 单个机型 / 系统的适配器契约。
 *
 * 每种系统各写一个实现类，放在 `vendor/profiles/` 下，只声明"自己这套系统和 AOSP 基线的差异"，
 * 而不用把所有 OEM 的判断堆进同一个 Hook 函数里。共享 Hook 代码通过 [VendorRegistry] 间接使用这些
 * 适配器，运行时按 [priority] 选出唯一命中的适配器，选不到时落到 AOSP 基线。
 *
 * 差异通常分两类，对应下面两个扩展点：
 * 1. **只是类名不同** —— 覆写 [classCandidates]，给出该机型上系统服务实现类的候选包名。这是最常见的情况，
 *    绝大多数适配只需要这一步。
 * 2. **有基线覆盖不了的定制逻辑**（比如某 ROM 在系统服务里插了额外的位置模拟检测分支）—— 覆写
 *    [installExtraHooks]，在这里追加该机型专属的 Hook。它在基线 Hook 之后被调用，是纯追加，不影响其它机型。
 *
 * 实现要求：**无状态、可安全并发调用**。适配器实例是单例（`object`），不要在其中持有可变状态。
 */
interface SystemHookVendor {
    /** 该版本需要用户显式开启虚拟定位可用功能；其他适配器保持原有行为。 */
    /** 使用框架注册对象派发位置；仍复用通用 GNSS、NMEA 和地理编码。 */
    /** Vendor owns BLE delivery using permission-checked framework scan queues. */
    val usesFrameworkBleDelivery: Boolean get() = false

    val usesFrameworkLocationDelivery: Boolean get() = false

    val requiresVirtualLocationOptIn: Boolean get() = false


    /**
     * 适配器标识，例如 `"hyperos"`、`"aosp"`。
     *
     * 厂商级适配器直接取 [com.vincenthzr.locationspoofer.vendor.VendorScheme] 里对应项的 id——App 内
     * "厂商适配方案"设置页把用户的手动选择持久化成这个字符串（见 [VendorRegistry.applyManualOverride]），
     * 两边引用同一个枚举，不会失去同步。系统版本级适配器用自己的 id（如 `"hyperos-4"`），不出现在设置页里。
     */
    val id: String

    /**
     * 该适配器归属的 ROM 家族。家族由 core-geo 的 `RomRules` 统一识别（App 显示"本机系统"也用这套规则），
     * 厂商级适配器的 [matches] 通常就是 `profile.family == family`。
     */
    val family: RomFamily

    /**
     * 选择优先级：多个适配器同时 [matches] 时，数字最大的胜出。
     *
     * 约定：具体机型 / ROM 适配器用正数（越精确越大）；AOSP 基线固定为 [Int.MIN_VALUE]，
     * 保证它永远是最后兜底、绝不会盖过任何真正命中的 OEM 适配器。
     */
    val priority: Int

    /**
     * 判断当前设备是否应由本适配器接管。
     *
     * 判定应尽量依据稳定信号（厂商标识属性、`manufacturer`/`brand`），必要时再叠加 API Level、
     * 具体机型等条件做更精确的匹配。AOSP 基线恒返回 `true`。
     */
    fun matches(profile: VendorProfile): Boolean

    /**
     * 给出该机型上某个系统组件的实现类候选包名（按优先级从高到低）。
     *
     * 只需返回"与基线不同或基线没有"的候选即可——[VendorRegistry] 会自动在其后追加 AOSP 基线候选，
     * 所以 OEM 适配器即使只覆盖了个别组件，其它组件仍能自动享受基线兜底。默认返回空列表（完全走基线）。
     */
    fun classCandidates(component: SystemComponent): List<String> = emptyList()

    /**
     * 追加该机型专属的额外 Hook（基线逻辑无法覆盖的定制行为）。
     *
     * 在对应进程的基线系统 Hook 全部安装完成之后被调用，每个核心进程仅调用一次。默认空实现。
     * 实现内部必须自行 try/catch 兜底，任何异常都不应向外抛出而影响主流程。
     */
    fun installExtraHooks(hooker: LocationHooker, classLoader: ClassLoader) {}

    /**
     * 该机型需要额外豁免、绝不模拟的系统包名。
     *
     * 用于收纳"厂商自己的定位融合 / 场景感知 / 省电策略服务，如果喂给它假坐标会导致厂商自身的
     * 定位链路内部打架，或者被厂商风控识别为异常"这类**只在本机型上才存在**的系统包。
     * 跨机型通用的豁免名单（本项目自身、`android`、`system_server`、SystemUI、GMS、
     * Qualcomm 定位 HAL 等）已经维护在
     * [com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils.EXEMPT_PACKAGES] 的基线部分，
     * 不需要在这里重复；[VendorRegistry] 会自动把两者合并。默认返回空集合。
     */
    fun additionalExemptPackages(): Set<String> = emptySet()
}
