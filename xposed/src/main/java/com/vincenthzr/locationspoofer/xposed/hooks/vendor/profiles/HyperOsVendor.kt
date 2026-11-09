@file:Suppress("SpellCheckingInspection", "unused")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemHookVendor
import com.vincenthzr.locationspoofer.vendor.RomFamily
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.vendor.VendorScheme

/**
 * 小米 / 红米 / POCO：HyperOS 与旧版 MIUI 适配器。
 *
 * 这是本项目当前唯一在实体机上完整验证过的 OEM 适配器——小米 17 Pro Max / HyperOS 4 上，
 * 定位（GPS/网络/被动 provider）、Wi-Fi 扫描结果与已连接信息、基站信息、AppOps 反检测、
 * `ConnectivityService` NetworkCapabilities 脱敏全部实测生效（各目标应用的兼容情况见仓库根目录
 * ADAPTATION_PROGRESS.md），也是给后续维护者照着写新 OEM 适配器的"标准范例"。
 *
 * 现状分两部分，分别对应下面两个覆写点：
 * 1. [classCandidates] 返回空——HyperOS/MIUI 对本项目所 Hook 的这几个 **framework 系统服务**
 *    （`LocationManagerService` / `WifiServiceImpl` / `PhoneInterfaceManager` 等）实测沿用了 AOSP
 *    原生类名，完全走基线候选即可命中，这是"验证后确认无需覆盖"，不是"还没填"。
 * 2. [additionalExemptPackages] **不是空的**——HyperOS/MIUI 有自己的一整套定位融合与场景感知
 *    服务，这些包名如果被当成"目标应用"塞进假坐标，轻则让系统自身的定位链路互相打架（表现为
 *    地图 App 坐标漂移抖动），重则触发 MIUI 自身的异常检测。这部分是从早期版本里"和所有机型通用
 *    豁免名单混在一起"的写法迁移过来的——之所以要单独拆到这个文件，就是为了不让下一个要适配的
 *    厂商（ColorOS/One UI/…）在通用名单里看到一堆读不懂由来的小米包名。
 */
object HyperOsVendor : SystemHookVendor {
    override val id = VendorScheme.HYPEROS.id
    override val family = RomFamily.HYPEROS_MIUI
    override val priority = 100

    /**
     * 识别规则与 App 共用，见 core-geo 的 RomRules：HyperOS 暴露 ro.mi.os.version.name、旧 MIUI 暴露
     * ro.miui.ui.version.name，任一存在即判定为本家族，属性被裁剪时再按 xiaomi / redmi / poco 品牌兜底。
     */
    override fun matches(profile: VendorProfile) = profile.family == family

    /**
     * 已在 HyperOS 4 实机上逐一验证：本项目 Hook 的全部系统服务
     * （`LocationManagerService`、`LocationProviderManager`、`WifiServiceImpl`、
     * `WifiScanningServiceImpl`、`ConnectivityService`、`PhoneInterfaceManager`、
     * `TelephonyRegistry`、`AppOpsService`）均可直接用 AOSP 基线候选命中，无需任何覆盖。
     * 将来若在更高版本 HyperOS 上发现某组件换了实现类，只需在这里对应枚举项返回定制候选，
     * [com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry] 会自动在其后接上 AOSP 基线兜底，
     * 不影响这里已验证 OK 的其它组件。
     */
    override fun classCandidates(component: SystemComponent): List<String> = emptyList()

    /**
     * HyperOS/MIUI 自有的定位融合 / 场景感知 / 省电策略服务，绝不能被当作模拟目标：
     *
     * - `com.xiaomi.metoknlp`：小米自研的网络位置融合服务（MetokNLP），系统内部会拿它的结果和
     *   GPS 结果做融合校验，喂假坐标进去会导致融合结果坐标漂移/抖动，反而弄巧成拙。
     * - `com.xiaomi.location.fused`：HyperOS 对 Fused Location Provider 的自有实现（对应
     *   AOSP 原生的 `com.android.location.fused`，那条已经在通用基线里）。
     * - `com.xiaomi.aicr`：小米 AI 场景识别服务，会主动请求真实定位用于场景判断（比如自动亮度/
     *   省电策略），不应被污染。
     * - `com.xiaomi.hypercomm`：HyperOS 跨端互联服务，涉及"查找设备"等依赖真实定位的功能。
     * - `com.miui.powerinsight` / `com.miui.powerkeeper`：MIUI/HyperOS 的省电策略与耗电分析组件，
     *   会读取位置信息用于地理围栏类省电策略，同理不应被污染。
     */
    override fun additionalExemptPackages(): Set<String> = setOf(
        "com.xiaomi.metoknlp",
        "com.xiaomi.location.fused",
        "com.xiaomi.aicr",
        "com.xiaomi.hypercomm",
        "com.miui.powerinsight",
        "com.miui.powerkeeper",
    )

    /**
     * MIUI/HyperOS 专属额外 Hook 的挂载点。
     *
     * 当前 MIUI 位置模拟检测的规避逻辑（隐藏 `mock_location` AppOps、清除 Xposed 调用栈痕迹等）
     * 是跨机型通用行为，仍集中在 [com.vincenthzr.locationspoofer.xposed.hooks.AntiDetectionHooker] 与
     * [com.vincenthzr.locationspoofer.xposed.hooks.SystemAppOpsHooker] 中无条件执行，没有迁移到这里——
     * 因为那些检测点（AppOps 查询、调用栈清洗）是 AOSP 标准机制，其它厂商同样会用到，不是
     * HyperOS 独有的，所以不适合当成"厂商专属额外 Hook"。
     *
     * 这里保持空实现，留给未来发现的、**确认只在 HyperOS/MIUI 上才需要**的定制 Hook
     * （例如安全中心专属的"位置模拟中"状态查询点，一旦定位到具体系统类再补充）。
     */
    override fun installExtraHooks(hooker: LocationHooker, classLoader: ClassLoader) {
        // 预留：HyperOS 专属额外 Hook（目前没有已确认需要的项，见上方说明）。
    }
}
