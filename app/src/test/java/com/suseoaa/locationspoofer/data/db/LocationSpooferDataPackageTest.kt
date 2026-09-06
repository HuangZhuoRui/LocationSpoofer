package com.suseoaa.locationspoofer.data.db

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入导出数据包的序列化契约测试。
 * 这里锁住两件最容易悄悄出问题、又最难靠手测发现的事：
 * 1. 没勾选 API 密钥时，导出的 JSON 里绝不能出现密钥内容（分享场景下这是隐私事故）。
 * 2. 旧的 version 2 文件（没有 settings/apiKeys 字段）必须还能被解析。
 */
class LocationSpooferDataPackageTest {

    // 与 MainViewModel.exportEnvironmentData 中使用的配置保持一致
    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // 与 MainViewModel.parseImportPackageInternal 中使用的配置保持一致
    private val importJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `未勾选密钥时导出的 JSON 不包含任何密钥内容`() {
        val pkg = LocationSpooferDataPackage(
            savedLocations = listOf(
                com.suseoaa.locationspoofer.data.model.SavedLocation("家", 31.0, 121.0)
            ),
            settings = ExportedSettings(altitude = "25.0", satelliteCount = "20"),
            apiKeys = null
        )

        val text = exportJson.encodeToString(pkg)

        // 结构上 apiKeys 必须是 null，而不是一个内容为空字符串的对象
        assertTrue(text.contains("\"apiKeys\": null"))
        // 任何一个密钥字段名都不应该出现在文件里
        listOf(
            "amapApiKey", "baiduApiKey", "googleApiKey",
            "wigleApiToken", "opencellidApiToken"
        ).forEach { field ->
            assertFalse("导出文件里不应出现密钥字段 $field", text.contains(field))
        }
    }

    @Test
    fun `勾选密钥时导出的 JSON 才会带上密钥`() {
        val pkg = LocationSpooferDataPackage(
            apiKeys = ExportedApiKeys(amapApiKey = "test-amap-key")
        )
        val text = exportJson.encodeToString(pkg)
        assertTrue(text.contains("amapApiKey"))
        assertTrue(text.contains("test-amap-key"))
    }

    @Test
    fun `旧的 version 2 文件仍然可以解析，settings 与 apiKeys 落到 null`() {
        // 模拟 2.6.x 之前导出的文件：没有 settings / apiKeys 字段
        val legacyV2 = """
            {
              "version": 2,
              "exportTimestamp": 1700000000000,
              "appVersion": "2.0.0",
              "locations": [],
              "savedLocations": [
                {"name": "公司", "lat": 31.5, "lng": 121.5}
              ],
              "savedRoutes": [],
              "appCoordinateSystems": {"com.tencent.mm": "GCJ-02"}
            }
        """.trimIndent()

        val pkg = importJson.decodeFromString<LocationSpooferDataPackage>(legacyV2)

        assertEquals(2, pkg.version)
        assertEquals(1, pkg.savedLocations.size)
        assertEquals("公司", pkg.savedLocations[0].name)
        assertEquals("GCJ-02", pkg.appCoordinateSystems["com.tencent.mm"])
        assertNull("旧文件没有这两个分类，应当为 null", pkg.settings)
        assertNull("旧文件没有这两个分类，应当为 null", pkg.apiKeys)
    }

    @Test
    fun `新格式可以完整往返序列化`() {
        val original = LocationSpooferDataPackage(
            savedLocations = listOf(
                com.suseoaa.locationspoofer.data.model.SavedLocation("家", 31.0, 121.0)
            ),
            appCoordinateSystems = mapOf("com.autonavi.minimap" to "GCJ-02"),
            settings = ExportedSettings(
                mockWifi = false,
                mockCell = true,
                mockBluetooth = false,
                enableJitter = false,
                altitude = "12.5",
                satelliteCount = "18",
                mapType = "SATELLITE",
                mapEngine = "AMAP"
            ),
            apiKeys = ExportedApiKeys(baiduApiKey = "bd-key")
        )

        val restored = importJson.decodeFromString<LocationSpooferDataPackage>(
            exportJson.encodeToString(original)
        )

        assertEquals(3, restored.version)
        assertEquals(original.settings, restored.settings)
        assertEquals(original.apiKeys, restored.apiKeys)
        assertEquals(original.savedLocations, restored.savedLocations)
        assertEquals(original.appCoordinateSystems, restored.appCoordinateSystems)
    }
}
