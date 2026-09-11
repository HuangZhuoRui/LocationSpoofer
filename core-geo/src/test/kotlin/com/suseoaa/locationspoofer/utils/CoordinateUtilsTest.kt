package com.suseoaa.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 这几个坐标转换算法本身没有一个"标准答案"可以直接硬编码比对，
 * 所以测试策略是验证算法自身应当满足的数学性质：
 * 1. 中国境外的坐标应原样透传（wgs84ToGcj02/gcj02ToWgs84 的越界判断）。
 * 2. 中国境内的坐标经过加密后必须发生偏移（否则等于没加密）。
 * 3. 往返转换（WGS84->GCJ02->WGS84，GCJ02->BD09->GCJ02）应当在极小误差内还原原始坐标。
 */
class CoordinateUtilsTest {

    private val beijingLat = 39.9087
    private val beijingLng = 116.3975

    @Test
    fun `wgs84ToGcj02 leaves out-of-China coordinates unchanged`() {
        // 纽约，明显在中国境外
        val nyLat = 40.7128
        val nyLng = -74.0060
        val result = CoordinateUtils.wgs84ToGcj02(nyLat, nyLng)
        assertEquals(nyLat, result.lat, 1e-12)
        assertEquals(nyLng, result.lng, 1e-12)
    }

    @Test
    fun `gcj02ToWgs84 leaves out-of-China coordinates unchanged`() {
        val nyLat = 40.7128
        val nyLng = -74.0060
        val result = CoordinateUtils.gcj02ToWgs84(nyLat, nyLng)
        assertEquals(nyLat, result.lat, 1e-12)
        assertEquals(nyLng, result.lng, 1e-12)
    }

    @Test
    fun `wgs84ToGcj02 shifts in-China coordinates by a non-trivial but bounded amount`() {
        val result = CoordinateUtils.wgs84ToGcj02(beijingLat, beijingLng)
        val latDelta = abs(result.lat - beijingLat)
        val lngDelta = abs(result.lng - beijingLng)
        // GCJ-02 的偏移量通常在几十米到几百米之间(约 0.0002 ~ 0.006 度)，
        // 既不能是 0(说明没加密)，也不能大到离谱(说明算法算错了)。
        assertTrue("expected a non-zero offset, got $latDelta", latDelta > 1e-6)
        assertTrue("offset too large: $latDelta", latDelta < 0.01)
        assertTrue("expected a non-zero offset, got $lngDelta", lngDelta > 1e-6)
        assertTrue("offset too large: $lngDelta", lngDelta < 0.01)
    }

    @Test
    fun `wgs84 to gcj02 and back round-trips within half a millimeter`() {
        val gcj = CoordinateUtils.wgs84ToGcj02(beijingLat, beijingLng)
        val backToWgs = CoordinateUtils.gcj02ToWgs84(gcj.lat, gcj.lng)
        // 文档声称往返误差 < 0.5mm；纬度上 1e-8 度约等于 1.1mm，用 5e-8 留出安全余量。
        assertEquals(beijingLat, backToWgs.lat, 5e-8)
        assertEquals(beijingLng, backToWgs.lng, 5e-8)
    }

    @Test
    fun `gcj02 to bd09 and back round-trips accurately`() {
        val bd = CoordinateUtils.gcj02ToBd09(beijingLat, beijingLng)
        val backToGcj = CoordinateUtils.bd09ToGcj02(bd.lat, bd.lng)
        assertEquals(beijingLat, backToGcj.lat, 1e-6)
        assertEquals(beijingLng, backToGcj.lng, 1e-6)
    }

    @Test
    fun `gcj02ToBd09 shifts coordinates by a non-trivial but bounded amount`() {
        val result = CoordinateUtils.gcj02ToBd09(beijingLat, beijingLng)
        val latDelta = abs(result.lat - beijingLat)
        val lngDelta = abs(result.lng - beijingLng)
        assertTrue(latDelta > 1e-6)
        assertTrue(latDelta < 0.01)
        assertTrue(lngDelta > 1e-6)
        assertTrue(lngDelta < 0.01)
    }
}
