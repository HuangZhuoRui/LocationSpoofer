package com.vincenthzr.locationspoofer.vendor

import org.junit.Assert.assertEquals
import org.junit.Test

class RomRulesTest {

    private fun profile(manufacturer: String, brand: String = manufacturer, release: String = "15", prop: Pair<String, String>? = null) =
        VendorProfile(manufacturer, brand, "model", "device", 35, release, listOfNotNull(prop).toMap())

    @Test
    fun `recognizes each rom family by its own properties`() {
        val cases = mapOf(
            profile("Xiaomi", release = "17", prop = "ro.mi.os.version.name" to "OS4.0") to RomFamily.HYPEROS_MIUI,
            profile("unknown", prop = "ro.miui.ui.version.name" to "V140") to RomFamily.HYPEROS_MIUI,
            profile("unknown", prop = "ro.build.version.oplusrom" to "V15.0.0") to RomFamily.COLOROS,
            profile("unknown", prop = "ro.build.version.oneui" to "60100") to RomFamily.ONEUI,
            profile("unknown", prop = "ro.vivo.os.version" to "4.0") to RomFamily.ORIGINOS_FUNTOUCH,
            profile("unknown", prop = "ro.build.version.magic" to "8.0") to RomFamily.MAGICOS_EMUI,
            profile("unknown", prop = "ro.flyme.published" to "true") to RomFamily.FLYME,
            profile("Google", "google") to RomFamily.AOSP,
        )
        for ((p, family) in cases) assertEquals(p.toString(), family, p.family)
    }

    @Test
    fun `falls back to brand names when rom properties are stripped`() {
        assertEquals(RomFamily.HYPEROS_MIUI, profile("Xiaomi", "Redmi").family)
        assertEquals(RomFamily.HYPEROS_MIUI, profile("Xiaomi", "POCO").family)
        assertEquals(RomFamily.COLOROS, profile("OnePlus").family)
        assertEquals(RomFamily.COLOROS, profile("realme").family)
        assertEquals(RomFamily.ONEUI, profile("samsung").family)
        assertEquals(RomFamily.ORIGINOS_FUNTOUCH, profile("vivo", "iQOO").family)
        assertEquals(RomFamily.MAGICOS_EMUI, profile("HONOR").family)
        assertEquals(RomFamily.FLYME, profile("Meizu").family)
    }

    @Test
    fun `builds a readable system label`() {
        assertEquals("HyperOS 4.0 · Android 17", profile("Xiaomi", release = "17", prop = "ro.mi.os.version.name" to "OS4.0").systemLabel)
        assertEquals("MIUI V140 · Android 13", profile("Xiaomi", release = "13", prop = "ro.miui.ui.version.name" to "V140").systemLabel)
        assertEquals("One UI 6.1 · Android 14", profile("samsung", release = "14", prop = "ro.build.version.oneui" to "60100").systemLabel)
        assertEquals("ColorOS 15.0.0 · Android 15", profile("OPPO", prop = "ro.build.version.oplusrom" to "V15.0.0").systemLabel)
        assertEquals("Flyme · Android 15", profile("Meizu").systemLabel)
        assertEquals("Android 15", profile("Google").systemLabel)
    }

    @Test
    fun `every family maps to a concrete adapter scheme`() {
        for (family in RomFamily.entries) {
            val scheme = VendorScheme.forFamily(family)
            assert(scheme != VendorScheme.AUTO) { "$family -> AUTO" }
        }
        assertEquals(VendorScheme.HYPEROS, VendorScheme.fromId("hyperos"))
        assertEquals(VendorScheme.AUTO, VendorScheme.fromId("no-such-id"))
    }
}
