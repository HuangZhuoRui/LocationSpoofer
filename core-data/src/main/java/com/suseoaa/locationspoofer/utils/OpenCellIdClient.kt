package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class OpenCellIdClient {
    private val client = OkHttpClient()

    /** 验证 token 是否有效 */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext false
        }
        return@withContext try {
            val url =
                "https://opencellid.org/cell/getInArea?key=$token&BBOX=0.0,0.0,0.0,0.0&format=json"
            val request = Request.Builder()
                .url(url)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext false
                val json = JSONObject(body)
                if (json.has("error")) {
                    // code 1 表示“未找到基站”，这实际上验证了密钥存在并且有效。
                    // code 2 表示“API Key not known”或其他代码指示密钥无效。
                    json.optInt("code") == 1
                } else {
                    true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 根据经纬度获取基站数据，无数据时返回空数组字符串"[]"，不生成假数据 */
    suspend fun fetchCellData(lat: Double, lng: Double, token: String): String =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                return@withContext "[]"
            }

            val searchBoxes = buildSearchBoxes(lat, lng)

            try {
                for (box in searchBoxes) {
                    val url =
                        "https://opencellid.org/cell/getInArea?key=$token&BBOX=${box.latMin},${box.lonMin},${box.latMax},${box.lonMax}&format=json&limit=20"
                    val request = Request.Builder()
                        .url(url)
                        .build()

                    val response = client.newCall(request).execute()
                    val code = response.code
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body == null) {
                            continue
                        }
                        val jsonObject = JSONObject(body)
                        val cells = jsonObject.optJSONArray("cells")
                        if (cells == null || cells.length() == 0) {
                            continue
                        }
                        val normalizedCells = normalizeOpenCellIdCells(cells)
                        if (normalizedCells.length() == 0) {
                            continue
                        }
                        return@withContext normalizedCells.toString()
                    } else {
                        // 请求未成功，尝试下一个半径
                    }
                }
                return@withContext "[]"
            } catch (e: Exception) {
                return@withContext "[]"
            }
        }

    private data class BBox(
        val latMin: Double,
        val lonMin: Double,
        val latMax: Double,
        val lonMax: Double
    )

    private fun buildSearchBoxes(lat: Double, lng: Double): List<BBox> {
        val initialHalfSpan = 0.005
        val gridHalfSpan = 0.008
        val gridStep = gridHalfSpan * 2
        val boxes = mutableListOf(bbox(lat, lng, initialHalfSpan))

        for (ring in 0..2) {
            for (x in -ring..ring) {
                for (y in -ring..ring) {
                    if (ring > 0 && kotlin.math.abs(x) != ring && kotlin.math.abs(y) != ring) continue
                    val centerLat = lat + y * gridStep
                    val centerLng = lng + x * gridStep
                    val box = bbox(centerLat, centerLng, gridHalfSpan)
                    if (boxes.none { it == box }) boxes.add(box)
                }
            }
        }

        return boxes
    }

    private fun bbox(lat: Double, lng: Double, halfSpan: Double): BBox {
        return BBox(
            latMin = (lat - halfSpan).coerceAtLeast(-90.0),
            lonMin = (lng - halfSpan).coerceAtLeast(-180.0),
            latMax = (lat + halfSpan).coerceAtMost(90.0),
            lonMax = (lng + halfSpan).coerceAtMost(180.0)
        )
    }

    private fun normalizeOpenCellIdCells(cells: JSONArray): JSONArray {
        val normalized = JSONArray()
        for (i in 0 until cells.length()) {
            val source = cells.optJSONObject(i) ?: continue
            val type =
                normalizeRadioType(source.optString("radio", source.optString("type", "LTE")))
            val mcc = positiveInt(source, "mcc", default = 460)
            val mnc = positiveInt(source, "mnc", "net", default = 0)
            val area = positiveInt(source, "tac", "lac", "area", default = 0)
            val cellId = positiveInt(source, "ci", "cid", "cellid", "cell", default = 0)
            if (area <= 0 || cellId <= 0) {
                continue
            }

            val dbm = signalDbm(source, i)
            val pci = positiveInt(source, "pci", default = (cellId % 504).coerceIn(0, 503))
            normalized.put(JSONObject().apply {
                put("type", type)
                put("radio", source.optString("radio", type))
                put("mcc", mcc)
                put("mnc", mnc)
                put("tac", area)
                put("lac", area)
                put("ci", cellId)
                put("cid", cellId)
                put("cellid", cellId)
                put("pci", pci)
                put("dbm", dbm)
                put("isRegistered", i == 0)
                if (source.has("lat")) put("lat", source.optDouble("lat"))
                if (source.has("lon")) put("lon", source.optDouble("lon"))
                if (source.has("range")) put("range", source.optInt("range"))
                if (source.has("samples")) put("samples", source.optInt("samples"))
            })
        }
        return normalized
    }

    private fun normalizeRadioType(radio: String): String {
        return when (radio.uppercase(Locale.US)) {
            "GSM" -> "GSM"
            "UMTS", "WCDMA" -> "WCDMA"
            "NR", "NR5G", "5G" -> "NR"
            "CDMA" -> "CDMA"
            else -> "LTE"
        }
    }

    private fun positiveInt(source: JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!source.has(key) || source.isNull(key)) continue
            val value = source.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = source.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    private fun signalDbm(source: JSONObject, index: Int): Int {
        val direct = source.optInt("dbm", Int.MIN_VALUE)
        if (direct in -140..-40) return direct

        val average = source.optInt("averageSignalStrength", Int.MIN_VALUE)
        if (average in -140..-40) return average

        val signal = source.optInt("signal", Int.MIN_VALUE)
        if (signal in -140..-40) return signal

        return (-70 - index * 3).coerceAtLeast(-110)
    }
}
