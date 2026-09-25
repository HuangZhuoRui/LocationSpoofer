@file:Suppress("SpellCheckingInspection", "unused")

package com.suseoaa.locationspoofer.xposed.hooks.vendor.profiles

import com.suseoaa.locationspoofer.xposed.hooks.vendor.RomFamily
import com.suseoaa.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.suseoaa.locationspoofer.xposed.hooks.vendor.SystemHookVendor
import com.suseoaa.locationspoofer.xposed.hooks.vendor.VendorProfile

/**
 * 三星：One UI 适配器（**模板 / 待实机验证**）。
 *
 * 与 [ColorOsVendor] 同理，目前只负责自报为 oneui、完全走 AOSP 基线，是留给后续维护者的骨架。
 * 三星在系统服务里的定制通常较多，拿到实机后大概率需要在这里补充定制类名候选或额外 Hook，
 * 具体做法见 [ADAPTATION_GUIDE](../../../../../../../../../ADAPTATION_GUIDE.md)。
 */
object OneUiVendor : SystemHookVendor {
    override val id = "oneui"
    override val family = RomFamily.ONEUI
    override val priority = 90

    override fun matches(profile: VendorProfile): Boolean {
        if (profile.hasProp("ro.build.version.oneui")) return true
        val m = profile.manufacturer.lowercase()
        val b = profile.brand.lowercase()
        return m == "samsung" || b == "samsung"
    }

    override fun classCandidates(component: SystemComponent): List<String> = emptyList()
}
