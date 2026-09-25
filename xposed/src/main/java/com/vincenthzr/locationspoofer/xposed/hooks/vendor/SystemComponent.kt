@file:Suppress("SpellCheckingInspection", "unused")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor

/**
 * 全局方案会注入的系统进程。适配器只能改"在哪个类里 Hook"，改不了"在哪个进程里 Hook"——
 * 如果某个 ROM 把定位等服务挪到了这三个之外的进程，除了这里加一项，还要同步修改
 * `xposed/src/global/resources/META-INF/xposed/scope.list`（LSPosed 作用域）与
 * `LocationHooker.handleSystemProcessGlobal` 里的进程判断。
 */
enum class SystemProcess {
    SYSTEM_SERVER,
    PHONE,
    BLUETOOTH,
}

/**
 * 需要跨机型/系统解析的系统内部组件枚举。
 *
 * 每一项代表一个"在不同 Android 版本 / OEM 定制 ROM 上，实现类的包名可能不一样"的系统服务
 * （或其关键内部类）。厂商适配器（[SystemHookVendor]）针对这些枚举项各自给出自己已知的候选
 * 类名，共享 Hook 代码则通过 [SystemClassLocator.locate]
 * 按"当前机型候选 → AOSP 基线候选"的顺序去解析，而不是把各家类名硬编码堆在同一个函数里。
 * 每个组件的查找结果和已挂载的方法会记录进 Hook 状态报告，App 的"系统适配 → Hook 运行状态"页可以直接看到。
 *
 * 新增一个需要按机型区分类名的系统组件时：
 * 1. 在这里加一个枚举项，注明它所在的进程；
 * 2. 在 [com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.AospVendor] 里补上它的 AOSP 基线候选；
 * 3. 共享 Hook 代码用 [SystemClassLocator.locate] 查找该组件的类；
 * 4. 各 OEM 适配器按需在自己的 `classCandidates` 里追加该项的定制候选；
 * 5. 在 App 的字符串资源里补上组件的显示名（`hook_component_<枚举名小写>`）。
 */
enum class SystemComponent(val process: SystemProcess) {
    /** LocationManagerService（定位服务总入口） */
    LOCATION_MANAGER_SERVICE(SystemProcess.SYSTEM_SERVER),

    /** LocationProviderManager（Android 12+ 底层 provider 管理器） */
    LOCATION_PROVIDER_MANAGER(SystemProcess.SYSTEM_SERVER),

    /** WifiServiceImpl（Android 12+ 在 wifi APEX 里） */
    WIFI_SERVICE(SystemProcess.SYSTEM_SERVER),

    /** WifiScanningServiceImpl（getSingleScanResults） */
    WIFI_SCANNER_SERVICE(SystemProcess.SYSTEM_SERVER),

    /** ConnectivityService（Android 12+ 在 tethering APEX 里） */
    CONNECTIVITY_SERVICE(SystemProcess.SYSTEM_SERVER),

    /** TelephonyRegistry（notifyCellInfo 等主动派发入口） */
    TELEPHONY_REGISTRY(SystemProcess.SYSTEM_SERVER),

    /** AppOpsService（OP_MOCK_LOCATION 反检测） */
    APPOPS_SERVICE(SystemProcess.SYSTEM_SERVER),

    /** PhoneInterfaceManager（基站信息查询） */
    TELEPHONY_PHONE_MANAGER(SystemProcess.PHONE),

    /**
     * BLE 扫描入口：Android 17 起是 le_scan.ScanBinder（注册与启动合并为 registerAndStartScan），
     * 更早的版本是 gatt.GattService（registerScanner + startScan 两步）
     */
    BLUETOOTH_SCAN_SERVICE(SystemProcess.BLUETOOTH),
}
