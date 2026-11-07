package com.vincenthzr.locationspoofer.vendor

/**
 * 全局方案的厂商适配方案：App 设置页"厂商适配方案"的选项，也是 xposed 模块里厂商级适配器
 * （`SystemHookVendor`）的 [id] 来源——两边都引用这里，id 不会再各写一份而失去同步。
 *
 * [AUTO] 表示按设备属性自动识别（见 [RomRules]）；其余每一项对应 xposed 模块 `vendor/profiles/` 下的
 * 一个厂商级适配器，用户手动选中后跳过自动识别、强制使用该适配器。
 * [id] 会被持久化到用户设置里，发布后不要改名。
 *
 * 新增一个厂商级适配器时在这里加一项，并在 [forFamily] 里把对应的 [RomFamily] 指过来；
 * xposed 模块的单元测试会检查每一项都有同 id 的适配器、且自动识别选中的适配器与 [forFamily] 一致。
 */
enum class VendorScheme(val id: String) {
    AUTO("auto"),
    HYPEROS("hyperos"),
    COLOROS("coloros"),
    ONEUI("oneui"),
    AOSP("aosp");

    companion object {
        fun fromId(id: String?): VendorScheme = entries.firstOrNull { it.id == id } ?: AUTO

        /** 自动识别时某个系统家族会用到的适配器；还没有专用适配器的家族落到 AOSP 基线 */
        fun forFamily(family: RomFamily): VendorScheme = when (family) {
            RomFamily.HYPEROS_MIUI -> HYPEROS
            RomFamily.COLOROS -> COLOROS
            RomFamily.ONEUI -> ONEUI
            RomFamily.ORIGINOS_FUNTOUCH,
            RomFamily.MAGICOS_EMUI,
            RomFamily.FLYME,
            RomFamily.AOSP -> AOSP
        }
    }
}
