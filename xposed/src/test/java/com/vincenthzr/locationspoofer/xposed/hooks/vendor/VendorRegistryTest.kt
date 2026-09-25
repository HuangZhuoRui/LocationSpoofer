package com.vincenthzr.locationspoofer.xposed.hooks.vendor

import com.vincenthzr.locationspoofer.vendor.RomFamily
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import com.vincenthzr.locationspoofer.vendor.VendorScheme
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.AospVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorRegistryTest {

    /** 每个家族各一份典型的设备画像 */
    private val samples = mapOf(
        RomFamily.HYPEROS_MIUI to VendorProfile("Xiaomi", "Xiaomi", "", "", 36, "17", mapOf("ro.mi.os.version.name" to "OS4.0")),
        RomFamily.COLOROS to VendorProfile("OPPO", "OPPO", "", "", 35, "15", mapOf("ro.build.version.oplusrom" to "V15.0.0")),
        RomFamily.ONEUI to VendorProfile("samsung", "samsung", "", "", 34, "14", mapOf("ro.build.version.oneui" to "60100")),
        RomFamily.ORIGINOS_FUNTOUCH to VendorProfile("vivo", "vivo", "", "", 35, "15", mapOf("ro.vivo.os.version" to "5.0")),
        RomFamily.MAGICOS_EMUI to VendorProfile("HONOR", "HONOR", "", "", 35, "15", mapOf("ro.build.version.magic" to "9.0")),
        RomFamily.FLYME to VendorProfile("Meizu", "meizu", "", "", 35, "15", emptyMap()),
        RomFamily.AOSP to VendorProfile("Google", "google", "", "", 36, "16", emptyMap()),
    )

    @Test
    fun `samples cover every family`() {
        assertEquals(RomFamily.entries.toSet(), samples.keys)
        for ((family, profile) in samples) assertEquals(family, profile.family)
    }

    @Test
    fun `automatic selection matches what the app shows for every family`() {
        // App 的"系统适配"页用 VendorScheme.forFamily 显示本机会用哪个适配器，两边必须一致
        for ((family, profile) in samples) {
            assertEquals(family.name, VendorScheme.forFamily(family).id, VendorRegistry.select(profile, null).id)
        }
    }

    @Test
    fun `every selectable scheme has a registered adapter`() {
        val ids = VendorRegistry.ALL.map { it.id }
        for (scheme in VendorScheme.entries - VendorScheme.AUTO) {
            assertTrue("no adapter for ${scheme.id}", scheme.id in ids)
        }
        assertEquals("adapter ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `manual override wins and unknown ids fall back to automatic selection`() {
        val xiaomi = samples.getValue(RomFamily.HYPEROS_MIUI)
        assertEquals("aosp", VendorRegistry.select(xiaomi, "aosp").id)
        assertEquals("hyperos", VendorRegistry.select(xiaomi, "auto").id)
        assertEquals("hyperos", VendorRegistry.select(xiaomi, "no-such-adapter").id)
    }

    @Test
    fun `every component has an aosp baseline candidate`() {
        for (component in SystemComponent.entries) {
            assertTrue("$component has no AOSP candidate", AospVendor.classCandidates(component).isNotEmpty())
        }
        assertEquals(AospVendor, VendorRegistry.ALL.last())
    }
}
