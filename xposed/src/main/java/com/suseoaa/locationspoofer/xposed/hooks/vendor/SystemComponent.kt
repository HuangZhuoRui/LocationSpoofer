@file:Suppress("SpellCheckingInspection", "unused")

package com.suseoaa.locationspoofer.xposed.hooks.vendor

/**
 * 需要跨机型/系统解析的系统内部组件枚举。
 *
 * 每一项代表一个"在不同 Android 版本 / OEM 定制 ROM 上，实现类的包名可能不一样"的系统服务
 * （或其关键内部类）。厂商适配器（[SystemHookVendor]）针对这些枚举项各自给出自己已知的候选
 * 类名，共享 Hook 代码则通过 [VendorRegistry.resolveClass] 按"当前机型候选 → AOSP 基线候选"
 * 的顺序去解析，而不是把各家类名硬编码堆在同一个函数里。
 *
 * 新增一个需要按机型区分类名的系统组件时：
 * 1. 在这里加一个枚举项；
 * 2. 在 [com.suseoaa.locationspoofer.xposed.hooks.vendor.profiles.AospVendor] 里补上它的 AOSP 基线候选；
 * 3. 各 OEM 适配器按需在自己的 `classCandidates` 里追加该项的定制候选。
 */
enum class SystemComponent {
    /** system_server：LocationManagerService（定位服务总入口） */
    LOCATION_MANAGER_SERVICE,

    /** system_server：LocationProviderManager（Android 12+ 底层 provider 管理器） */
    LOCATION_PROVIDER_MANAGER,

    /** system_server / wifi APEX：WifiServiceImpl */
    WIFI_SERVICE,

    /** system_server / wifi APEX：WifiScanningServiceImpl（getSingleScanResults） */
    WIFI_SCANNER_SERVICE,

    /** system_server / tethering APEX：ConnectivityService */
    CONNECTIVITY_SERVICE,

    /** com.android.phone 进程：PhoneInterfaceManager */
    TELEPHONY_PHONE_MANAGER,

    /** system_server：TelephonyRegistry（notifyCellInfo 等主动派发入口） */
    TELEPHONY_REGISTRY,

    /** system_server：AppOpsService（OP_MOCK_LOCATION 反检测） */
    APPOPS_SERVICE,

    /** com.android.bluetooth 进程 / bluetooth APEX：GattService（BLE 扫描） */
    BLUETOOTH_GATT_SERVICE,
}
