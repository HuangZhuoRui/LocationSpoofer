@file:Suppress("SpellCheckingInspection", "unused", "UnusedImport")

package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemVersionVendor
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.HyperOsVendor

/**
 * 系统大版本适配器模板：演示如何在厂商级适配器（这里以 [HyperOsVendor] 为例）基础上，
 * 针对该厂商系统的某个大版本跨度（比如 HyperOS 3 升到 HyperOS 4 这种跨版本更新）追加专属规则。
 *
 * **这是一个安全的、默认不生效的模板，不是真实适配。** [matchesVersion] 恒返回 `false`——已经把它
 * 注册进 [com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry.ALL] 也不会对任何真实设备
 * 产生任何影响，可以安全地留在仓库里当范例。
 *
 * ## 怎么把它改成真正的适配
 *
 * 1. **不要直接改这份模板**，先复制一份改名成具体版本（如 `HyperOs4Vendor.kt`），
 *    放在同一个 `vendor/profiles/versions/` 目录下——这样以后 HyperOS 5、6 需要单独适配时，
 *    这份带完整说明的模板还在，可以继续复制。
 * 2. 在目标版本的真机上跑，拿到真实的版本号属性原始值（**不要凭空假设格式**）：
 *    ```bash
 *    adb shell getprop ro.mi.os.version.name
 *    adb shell getprop ro.mi.os.version.code
 *    adb shell getprop ro.build.version.sdk
 *    ```
 *    把实测确认过的判定逻辑写进 [matchesVersion]（可以用基类的 [extractLeadingMajorVersion] 辅助
 *    提取主版本号，也可以直接用 `profile.prop(...)` 做字符串匹配，取决于真实格式）。
 * 3. 把 [id] 改成一眼能看出对应版本的字符串（如 `"hyperos-4"`）。
 * 4. 按 `ADAPTATION_GUIDE.md` 的方法在真机上反编译确认差异后，按需取消下面三个方法的注释并填入
 *    真实内容；没有差异的方法保持注释掉（继承 [SystemVersionVendor] 默认的"透传给 parent"行为）。
 * 5. 到 `VendorRegistry.ALL` 列表里注册新文件（和厂商级适配器同一个列表）。
 * 6. 把上面这段"这是一份模板"的说明也一并删掉或改写，避免误导后来者。
 */
object SystemVersionVendorTemplate : SystemVersionVendor(parent = HyperOsVendor) {

    override val id = "system-version-template" // TODO: 改成具体版本名，如 "hyperos-4"

    /**
     * TODO: 换成真实的版本判定逻辑。下面两种写法都只是示例，实测确认属性格式后二选一或自己写：
     *
     * ```kotlin
     * // 写法一：解析形如 "OS4.0.12.0.XXXCNXM" 的版本号字符串，取开头的整数主版本号
     * override fun matchesVersion(profile: VendorProfile): Boolean {
     *     val major = extractLeadingMajorVersion(profile.prop("ro.mi.os.version.name")) ?: return false
     *     return major == 4
     * }
     *
     * // 写法二：如果版本号格式不是"数字开头"，直接做字符串匹配
     * override fun matchesVersion(profile: VendorProfile): Boolean =
     *     profile.prop("ro.mi.os.version.name").startsWith("OS4.")
     * ```
     */
    override fun matchesVersion(profile: VendorProfile): Boolean = false // TODO: 替换成真实判定逻辑

    // 范例：假设这个大版本上某个系统服务换了类名（真实项目里把示例类名换成反编译确认过的真实类名；
    // 如果这个版本在这个组件上和 parent 没有差异，就不要覆写、保持透传）。
    // override fun classCandidates(component: SystemComponent): List<String> = when (component) {
    //     SystemComponent.WIFI_SERVICE -> listOf("com.example.HyperOs4WifiServiceImplVariant") + super.classCandidates(component)
    //     else -> super.classCandidates(component)
    // }

    // 范例：假设这个大版本额外预装了一个需要豁免的系统包（同样，换成 dumpsys/反编译确认过的真实包名）。
    // override fun additionalExemptPackages(): Set<String> =
    //     super.additionalExemptPackages() + setOf("com.example.version.specific.service")

    // 范例：追加只在这个大版本上才需要的定制 Hook。注意一定要先调用 super，否则父适配器
    // （parent，这里是 HyperOsVendor）的额外 Hook 不会被执行。
    // override fun installExtraHooks(hooker: LocationHooker, classLoader: ClassLoader) {
    //     super.installExtraHooks(hooker, classLoader)
    //     // TODO: 本版本专属的额外 Hook
    // }
}
