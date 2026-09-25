package com.vincenthzr.locationspoofer.utils

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * 运动真实度引擎：给速度、步频（步幅）、海拔、加速度计波形加上自然起伏。
 *
 * 速度、步频这些"慢变量"都是以模拟开始时间为种子的确定性函数——system_server、每个被 Hook 的
 * App 进程、以及 App 自己的路线进度都在各自独立计算位置和步数，必须对同一时刻算出同样的结果，
 * 否则不同进程之间的距离、步数会对不上。随机强度为 [Level.OFF] 且速度浮动为 0 时退化为匀速匀步频。
 */
object MotionRealism {

    enum class Level(
        val id: Int,
        /** 步频独立于速度的相对起伏幅度 */
        val cadenceVariation: Double,
        /** 海拔随时间漂移的幅度（米），由 [AltitudeModel] 使用 */
        val altitudeAmplitudeM: Double,
        /** 每一步冲击力度的相对差异 */
        val stepAmplitudeVariation: Double,
        /** 加速度计白噪声标准差（m/s²） */
        val sensorNoise: Double
    ) {
        OFF(0, 0.0, 0.0, 0.0, 0.0),
        LOW(1, 0.015, 1.5, 0.05, 0.03),
        MEDIUM(2, 0.03, 3.0, 0.10, 0.06),
        HIGH(3, 0.05, 5.0, 0.18, 0.10);

        companion object {
            fun fromId(id: Int): Level = entries.firstOrNull { it.id == id } ?: MEDIUM
        }
    }

    const val DEFAULT_LEVEL_ID = 2
    const val DEFAULT_SPEED_FLUCTUATION_PCT = 10
    const val MAX_SPEED_FLUCTUATION_PCT = 30

    /** 速度变化中由步频承担的比例，其余由步幅承担（步幅 = 速度 / 步频） */
    private const val CADENCE_SHARE_OF_SPEED = 0.4
    private const val RUN_SPEED_THRESHOLD = 2.2
    private const val GRAVITY = 9.80665f

    private const val SPEED_SALT = 0x5EED5EEDL
    private const val CADENCE_SALT = 0x0CADE0CEL
    private const val STEP_SALT = 0x57E9057EL

    /**
     * 若干个周期、相位随机的正弦叠加成的平滑噪声，取值范围 [-1, 1]。
     * 用正弦叠加而不是随机游走，是因为它的积分有解析解：任意时刻的累计距离 / 步数都能直接算出来，
     * 不依赖逐帧累加的中间状态，所以各个进程在任意时刻都能独立得到一致的结果。
     */
    class SmoothNoise(seed: Long, minPeriodSec: Double, maxPeriodSec: Double, components: Int = 4) {
        private val periods: DoubleArray
        private val phases: DoubleArray
        private val weights: DoubleArray

        init {
            val rnd = Random(seed)
            periods = DoubleArray(components) { minPeriodSec * (maxPeriodSec / minPeriodSec).pow(rnd.nextDouble()) }
            phases = DoubleArray(components) { rnd.nextDouble() * 2 * PI }
            val raw = DoubleArray(components) { 0.5 + rnd.nextDouble() }
            val sum = raw.sum()
            weights = DoubleArray(components) { raw[it] / sum }
        }

        fun value(tSec: Double): Double {
            var v = 0.0
            for (i in periods.indices) v += weights[i] * sin(2 * PI * tSec / periods[i] + phases[i])
            return v
        }

        /** ∫₀ᵗ value(τ) dτ */
        fun integral(tSec: Double): Double {
            var v = 0.0
            for (i in periods.indices) {
                val w = 2 * PI / periods[i]
                v += weights[i] / w * (cos(phases[i]) - cos(w * tSec + phases[i]))
            }
            return v
        }
    }

    class Session(val startTimestamp: Long, val level: Level, speedFluctuationPct: Int) {
        val speedFluctuationPct = speedFluctuationPct.coerceIn(0, MAX_SPEED_FLUCTUATION_PCT)
        private val fluctuation = this.speedFluctuationPct / 100.0
        private val speedNoise = SmoothNoise(startTimestamp xor SPEED_SALT, 20.0, 600.0)
        private val cadenceNoise = SmoothNoise(startTimestamp xor CADENCE_SALT, 3.0, 60.0)

        fun speed(baseSpeed: Double, elapsedSec: Double): Double =
            baseSpeed * (1 + fluctuation * speedNoise.value(elapsedSec))

        /** 从开始到 elapsedSec 的累计行进距离（米） */
        fun distance(baseSpeed: Double, elapsedSec: Double): Double =
            baseSpeed * (elapsedSec + fluctuation * speedNoise.integral(elapsedSec))

        fun cadence(baseCadenceSpm: Double, elapsedSec: Double): Double = baseCadenceSpm * (
            1 + CADENCE_SHARE_OF_SPEED * fluctuation * speedNoise.value(elapsedSec) +
                level.cadenceVariation * cadenceNoise.value(elapsedSec)
            )

        /** 从开始到 elapsedSec 的累计步数（连续值，整数部分是步数，小数部分是当前这一步的相位） */
        fun steps(baseCadenceSpm: Double, elapsedSec: Double): Double = baseCadenceSpm / 60.0 * (
            elapsedSec + CADENCE_SHARE_OF_SPEED * fluctuation * speedNoise.integral(elapsedSec) +
                level.cadenceVariation * cadenceNoise.integral(elapsedSec)
            )

