package com.suseoaa.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MacVendorHelperTest {

    @Test
    fun `getVendor matches known OUI`() {
        assertEquals("Dell", MacVendorHelper.getVendor("00:14:22:AA:BB:CC"))
        assertEquals("Apple", MacVendorHelper.getVendor("3c:07:54:11:22:33"))
    }

    @Test
    fun `getVendor is case-insensitive and trims whitespace`() {
        assertEquals("Dell", MacVendorHelper.getVendor("00:14:22:AA:BB:CC".uppercase()))
        assertEquals("Dell", MacVendorHelper.getVendor("  00:14:22:AA:BB:CC  "))
    }

    @Test
    fun `getVendor returns Unknown for null, blank, short or unmatched input`() {
        assertEquals("Unknown", MacVendorHelper.getVendor(null))
        assertEquals("Unknown", MacVendorHelper.getVendor(""))
        assertEquals("Unknown", MacVendorHelper.getVendor("1234"))
        assertEquals("Unknown", MacVendorHelper.getVendor("ff:ff:ff:00:00:00"))
    }

    @Test
    fun `frequencyToChannel maps standard 2point4GHz frequencies to correct channel`() {
        assertEquals(1, MacVendorHelper.frequencyToChannel(2412))
        assertEquals(2, MacVendorHelper.frequencyToChannel(2417))
        assertEquals(6, MacVendorHelper.frequencyToChannel(2437))
        assertEquals(11, MacVendorHelper.frequencyToChannel(2462))
        assertEquals(13, MacVendorHelper.frequencyToChannel(2472))
        assertEquals(14, MacVendorHelper.frequencyToChannel(2484))
    }

    @Test
    fun `frequencyToChannel maps standard 5GHz frequencies to correct channel`() {
        assertEquals(34, MacVendorHelper.frequencyToChannel(5170))
        assertEquals(36, MacVendorHelper.frequencyToChannel(5180))
        assertEquals(165, MacVendorHelper.frequencyToChannel(5825))
    }

    @Test
    fun `frequencyToChannel returns 0 for out-of-range frequencies`() {
        assertEquals(0, MacVendorHelper.frequencyToChannel(1000))
        assertEquals(0, MacVendorHelper.frequencyToChannel(2400))
        assertEquals(0, MacVendorHelper.frequencyToChannel(2473))
        assertEquals(0, MacVendorHelper.frequencyToChannel(5826))
        assertEquals(0, MacVendorHelper.frequencyToChannel(6000))
    }
}
