package com.suseoaa.locationspoofer.xposed.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEngineTest {

    private fun pointsJson(vararg latLng: Pair<Double, Double>): JSONArray {
        val arr = JSONArray()
        latLng.forEach { (lat, lng) ->
            arr.put(JSONObject().apply {
                put("lat", lat)
                put("lng", lng)
            })
        }
        return arr
    }

    @Test
    fun `non route mode falls back to the raw config coordinate`() {
        val config = JSONObject().apply {
            put("is_route_mode", false)
            put("lat", 31.5)
            put("lng", 120.5)
            put("sim_bearing", 88.0)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(31.5, motion.lat, 0.0)
        assertEquals(120.5, motion.lng, 0.0)
        assertEquals(88f, motion.bearing)
        assertEquals(0f, motion.speed)
    }

    @Test
    fun `route mode with fewer than two points falls back to the raw config coordinate`() {
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(1.0 to 2.0))
            put("lat", 31.5)
            put("lng", 120.5)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(31.5, motion.lat, 0.0)
        assertEquals(120.5, motion.lng, 0.0)
    }

    @Test
    fun `straight two-point route starts at the first point`() {
        // 用这个测试专属的坐标，避免和其它用例撞上 RouteEngine 内部的路线缓存签名(count+首尾纬度)。
        val start = 10.0 to 100.0
        val end = 10.02 to 100.0 // 正北方向，约 2.2km，明显不是闭环(缺口远大于5米阈值)
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(start, end))
            put("speed_m_s", 10.0)
            put("start_timestamp", 0L)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(start.first, motion.lat, 1e-6)
        assertEquals(start.second, motion.lng, 1e-6)
    }

    @Test
    fun `straight two-point route with stop_at_destination stops exactly at the last point`() {
        val start = 10.0 to 100.0
        val end = 10.02 to 100.0
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(start, end))
            put("speed_m_s", 10.0)
            // start_timestamp 必须是正数——RouteEngine 把 <=0 的值当作"还没设置开始时间"，
            // 会直接拿 now 当起点，导致 elapsedSec 恒为 0，永远走不到终点。
            put("start_timestamp", 1L)
            put("stop_at_destination", true)
        }
        // 全程约 2.2km，10m/s 走完要 220s；给 10000s 保证早已到达终点。
        val motion = RouteEngine.calculateCurrentPosition(config, now = 10_000_000L)
        assertEquals(end.first, motion.lat, 1e-6)
        assertEquals(end.second, motion.lng, 1e-6)
        assertEquals(0f, motion.speed)
    }

    @Test
    fun `closed loop route treats the near-identical last point as returning to the start`() {
        // 首尾几乎重合(小于 RouteEngine 5 米的闭环判定阈值)，构成一个三角形闭环。
        val a = 20.0 to 110.0
        val b = 20.01 to 110.0
        val c = 20.01 to 110.01
        val closeToA = 20.0 to 110.0 // 与 a 完全相同，缺口为 0，必然判定为闭环
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(a, b, c, closeToA))
            put("speed_m_s", 5.0)
            put("start_timestamp", 0L)
        }
        // t=0 时应该正好在起点 a
        val atStart = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(a.first, atStart.lat, 1e-6)
        assertEquals(a.second, atStart.lng, 1e-6)
        assertTrue(atStart.speed > 0f)
    }
}
