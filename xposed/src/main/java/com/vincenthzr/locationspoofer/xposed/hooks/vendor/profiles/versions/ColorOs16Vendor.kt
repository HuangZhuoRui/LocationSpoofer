package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemVersionVendor
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.ColorOsVendor

/** 仅覆盖 ColorOS 16 的框架差异，定位、环境数据和诊断继续走统一挂载路径。 */
object ColorOs16Vendor : SystemVersionVendor(ColorOsVendor) {
    override val id = "coloros16"
    override val usesFrameworkBleDelivery = true
    override val usesFrameworkLocationDelivery = true
    override val requiresVirtualLocationOptIn = true

    override fun matchesVersion(profile: VendorProfile): Boolean =
        com.vincenthzr.locationspoofer.vendor.RomRules.isColorOs16(profile)

    override fun classCandidates(component: SystemComponent): List<String> = when (component) {
        SystemComponent.BLUETOOTH_SCAN_SERVICE -> listOf(
            "com.android.bluetooth.le_scan.TransitionalScanHelper"
        )
        SystemComponent.CONNECTIVITY_SERVICE -> listOf(
            "android.net.connectivity.com.android.server.ConnectivityService"
        )
        else -> super.classCandidates(component)
    }

    override fun installExtraHooks(hooker: LocationHooker, classLoader: ClassLoader) {
        super.installExtraHooks(hooker, classLoader)
        if (hooker.isBluetoothProcessInstance) {
            val pendingIntent = ColorOs16BleDelivery(hooker, classLoader)
            hooker.vendorExtraHooks.add(pendingIntent)
            pendingIntent.install()
        }
        if (hooker.isSystemServerProcess) {
            val extra = ColorOs16LocationAvailability(hooker, classLoader)
            hooker.vendorExtraHooks.add(extra)
            extra.install()
            val delivery = ColorOs16LocationDelivery(hooker, classLoader)
            hooker.vendorExtraHooks.add(delivery)
            delivery.install()
            val sensors = com.vincenthzr.locationspoofer.xposed.hooks.SystemAccelHooker(hooker)
            hooker.vendorExtraHooks.add(sensors)
            sensors.install()
            val steps = com.vincenthzr.locationspoofer.xposed.hooks.SystemStepHooker(hooker)
            hooker.vendorExtraHooks.add(steps)
            steps.install()
        }
    }
}
