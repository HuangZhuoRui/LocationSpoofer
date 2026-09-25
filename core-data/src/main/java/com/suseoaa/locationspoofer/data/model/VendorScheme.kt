package com.suseoaa.locationspoofer.data.model

/**
 * App 设置页"厂商适配方案"可供用户手动选择的选项。
 *
 * [id] 必须和 xposed 模块里 `vendor/profiles/` 下对应 `SystemHookVendor.id` 的取值逐字一致——
 * 两者分属不同 Gradle 模块（core-data 不依赖 xposed），无法直接共享同一个类型，只能保持字符串同步。
 * `AUTO` 是默认值，表示交给 `VendorRegistry` 按设备属性自动识别；其余选项对应一个具体的
 * `SystemHookVendor` 实现，选中后会跳过自动识别、强制使用该适配器（即使 `matches()` 判断不通过）。
 *
 * 新增一个厂商适配器（`xposed/.../vendor/profiles/XxxVendor.kt`）后，如果希望用户能在设置页里手动
 * 选中它，记得在这里同步加一项——反过来，`xposed/.../vendor/profiles/versions/` 下的系统版本级
 * 适配器（继承 `SystemVersionVendor`）目前不在这里暴露，用户手动选择只精确到"厂商"这一层。
 */
enum class VendorScheme(val id: String) {
    AUTO("auto"),
    HYPEROS("hyperos"),
    COLOROS("coloros"),
    ONEUI("oneui"),
    AOSP("aosp");

    companion object {
        fun fromId(id: String?): VendorScheme = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
