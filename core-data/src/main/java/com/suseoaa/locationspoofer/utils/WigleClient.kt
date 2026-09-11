package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WigleClient {
    private val client = OkHttpClient()

    /** 验证 token 是否有效（调用 /profile/user 接口） */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        return@withContext try {
            val request = Request.Builder()
                .url("https://api.wigle.net/api/v2/profile/user")
                .addHeader("Authorization", "Basic $token")
                .addHeader("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext false
                org.json.JSONObject(body).optString("success") == "true"
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                return@withContext generateFallbackWifi()
            }

            val latrange1 = lat - 0.002
            val latrange2 = lat + 0.002
            val longrange1 = lng - 0.002
            val longrange2 = lng + 0.002

            val url =
                "https://api.wigle.net/api/v2/network/search?latrange1=$latrange1&latrange2=$latrange2&longrange1=$longrange1&longrange2=$longrange2"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Basic $token")
                .addHeader("Accept", "application/json")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext generateFallbackWifi()
                    val jsonObject = JSONObject(body)
                    val results = jsonObject.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        return@withContext generateFallbackWifi()
                    }

                    val wifiList = mutableListOf<JSONObject>()
                    val count = minOf(results.length(), 10)

                    for (i in 0 until count) {
                        val item = results.getJSONObject(i)
                        val wifi = JSONObject()
                        wifi.put("ssid", item.optString("ssid", "WLAN_${(1000..9999).random()}"))
                        wifi.put("bssid", item.optString("netid", generateRandomBssid()))
                        wifiList.add(wifi)
                    }
                    return@withContext wifiList.toString()
                } else {
                    return@withContext generateFallbackWifi()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext generateFallbackWifi()
            }
        }

    /**
     * 生成符合真实OUI规范的BSSID
     *
     * IEEE OUI(Organizationally Unique Identifier)是MAC地址前3字节,由IEEE分配给设备厂商。
     * 反作弊SDK会校验OUI前缀是否属于已注册的合法厂商,纯随机MAC会因OUI不存在而被标记。
     * 以下OUI均来自中国市场主流路由器/AP厂商的真实注册前缀。
     */
    private fun generateRandomBssid(): String {
        val ouis = listOf(
            "00:14:22", // Dell
            "cc:2d:e0", // Routerboard
            "44:a8:42", // Cisco Meraki
            "00:25:9c", // Cisco-Linksys
            "60:e3:27", // TP-Link(普联)
            "b0:95:75", // TP-Link(普联)
            "10:41:7f", // TP-Link(普联)
            "88:25:93", // Tenda(腾达)
            "c8:3a:35", // Tenda(腾达)
            "48:7d:2e", // Huawei(华为)
            "e0:19:1d", // Huawei(华为)
            "34:12:f9", // Huawei(华为)
            "28:d1:27", // ZTE(中兴)
            "64:13:6c", // ZTE(中兴)
            "78:11:dc", // Xiaomi(小米)
            "50:64:2b", // Xiaomi(小米)
            "74:da:88", // Edimax
            "c0:25:e9", // Netgear
            "20:76:93", // ASUS(华硕)
            "b4:fb:e4", // D-Link
            "fc:d7:33", // H3C(新华三)
            "3c:37:86"  // Ruijie(锐捷)
        )
        return "${ouis.random()}:${
            String.format(
                java.util.Locale.US,
                "%02x:%02x:%02x",
                (0..255).random(),
                (0..255).random(),
                (0..255).random()
            )
        }"
    }

    private fun generateFallbackWifi(): String {
        val list = mutableListOf<JSONObject>()
        for (i in 0..9) {
            val wifi = JSONObject()
            wifi.put("ssid", "WLAN_${(1000..9999).random()}")
            wifi.put("bssid", generateRandomBssid())
            list.add(wifi)
        }
        return list.toString()
    }
}
