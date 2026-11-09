package com.vincenthzr.locationspoofer.utils

import android.content.Context
import android.content.pm.PackageManager
import com.vincenthzr.locationspoofer.data.model.AppInfoItem

class LSPosedManager {
    fun isModuleActive(): Boolean {
        return XposedModuleStatus.isModuleActive.value
    }

    fun getHookedApps(context: Context): List<AppInfoItem> {
        val scope = XposedModuleStatus.mService?.scope ?: emptyList()
        val pm = context.packageManager
        return scope.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                val isSystem =
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                AppInfoItem(pkg, label, isSystem)
            } catch (e: Exception) {
                AppInfoItem(pkg, pkg, false)
            }
        }.sortedBy { it.appName }
    }

    /**
     * 枚举设备上所有已安装 App（不局限于 LSPosed 模块作用域勾选的那部分），
     * 供"系统级模拟应用"选择页使用 —— 该页面选中的包名只是写进 system_hook_packages
     * 配置项，由 system_server 里的 Hook 自己按 Binder 调用方 UID 反查包名做匹配，
     * 完全不依赖这些 App 本身有没有被加入 LSPosed 模块作用域。
     */
    fun getAllInstalledApps(context: Context): List<AppInfoItem> {
        val pm = context.packageManager
        val selfPackage = context.packageName
        return pm.getInstalledApplications(0).mapNotNull { info ->
            if (info.packageName == selfPackage) return@mapNotNull null
            try {
                val label = pm.getApplicationLabel(info).toString()
                val isSystem =
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                AppInfoItem(info.packageName, label, isSystem)
            } catch (e: Exception) {
                AppInfoItem(info.packageName, info.packageName, false)
            }
        }.sortedBy { it.appName }
    }
}
