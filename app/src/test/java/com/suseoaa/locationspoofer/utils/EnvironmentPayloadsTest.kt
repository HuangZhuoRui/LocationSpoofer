package com.suseoaa.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentPayloadsTest {
    @Test
    fun wifiObjectCountsNearbyAndConnectedData() {
        val payload = """
            {
              "connectedWifi": {"bssid": "00:11:22:33:44:55"},
              "nearbyWifi": [{"bssid": "66:77:88:99:aa:bb"}]
            }
        """.trimIndent()

        val info = EnvironmentPayloads.inspectWifi(payload)

        assertTrue(info.hasData)
        assertEquals(1, info.nearbyCount)
    }

    @Test
    fun connectedOnlyWifiObjectIsUsable() {
        val info = EnvironmentPayloads.inspectWifi(
            """{"connectedWifi":{"bssid":"00:11:22:33:44:55"},"nearbyWifi":[]}"""
        )

        assertTrue(info.hasData)
        assertEquals(0, info.nearbyCount)
    }

    @Test
    fun nearbyOnlyWifiObjectIsUsable() {
        val info = EnvironmentPayloads.inspectWifi(
            """{"connectedWifi":null,"nearbyWifi":[{"bssid":"00:11:22:33:44:55"}]}"""
        )

        assertTrue(info.hasData)
        assertEquals(1, info.nearbyCount)
    }

    @Test
    fun emptyWifiObjectIsNotUsable() {
        val info = EnvironmentPayloads.inspectWifi(
            """{"connectedWifi":null,"nearbyWifi":[]}"""
        )

        assertFalse(info.hasData)
        assertEquals(0, info.nearbyCount)
    }

    @Test
    fun wifiArrayIsNotAcceptedAsConfigObject() {
        val info = EnvironmentPayloads.inspectWifi("[{}]")

        assertFalse(info.hasData)
        assertEquals(0, info.nearbyCount)
    }

    @Test
    fun arrayPayloadRequiresAtLeastOneItem() {
        assertTrue(EnvironmentPayloads.hasArrayItems("[{}]"))
        assertFalse(EnvironmentPayloads.hasArrayItems("[]"))
        assertFalse(EnvironmentPayloads.hasArrayItems("{}"))
    }

    @Test
    fun emptySavedPayloadInheritsNonEmptyLocalCache() {
        val merged = EnvironmentPayloads.merge(
            localWifiJson = """{"nearbyWifi":[{}]}""",
            localCellJson = "[{}]",
            localBluetoothJson = "[{}]",
            wifiJsonOverride = EnvironmentPayloads.usableWifiOverride("[]"),
            cellJsonOverride = EnvironmentPayloads.usableArrayOverride("[]"),
            bluetoothJsonOverride = EnvironmentPayloads.usableArrayOverride("[]")
        )

        assertEquals("""{"nearbyWifi":[{}]}""", merged.wifiJson)
        assertEquals("[{}]", merged.cellJson)
        assertEquals("[{}]", merged.bluetoothJson)
    }

    @Test
    fun nonEmptySavedPayloadOverridesLocalCachePerChannel() {
        val savedWifi = """{"connectedWifi":{"bssid":"00:11:22:33:44:55"},"nearbyWifi":[]}"""
        val merged = EnvironmentPayloads.merge(
            localWifiJson = "local-wifi",
            localCellJson = "local-cell",
            localBluetoothJson = "local-bluetooth",
            wifiJsonOverride = EnvironmentPayloads.usableWifiOverride(savedWifi),
            cellJsonOverride = EnvironmentPayloads.usableArrayOverride("[{}]"),
            bluetoothJsonOverride = EnvironmentPayloads.usableArrayOverride("[{}]")
        )

        assertEquals(savedWifi, merged.wifiJson)
        assertEquals("[{}]", merged.cellJson)
        assertEquals("[{}]", merged.bluetoothJson)
    }

    @Test
    fun absentOverrideInheritsLocalCache() {
        val merged = EnvironmentPayloads.merge(
            localWifiJson = "local-wifi",
            localCellJson = "local-cell",
            localBluetoothJson = "local-bluetooth",
            wifiJsonOverride = null,
            cellJsonOverride = null,
            bluetoothJsonOverride = null
        )

        assertEquals("local-wifi", merged.wifiJson)
        assertEquals("local-cell", merged.cellJson)
        assertEquals("local-bluetooth", merged.bluetoothJson)
    }
}
