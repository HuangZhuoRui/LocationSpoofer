package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeModelTest {

    private val start = CoordinateUtils.LatLng(23.1066, 113.3245)
    private val t0 = 1_789_398_037_217L

    /** 沿正东方向每隔 stepM 采样一次海拔 */
    private fun profile(variation: Double, drift: Double, lengthM: Double, stepM: Double, epochMs: Long = t0): List<Double> =
        (0..(lengthM / stepM).toInt()).map {
            val p = GeoMath.destination(start, 90.0, it * stepM)
            AltitudeModel.altitude(50.0, variation, drift, p.lat, p.lng, epochMs)
        }

    @Test
    fun `zero variation keeps the altitude fixed`() {
        assertTrue(profile(0.0, 5.0, 2000.0, 10.0).all { it == 50.0 })
    }

    @Test
    fun `altitude stays within the configured range and actually varies`() {
        for (variation in listOf(5.0, 20.0, 100.0)) {
            val values = profile(variation, 3.0, 20_000.0, 20.0)
            assertTrue(values.all { it in 50.0 - variation..50.0 + variation })
            assertTrue("variation $variation", values.max() - values.min() > variation * 0.5)
        }
    }

    @Test
    fun `same place gives the same altitude and stays still over short time`() {
        val a = AltitudeModel.altitude(50.0, 20.0, 0.0, start.lat, start.lng, t0)
        val b = AltitudeModel.altitude(50.0, 20.0, 0.0, start.lat, start.lng, t0 + 3_600_000)
        assertEquals(a, b, 0.0)
        // 有漂移时站着不动，1 秒内的变化远小于 1 米
        val c = AltitudeModel.altitude(50.0, 20.0, 3.0, start.lat, start.lng, t0)
        val d = AltitudeModel.altitude(50.0, 20.0, 3.0, start.lat, start.lng, t0 + 1000)
        assertTrue(abs(c - d) < 0.5)
    }

    @Test
    fun `slope never exceeds a realistic grade`() {
        for (variation in listOf(10.0, 50.0, 100.0)) {
            val values = profile(variation, 0.0, 10_000.0, 1.0)
            val maxGrade = values.zipWithNext { a, b -> abs(b - a) }.max()
            assertTrue("variation $variation grade $maxGrade", maxGrade <= 0.08 + 1e-6)
        }
    }
}
