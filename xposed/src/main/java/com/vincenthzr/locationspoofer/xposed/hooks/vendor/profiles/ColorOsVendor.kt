@file:Suppress("SpellCheckingInspection", "unused")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles

import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemHookVendor
import com.vincenthzr.locationspoofer.vendor.RomFamily
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.vendor.VendorScheme

/**
 * OPPO / OnePlus / realme：ColorOS / OxygenOS 适配器（**模板 / 待实机验证**）。
 *
 * 目前仅负责让这些机型正确自报为 coloros，[classCandidates] 返回空、完全走 AOSP 基线——
 * 因为尚未在实机上确认 ColorOS 是否改了这些系统服务的实现类名。它是留给后续维护者的骨架：
 * 拿到 ColorOS 实机后，按 [ADAPTATION_GUIDE](../../../../../../../../../../ADAPTATION_GUIDE.md) 的方法
 * 反编译确认真实类名，再把差异填进 [classCandidates] / [installExtraHooks] 即可，无需改动任何共享 Hook 代码。
 */
object ColorOsVendor : SystemHookVendor {
    override val id = VendorScheme.COLOROS.id
    override val family = RomFamily.COLOROS
    override val priority = 90

    /** 识别规则与 App 共用，见 core-geo 的 RomRules */
    override fun matches(profile: VendorProfile) = profile.family == family

    override fun classCandidates(component: SystemComponent): List<String> = emptyList()
}
