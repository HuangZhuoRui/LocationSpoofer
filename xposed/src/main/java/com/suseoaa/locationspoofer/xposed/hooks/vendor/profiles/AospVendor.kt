@file:Suppress("SpellCheckingInspection", "unused")

package com.suseoaa.locationspoofer.xposed.hooks.vendor.profiles

import com.suseoaa.locationspoofer.xposed.hooks.vendor.RomFamily
import com.suseoaa.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.suseoaa.locationspoofer.xposed.hooks.vendor.SystemHookVendor
import com.suseoaa.locationspoofer.xposed.hooks.vendor.VendorProfile

/**
 * AOSP 基线适配器：所有机型的兜底，[matches] 恒为 `true`、[priority] 恒为最低。
 *
 * 这里集中维护"原生 Android 上各系统服务实现类的标准候选名"。它同时充当两个角色：
 * 1. 在原生 / 接近原生系统（Pixel、LineageOS 等）上作为实际生效的适配器；
 * 2. 在任何 OEM 适配器命中时，作为 [com.suseoaa.locationspoofer.xposed.hooks.vendor.VendorRegistry]
 *    自动追加的基线候选来源——所以即便某个 OEM 适配器只覆盖了个别组件，其余组件仍能回落到这里。
 *
 * 维护约定：**新增系统版本导致的原生类名变化优先加在这里**（作为新候选追加，不要删旧的，以免影响老设备），
 * 只有当某个类名是某家 OEM 独有的定制时，才放到对应的 OEM 适配器里。
 */
object AospVendor : SystemHookVendor {
    override val id = "aosp"
    override val family = RomFamily.AOSP
    override val priority = Int.MIN_VALUE
    override fun matches(profile: VendorProfile) = true

    override fun classCandidates(component: SystemComponent): List<String> = when (component) {
        SystemComponent.LOCATION_MANAGER_SERVICE -> listOf(
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService",
        )
        SystemComponent.LOCATION_PROVIDER_MANAGER -> listOf(
            "com.android.server.location.provider.LocationProviderManager",
        )
        SystemComponent.WIFI_SERVICE -> listOf(
            "com.android.server.wifi.WifiServiceImpl",
            "com.android.server.WifiService",
        )
        SystemComponent.WIFI_SCANNER_SERVICE -> listOf(
            "com.android.server.wifi.scanner.WifiScanningServiceImpl",
        )
        SystemComponent.CONNECTIVITY_SERVICE -> listOf(
            "com.android.server.ConnectivityService",
            "com.android.server.connectivity.ConnectivityService",
        )
        SystemComponent.TELEPHONY_PHONE_MANAGER -> listOf(
            "com.android.internal.telephony.PhoneInterfaceManager",
            "com.android.server.telephony.PhoneInterfaceManager",
            "com.android.phone.PhoneInterfaceManager",
        )
        SystemComponent.TELEPHONY_REGISTRY -> listOf(
            "com.android.server.TelephonyRegistry",
        )
        SystemComponent.APPOPS_SERVICE -> listOf(
            "com.android.server.appop.AppOpsService",
            "com.android.server.AppOpsService",
        )
        SystemComponent.BLUETOOTH_GATT_SERVICE -> listOf(
            "com.android.bluetooth.gatt.GattService",
            "com.android.bluetooth.btservice.AdapterService",
        )
    }
}
