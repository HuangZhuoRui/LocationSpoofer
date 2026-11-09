package com.vincenthzr.locationspoofer.utils

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 模拟海拔 = 设置的基准海拔 + 随位置起伏的"地形" + 随时间缓慢漂移的 GPS 高程误差，总偏差不超过设置的变化范围。
 *
 * 地形只由坐标决定：同一地点在任何时刻、任何进程、循环路线的每一圈都得到同一海拔，站着不动时也不会上下乱跳。
 * 起伏的波长随幅度自动拉长，坡度不超过 [MAX_GRADE]，避免出现步行几十米就爬升几十米这种不可能的数据。
 * 漂移以绝对时间为自变量、固定种子生成，不依赖 start_timestamp——摇杆模式每秒都会重写 start_timestamp，
 * 以它为种子会让海拔每秒随机跳一次。
 */
object AltitudeModel {

    const val DEFAULT_VARIATION_M = 10
    const val MAX_VARIATION_M = 100

    /** 地形最大坡度（8%，城市道路常见上限） */
    private const val MAX_GRADE = 0.08
    private const val MIN_WAVELENGTH_M = 250.0
    private const val WAVELENGTH_SPREAD = 4.0
    private const val COMPONENTS = 5
    private const val TERRAIN_SEED = 0x7E22A1A7L
    private const val DRIFT_SEED = 0x0A171707L
    private const val METERS_PER_DEG_LAT = 110_540.0
    private const val METERS_PER_DEG_LNG = 111_320.0

    /** 若干个方向、波长、相位随机的平面正弦波叠加，取值范围 [-1, 1]，梯度不超过 2π / minWavelength */
    private class Terrain(minWavelengthM: Double) {
        private val kx: DoubleArray
        private val ky: DoubleArray
        private val phases: DoubleArray
        private val weights: DoubleArray

        init {
            val rnd = Random(TERRAIN_SEED)
            kx = DoubleArray(COMPONENTS)
            ky = DoubleArray(COMPONENTS)
            for (i in 0 until COMPONENTS) {
                val wavelength = minWavelengthM * WAVELENGTH_SPREAD.pow(rnd.nextDouble())
                val direction = rnd.nextDouble() * 2 * PI
                kx[i] = 2 * PI / wavelength * cos(direction)
                ky[i] = 2 * PI / wavelength * sin(direction)
            }
            phases = DoubleArray(COMPONENTS) { rnd.nextDouble() * 2 * PI }
            val raw = DoubleArray(COMPONENTS) { 0.5 + rnd.nextDouble() }
            val sum = raw.sum()
            weights = DoubleArray(COMPONENTS) { raw[it] / sum }
        }

        fun value(xM: Double, yM: Double): Double {
            var v = 0.0
            for (i in 0 until COMPONENTS) v += weights[i] * sin(kx[i] * xM + ky[i] * yM + phases[i])
            return v
        }
    }

    private val drift = MotionRealism.SmoothNoise(DRIFT_SEED, 30.0, 400.0)

    // 同一会话里幅度不变，按幅度缓存一份地形
    @Volatile private var cachedTerrain: Pair<Double, Terrain>? = null

    private fun terrain(amplitudeM: Double): Terrain {
        cachedTerrain?.let { (amp, t) -> if (amp == amplitudeM) return t }
        val minWavelength = max(MIN_WAVELENGTH_M, 2 * PI * amplitudeM / MAX_GRADE)
        return Terrain(minWavelength).also { cachedTerrain = amplitudeM to it }
    }

    /**
     * @param variationM 相对基准海拔的最大偏差（米），0 表示固定海拔
     * @param driftM 随时间漂移的幅度（米，来自运动真实度档位），会被限制在变化范围的一半以内
     */
    fun altitude(baseM: Double, variationM: Double, driftM: Double, lat: Double, lng: Double, epochMs: Long): Double {
        val range = variationM.coerceIn(0.0, MAX_VARIATION_M.toDouble())
        if (range <= 0.0) return baseM
        val driftAmp = min(driftM.coerceAtLeast(0.0), range / 2)
        val terrainAmp = range - driftAmp
        val x = lng * METERS_PER_DEG_LNG * cos(lat * PI / 180)
        val y = lat * METERS_PER_DEG_LAT
        return baseM + terrainAmp * terrain(terrainAmp).value(x, y) + driftAmp * drift.value(epochMs / 1000.0)
    }
}