        /**
         * 加速度计读数（含重力，m/s²），坐标约定：Z 竖直、Y 前后、X 左右。
         * 有录制的步态模板时按模板回放，否则用内置的步行 / 跑步波形；两者都会叠加逐步的力度差异和传感器噪声。
         */
        fun accelerometer(
            stepsFloat: Double,
            speed: Double,
            template: GaitTemplate?,
            noise: Random
        ): FloatArray {
            val stepIndex = floor(stepsFloat).toLong()
            val stepPhase = stepsFloat - stepIndex
            val strength = 1 + level.stepAmplitudeVariation * hashUnit(startTimestamp xor STEP_SALT, stepIndex)
            val out = if (template != null) {
                template.sample(stepsFloat / 2.0, strength)
            } else {
                proceduralGait(stepPhase, stepsFloat, stepIndex, speed, strength)
            }
            if (level.sensorNoise > 0) {
                for (i in 0..2) out[i] += (noise.nextGaussian() * level.sensorNoise).toFloat()
            }
            return out
        }
    }

    @Volatile
    private var cached: Session? = null

    /** 同一次模拟会话内复用同一个 Session，避免在高频 Hook 里反复初始化噪声参数 */
    fun session(startTimestamp: Long, levelId: Int, speedFluctuationPct: Int): Session {
        val level = Level.fromId(levelId)
        val pct = speedFluctuationPct.coerceIn(0, MAX_SPEED_FLUCTUATION_PCT)
        val c = cached
        if (c != null && c.startTimestamp == startTimestamp && c.level == level && c.speedFluctuationPct == pct) return c
        return Session(startTimestamp, level, pct).also { cached = it }
    }

    // ---------------------------------------------------------------- 内置步态波形

    private const val SHAPE_SAMPLES = 256

    private fun bump(p: Double, center: Double, width: Double): Double {
        var d = abs(p - center)
        if (d > 0.5) d = 1 - d
        return exp(-0.5 * (d / width) * (d / width))
    }

    /** 采样成查表并去掉均值：真实加速度计在一个步态周期内的平均值必须等于重力，否则长时间积分会暴露 */
    private fun zeroMeanTable(shape: (Double) -> Double): DoubleArray {
        val table = DoubleArray(SHAPE_SAMPLES) { shape(it.toDouble() / SHAPE_SAMPLES) }
        val mean = table.average()
        for (i in table.indices) table[i] -= mean
        return table
    }

    // 步行：脚跟着地冲击 → 支撑期回落 → 蹬地第二峰 → 摆动期低谷
    private val WALK_VERTICAL = zeroMeanTable { p ->
        1.0 * bump(p, 0.00, 0.045) - 0.45 * bump(p, 0.13, 0.07) + 0.55 * bump(p, 0.42, 0.09) - 0.65 * bump(p, 0.72, 0.13)
    }
    private val WALK_FORWARD = zeroMeanTable { p ->
        -0.5 * bump(p, 0.03, 0.05) + 0.45 * bump(p, 0.40, 0.09) - 0.15 * bump(p, 0.75, 0.15)
    }

    // 跑步：尖锐的落地冲击 + 支撑期主峰 + 腾空期接近失重
    private val RUN_VERTICAL = zeroMeanTable { p ->
        1.0 * bump(p, 0.00, 0.035) + 0.55 * bump(p, 0.16, 0.09) - 0.85 * bump(p, 0.62, 0.18)
    }
    private val RUN_FORWARD = zeroMeanTable { p ->
        -0.6 * bump(p, 0.02, 0.04) + 0.5 * bump(p, 0.25, 0.08)
    }

    private fun lookup(table: DoubleArray, phase: Double): Double {
        val pos = (phase - floor(phase)) * table.size
        val i = pos.toInt() % table.size
        val frac = pos - floor(pos)
        return table[i] * (1 - frac) + table[(i + 1) % table.size] * frac
    }

    private fun proceduralGait(
        stepPhase: Double,
        stepsFloat: Double,
        stepIndex: Long,
        speed: Double,
        strength: Double
    ): FloatArray {
        val running = speed >= RUN_SPEED_THRESHOLD
        val amplitude = if (running) 6.0 + 1.5 * speed else 2.0 + 1.0 * speed
        val vertical = lookup(if (running) RUN_VERTICAL else WALK_VERTICAL, stepPhase)
        val forward = lookup(if (running) RUN_FORWARD else WALK_FORWARD, stepPhase)
        // 左右方向：以一个跨步（两步）为周期摆动，左右脚着地时的侧向冲击方向相反
        val side = if (stepIndex % 2 == 0L) 1.0 else -1.0
        val lateral = 0.12 * amplitude * sin(PI * stepsFloat) + 0.1 * amplitude * side * bump(stepPhase, 0.02, 0.05)

        val z = (GRAVITY + strength * amplitude * vertical).coerceAtLeast(0.0)
        return floatArrayOf(
            (strength * lateral).toFloat(),
            (strength * 0.35 * amplitude * forward).toFloat(),
            z.toFloat()
        )
    }

    /** 由 (seed, n) 确定的 [-1, 1] 伪随机数：同一步在不同进程里算出同样的力度 */
    private fun hashUnit(seed: Long, n: Long): Double {
        var x = seed xor (n * -0x61c8864680b583ebL)
        x = (x xor (x ushr 33)) * -0xae502812aa7333L
        x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
        x = x xor (x ushr 33)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble() * 2 - 1
    }
}
