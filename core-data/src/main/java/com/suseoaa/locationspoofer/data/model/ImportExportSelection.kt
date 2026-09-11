package com.suseoaa.locationspoofer.data.model

/**
 * 导入/导出时用户勾选了哪些分类。
 * API 密钥默认不勾选：分享场景下带上密钥等于泄露个人开发者配额，
 * 需要迁移设备的用户可以自己主动勾上。
 */
data class ImportExportSelection(
    val locations: Boolean = true,
    val savedLocations: Boolean = true,
    val savedRoutes: Boolean = true,
    val appCoordinateSystems: Boolean = true,
    val settings: Boolean = true,
    val apiKeys: Boolean = false
) {
    /** 一项都没勾时不应该允许继续 */
    val hasAny: Boolean
        get() = locations || savedLocations || savedRoutes ||
                appCoordinateSystems || settings || apiKeys
}

/**
 * 各分类的条目数量。
 * 导出时是"当前设备上有多少"，导入时是"文件里有多少"，对话框据此显示数量并禁用空分类。
 * settings / apiKeys 是标量配置，用 0/1 表示有没有。
 */
data class ImportExportCounts(
    val locations: Int = 0,
    val savedLocations: Int = 0,
    val savedRoutes: Int = 0,
    val appCoordinateSystems: Int = 0,
    val settings: Int = 0,
    val apiKeys: Int = 0
)
