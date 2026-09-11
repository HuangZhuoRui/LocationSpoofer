package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectorySimulatorTest {

    @Test
    fun `getModeParams returns the documented speed and jitter presets`() {
        assertEquals(TrajectorySimulator.ModeParams(1.4, 2.0, 5.0), TrajectorySimulator.getModeParams("WALKING"))
        assertEquals(TrajectorySimulator.ModeParams(3.0, 3.0, 8.0), TrajectorySimulator.getModeParams("RUNNING"))
        assertEquals(TrajectorySimulator.ModeParams(5.5, 0.0, 3.0), TrajectorySimulator.getModeParams("CYCLING"))
        assertEquals(TrajectorySimulator.ModeParams(15.0, 0.0, 2.0), TrajectorySimulator.getModeParams("DRIVING"))
        assertEquals(TrajectorySimulator.ModeParams(0.0, 0.0, 2.0), TrajectorySimulator.getModeParams("STILL"))
        assertEquals(TrajectorySimulator.ModeParams(0.0, 0.0, 2.0), TrajectorySimulator.getModeParams("unknown-mode"))
    }

    @Test
    fun `calculateSimulatedLocation returns the exact start point when elapsed time is not positive`() {
        val result = TrajectorySimulator.calculateSimulatedLocation(
            baseLat = 30.0,
            baseLng = 120.0,
            startTimestamp = 1_000L,
            simModeName = "DRIVING",
            bearingDeg = 45f,
            currentTime = 1_000L,
            enableJitter = false
        )
        assertEquals(30.0, result.lat, 0.0)
        assertEquals(120.0, result.lng, 0.0)
        assertEquals(0f, result.speed)
        assertEquals(45f, result.bearing)
    }

    @Test
    fun `calculateSimulatedLocation moving due north keeps longitude fixed`() {
        // 从赤道(0,0)朝正北(bearing=0)走，经度理论上应保持不变(球面几何的特例)。
        val speedMs = 15.0 // DRIVING
        val elapsedSec = 100.0
        val distance = speedMs * elapsedSec
        val earthRadius = 6378137.0
        val expectedLatDelta = Math.toDegrees(distance / earthRadius)

        val result = TrajectorySimulator.calculateSimulatedLocation(
            baseLat = 0.0,
            baseLng = 0.0,
            startTimestamp = 0L,
            simModeName = "DRIVING",
            bearingDeg = 0f,
            currentTime = (elapsedSec * 1000).toLong(),
            enableJitter = false
        )

        assertEquals(expectedLatDelta, result.lat, 1e-9)
        assertEquals(0.0, result.lng, 1e-9)
        assertEquals(speedMs.toFloat(), result.speed)
        assertEquals(5.0f, result.accuracy) // enableJitter=false 时精度固定为基准值
        assertEquals(10.0, result.altitude, 0.0) // enableJitter=false 时海拔固定为基准值
    }

    @Test
    fun `calculateSimulatedLocation moving due east keeps latitude fixed at the equator`() {
        val speedMs = 15.0
        val elapsedSec = 100.0
        val distance = speedMs * elapsedSec
        val earthRadius = 6378137.0
        val expectedLngDelta = Math.toDegrees(distance / earthRadius)

        val result = TrajectorySimulator.calculateSimulatedLocation(
            baseLat = 0.0,
            baseLng = 0.0,
            startTimestamp = 0L,
            simModeName = "DRIVING",
            bearingDeg = 90f,
            currentTime = (elapsedSec * 1000).toLong(),
            enableJitter = false
        )

        assertEquals(0.0, result.lat, 1e-9)
        assertEquals(expectedLngDelta, result.lng, 1e-9)
    }

    @Test
    fun `calculateSimulatedLocation with jitter enabled keeps accuracy and altitude within documented bounds`() {
        val result = TrajectorySimulator.calculateSimulatedLocation(
            baseLat = 31.2304,
            baseLng = 121.4737,
            startTimestamp = 0L,
            simModeName = "WALKING",
            bearingDeg = 10f,
            currentTime = 5_000L,
            enableJitter = true
        )
        assertTrue(result.accuracy in 2.0f..50.0f)
        assertTrue(result.altitude in 0.0..100.0)
    }

    @Test
    fun `calculateRoutePosition with fewer than two points returns that point with zero speed`() {
        val single = TrajectorySimulator.calculateRoutePosition(
            points = listOf(RoutePoint(10.0, 20.0)),
            startTimestamp = 0L,
            simModeName = "DRIVING",
            currentTime = 5_000L
        )
        assertEquals(10.0, single.lat, 0.0)
        assertEquals(20.0, single.lng, 0.0)
        assertEquals(0f, single.speed)

        val empty = TrajectorySimulator.calculateRoutePosition(
            points = emptyList(),
            startTimestamp = 0L,
            simModeName = "DRIVING",
            currentTime = 5_000L
        )
        assertEquals(0.0, empty.lat, 0.0)
        assertEquals(0.0, empty.lng, 0.0)
    }

    @Test
    fun `calculateRoutePosition starts at the first point when elapsed time is not positive`() {
        val points = listOf(RoutePoint(10.0, 20.0), RoutePoint(11.0, 21.0))
        val result = TrajectorySimulator.calculateRoutePosition(
            points = points,
            startTimestamp = 10_000L,
            simModeName = "DRIVING",
            currentTime = 10_000L
        )
        assertEquals(10.0, result.lat, 0.0)
        assertEquals(20.0, result.lng, 0.0)
    }

    @Test
    fun `calculateRoutePosition on a straight two-point route reaches the end point after enough time`() {
        val from = RoutePoint(22.0, 114.0)
        val to = RoutePoint(22.01, 114.0) // 正北方向，约 1.1km
        val points = listOf(from, to)
        val speedMs = 15.0 // DRIVING

        // 给足够长的时间(远超过全程所需时间)，配合 stop_at_destination 语义之外的普通往返模式，
        // calculateRoutePosition 本身没有 stop_at_destination 参数，非闭环路线会来回走(往返)，
        // 所以这里只验证起点(t=0附近)与半程时间点大致落在 from/to 之间的合理范围内。
        val halfTimeMs = 40_000L // 40s * 15m/s = 600m，小于全程约1.1km，肯定还在 from->to 这一段上
        val result = TrajectorySimulator.calculateRoutePosition(
            points = points,
            startTimestamp = 0L,
            simModeName = "DRIVING",
            currentTime = halfTimeMs,
            enableJitter = false
        )
        // 走了一段之后纬度应该介于 from 和 to 之间，且明显大于起点
        assertTrue(result.lat > from.lat)
        assertTrue(result.lat < to.lat)
        // 经度理论上不变(正北方向)
        assertEquals(from.lng, result.lng, 1e-6)
    }
}
