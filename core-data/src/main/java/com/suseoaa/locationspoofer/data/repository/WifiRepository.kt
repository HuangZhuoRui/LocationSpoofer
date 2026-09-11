package com.suseoaa.locationspoofer.data.repository

import com.suseoaa.locationspoofer.utils.WigleClient

class WifiRepository(private val wigleClient: WigleClient) {

    suspend fun validateToken(token: String): Boolean = wigleClient.validateToken(token)

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        wigleClient.fetchWifiData(lat, lng, token)
}
