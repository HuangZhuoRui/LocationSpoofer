package com.vincenthzr.locationspoofer.vendor

/**
 * 已知的 ROM 家族，由 [RomRules.familyOf] 从设备画像识别得出。
 * xposed 模块用它选择适配器，App 用它显示"本机系统"并在适配进度文档里查找本机那一行。
 */
enum class RomFamily(
    /** 界面上显示的系统名（HyperOS / MIUI 由 [RomRules.romName] 进一步区分） */
    val displayName: String,
    /** 在 ADAPTATION_PROGRESS.md "按系统"表格第一列里查找本家族所用的关键字 */
    val progressKeywords: List<String>
) {
    /** 原生 / 接近原生（Pixel、AOSP、LineageOS 等），也是所有机型的兜底基线 */
    AOSP("Android", listOf("AOSP")),

    /** 小米 / 红米 / POCO：HyperOS 与旧版 MIUI */
    HYPEROS_MIUI("HyperOS", listOf("HyperOS", "MIUI")),

    /** OPPO / OnePlus / realme：ColorOS / OxygenOS（新版已同源） */
    COLOROS("ColorOS", listOf("ColorOS", "OxygenOS")),

    /** 三星：One UI */
    ONEUI("One UI", listOf("One UI")),

    /** vivo / iQOO：OriginOS / Funtouch OS */
    ORIGINOS_FUNTOUCH("OriginOS", listOf("OriginOS")),

    /** 华为 / 荣耀：EMUI / MagicOS */
    MAGICOS_EMUI("MagicOS", listOf("MagicOS", "EMUI")),

    /** 魅族：Flyme */
    FLYME("Flyme", listOf("Flyme")),
}
