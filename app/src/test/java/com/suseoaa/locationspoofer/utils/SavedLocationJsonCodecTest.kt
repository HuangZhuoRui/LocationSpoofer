package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.SavedLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedLocationJsonCodecTest {
    @Test
    fun roundTripPreservesEnvironmentPayloads() {
        val original = SavedLocation(
            name = "saved",
            lat = 39.9042,
            lng = 116.4074,
            wifiJson = "{\"connectedWifi\":null,\"nearbyWifi\":[{}]}",
            cellJson = "[{\"mcc\":460}]",
            bluetoothJson = "[{\"address\":\"00:11:22:33:44:55\"}]"
        )

        val decoded = SavedLocationJsonCodec.decode(SavedLocationJsonCodec.encode(listOf(original)))

        assertEquals(listOf(original), decoded)
    }

    @Test
    fun legacySavedLocationDefaultsMissingPayloads() {
        val decoded = SavedLocationJsonCodec.decode(
            "[{\"name\":\"legacy\",\"lat\":1.0,\"lng\":2.0}]"
        ).single()

        assertEquals("[]", decoded.wifiJson)
        assertEquals("[]", decoded.cellJson)
        assertEquals("[]", decoded.bluetoothJson)
    }
}
