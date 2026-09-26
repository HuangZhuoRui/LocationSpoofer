package com.vincenthzr.locationspoofer.xposed.hooks.vendor

import com.vincenthzr.locationspoofer.vendor.VendorProfile
import org.junit.Assert.*
import org.junit.Test

class ColorOs16VendorTest {
    private fun profile(sdk: Int = 36, version: String = "V16.0.0") =
        VendorProfile("OnePlus", "OnePlus", "", "", sdk, "16",
            mapOf("ro.build.version.oplusrom" to version))

    @Test fun `only confirmed ROM major and API select version adapter`() {
        assertEquals("coloros16", VendorRegistry.select(profile(), null).id)
        for (p in listOf(profile(35), profile(37), profile(version = "V15.0.0"),
            profile(version = "V17.0.0"), profile(version = ""))) {
            assertEquals("coloros", VendorRegistry.select(p, null).id)
        }
    }

    @Test fun `manual family selection retains matching version and explicit aosp wins`() {
        assertEquals("coloros16", VendorRegistry.select(profile(), "coloros").id)
        assertEquals("aosp", VendorRegistry.select(profile(), "aosp").id)
        assertEquals("coloros", VendorRegistry.select(profile(35), "coloros").id)
    }

    @Test fun `framework BLE delivery excludes the generic engine only on supported versions`() {
        assertTrue(VendorRegistry.select(profile(), null).usesFrameworkBleDelivery)
        assertFalse(VendorRegistry.select(profile(35), null).usesFrameworkBleDelivery)
        assertFalse(VendorRegistry.select(profile(), "aosp").usesFrameworkBleDelivery)
    }

    @Test fun `only version adapter opts in to explicit virtual availability`() {
        assertTrue(VendorRegistry.select(profile(), null).requiresVirtualLocationOptIn)
        assertFalse(VendorRegistry.select(profile(35), null).requiresVirtualLocationOptIn)
        assertFalse(VendorRegistry.select(profile(), "aosp").requiresVirtualLocationOptIn)
    }
}
