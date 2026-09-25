package com.vincenthzr.locationspoofer.xposed.utils

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

    private fun joystickConfig(speed: Double, bearing: Double, startTimestamp: Long) = JSONObject().apply {
        put("is_route_mode", false)
        put("sim_mode", "JOYSTICK")
        put("lat", 31.5)
        put("lng", 120.5)
        put("sim_bearing", bearing)
        put("speed_m_s", speed)
        put("start_timestamp", startTimestamp)
    }

    @Test
    fun `joystick mode extrapolates along the bearing between config writes`() {
        val motion = RouteEngine.calculateCurrentPosition(joystickConfig(5.0, 0.0, 1_000L), now = 2_000L)
        // 正北 5 m/s 走 1 秒 ≈ 纬度增加 5 / 111 km
        assertEquals(31.5 + 5.0 / 111_320.0, motion.lat, 1e-6)
        assertEquals(120.5, motion.lng, 1e-9)
        assertEquals(5f, motion.speed)
    }

    @Test
    fun `joystick extrapolation is capped when the app stops writing config`() {
        val capped = RouteEngine.calculateCurrentPosition(joystickConfig(5.0, 90.0, 1_000L), now = 61_000L)
        val atCap = RouteEngine.calculateCurrentPosition(joystickConfig(5.0, 90.0, 1_000L), now = 4_000L)
        assertEquals(atCap.lng, capped.lng, 1e-12)
        assertTrue(capped.lng > 120.5)
    }

    @Test
    fun `released joystick stays at the written coordinate`() {
        val motion = RouteEngine.calculateCurrentPosition(joystickConfig(0.0, 45.0, 1_000L), now = 5_000L)
        assertEquals(31.5, motion.lat, 0.0)
        assertEquals(120.5, motion.lng, 0.0)
        assertEquals(0f, motion.speed)
    }

    @Test
    fun `route resumes from the paused distance offset`() {
        // 一条正北方向约 1113 米的直线路线
        val route = pointsJson(0.0 to 0.0, 0.01 to 0.0)
        fun cfg(offset: Double, start: Long) = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", route)
            put("speed_m_s", 2.0)
            put("start_timestamp", start)
            put("route_distance_offset", offset)
        }
        val paused = RouteEngine.calculateCurrentPosition(cfg(0.0, 1_000L), now = 101_000L) // 走了 200 米时暂停
        val resumed = RouteEngine.calculateCurrentPosition(cfg(200.0, 500_000L), now = 500_000L) // 继续的瞬间
        assertEquals(paused.lat, resumed.lat, 1e-9)
        val later = RouteEngine.calculateCurrentPosition(cfg(200.0, 500_000L), now = 510_000L)
        assertEquals(220.0 / 111_320.0, later.lat, 2e-6)
    }
}
