package com.vincenthzr.locationspoofer.utils

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AccelSample(val timestampNanos: Long, val x: Float, val y: Float, val z: Float)

/**
 * 用户录制的个人步态模板：一个跨步（左右脚各一步）周期内三个轴的平均加速度曲线，含重力。
 *
 * 只保存平均后的单个周期而不是原始录音：一是体积小（约 1KB，可以直接放进配置文件跨进程下发），
 * 二是回放时由 [MotionRealism] 按当前步频拉伸、逐步叠加力度差异和噪声，避免原样循环同一段录音被识破。
 */
class GaitTemplate(
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val cadenceSpm: Int,
    val strideCount: Int
) {
    init {
        require(x.size == SAMPLES && y.size == SAMPLES && z.size == SAMPLES)
    }

    private val mean = floatArrayOf(x.average().toFloat(), y.average().toFloat(), z.average().toFloat())

    /**
     * @param stridePhase 跨步相位，只取小数部分
     * @param strength 动态分量（相对周期均值的部分）的缩放；均值代表重力方向，保持不变
     */
    fun sample(stridePhase: Double, strength: Double): FloatArray {
        val pos = (stridePhase - floor(stridePhase)) * SAMPLES
        val i = pos.toInt() % SAMPLES
        val j = (i + 1) % SAMPLES
        val frac = (pos - floor(pos)).toFloat()
        val axes = arrayOf(x, y, z)
        return FloatArray(3) { a ->
            val v = axes[a][i] * (1 - frac) + axes[a][j] * frac
            mean[a] + ((v - mean[a]) * strength).toFloat()
        }
    }

    fun encode(): String = buildString {
        append(VERSION).append(';').append(cadenceSpm).append(';').append(strideCount)
        for (axis in arrayOf(x, y, z)) {
            append(';')
            axis.joinTo(this, ",") { String.format(Locale.US, "%.3f", it) }
        }
    }

    sealed class Extraction {
        class Success(val template: GaitTemplate) : Extraction()
        class Failure(val reason: Reason) : Extraction()
    }

    enum class Reason { TOO_SHORT, NO_RHYTHM, TOO_FEW_STRIDES }

    companion object {
        const val SAMPLES = 64
        const val MIN_RECORDING_SEC = 8.0
        const val MIN_STRIDES = 4
        private const val VERSION = "v1"
        private const val RATE_HZ = 50.0

        fun decode(encoded: String?): GaitTemplate? {
            if (encoded.isNullOrBlank()) return null
            return try {
                val parts = encoded.split(';')
                if (parts.size != 6 || parts[0] != VERSION) return null
                val axes = parts.subList(3, 6).map { p -> p.split(',').map { it.toFloat() }.toFloatArray() }
                GaitTemplate(axes[0], axes[1], axes[2], parts[1].toInt(), parts[2].toInt())
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 从一段边走路边录制的加速度计数据中提取步态模板：
         * 重采样到固定频率 → 用加速度模长的自相关求出单步周期 → 找出每一步的冲击峰 →
         * 以"同一只脚起步"的相邻两步为一个跨步切片 → 各跨步重采样到 [SAMPLES] 点后取平均。
         */
        fun extract(samples: List<AccelSample>): Extraction {
            if (samples.size < 2) return Extraction.Failure(Reason.TOO_SHORT)
            val sorted = samples.sortedBy { it.timestampNanos }
            val durationSec = (sorted.last().timestampNanos - sorted.first().timestampNanos) / 1e9
            if (durationSec < MIN_RECORDING_SEC) return Extraction.Failure(Reason.TOO_SHORT)

            val n = (durationSec * RATE_HZ).toInt()
            val rx = resample(sorted, n) { it.x }
            val ry = resample(sorted, n) { it.y }
            val rz = resample(sorted, n) { it.z }

            val magnitude = DoubleArray(n) { sqrt((rx[it] * rx[it] + ry[it] * ry[it] + rz[it] * rz[it]).toDouble()) }
            val trend = movingAverage(magnitude, (RATE_HZ).toInt())
            val signal = movingAverage(DoubleArray(n) { magnitude[it] - trend[it] }, 5)

            val stepLag = estimateStepLag(signal) ?: return Extraction.Failure(Reason.NO_RHYTHM)
            val peaks = findPeaks(signal, (stepLag * 0.6).toInt())

            val strideLen = 2 * stepLag
            val strides = ArrayList<IntRange>()
            var k = 0
            while (k + 2 < peaks.size) {
                val start = peaks[k]
                val end = peaks[k + 2]
                if (abs(end - start - strideLen) <= strideLen * 0.25) strides += start until end
                k += 2
            }
            if (strides.size < MIN_STRIDES) return Extraction.Failure(Reason.TOO_FEW_STRIDES)

            fun average(axis: FloatArray) = FloatArray(SAMPLES) { s ->
                strides.map { r ->
                    val pos = r.first + (r.last + 1 - r.first) * s.toDouble() / SAMPLES
                    interpolate(axis, pos)
                }.average().toFloat()
            }

            val meanStrideSec = strides.map { (it.last + 1 - it.first) / RATE_HZ }.average()
            val cadence = (120.0 / meanStrideSec).roundToInt()
            return Extraction.Success(GaitTemplate(average(rx), average(ry), average(rz), cadence, strides.size))
        }

        private fun resample(sorted: List<AccelSample>, n: Int, axis: (AccelSample) -> Float): FloatArray {
            val t0 = sorted.first().timestampNanos
            val out = FloatArray(n)
            var j = 0
            for (i in 0 until n) {
                val t = t0 + (i / RATE_HZ * 1e9).toLong()
                while (j < sorted.size - 2 && sorted[j + 1].timestampNanos < t) j++
                val a = sorted[j]
                val b = sorted[j + 1]
                val span = (b.timestampNanos - a.timestampNanos).coerceAtLeast(1L)
                val f = ((t - a.timestampNanos).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
                out[i] = axis(a) * (1 - f) + axis(b) * f
            }
            return out
        }

        private fun movingAverage(data: DoubleArray, window: Int): DoubleArray {
            val half = window / 2
            return DoubleArray(data.size) { i ->
                val from = (i - half).coerceAtLeast(0)
                val to = (i + half).coerceAtMost(data.size - 1)
                var sum = 0.0
                for (k in from..to) sum += data[k]
                sum / (to - from + 1)
            }
        }

        /** 单步周期（采样点数），对应 70~240 步/分钟；若最大相关落在跨步周期上，退回到它的一半 */
        private fun estimateStepLag(signal: DoubleArray): Int? {
            val minLag = (0.25 * RATE_HZ).toInt()
            val maxLag = (0.85 * RATE_HZ).toInt()
            if (signal.size < maxLag * 4) return null
            val energy = signal.sumOf { it * it }
            if (energy <= 0.0) return null
            fun corr(lag: Int): Double {
                var s = 0.0
                for (i in 0 until signal.size - lag) s += signal[i] * signal[i + lag]
                return s / energy
            }
            var best = minLag
            var bestCorr = Double.NEGATIVE_INFINITY
            for (lag in minLag..maxLag) {
                val c = corr(lag)
                if (c > bestCorr) {
                    bestCorr = c
                    best = lag
                }
            }
            if (bestCorr < 0.2) return null
            val half = best / 2
            if (half >= minLag && corr(half) > 0.8 * bestCorr) return half
            return best
        }

        private fun findPeaks(signal: DoubleArray, minDistance: Int): List<Int> {
            val std = sqrt(signal.sumOf { it * it } / signal.size)
            val peaks = ArrayList<Int>()
            for (i in 1 until signal.size - 1) {
                if (signal[i] < 0.3 * std || signal[i] < signal[i - 1] || signal[i] < signal[i + 1]) continue
                if (peaks.isNotEmpty() && i - peaks.last() < minDistance) {
                    if (signal[i] > signal[peaks.last()]) peaks[peaks.size - 1] = i
                } else {
                    peaks += i
                }
            }
            return peaks
        }

        private fun interpolate(axis: FloatArray, pos: Double): Double {
            val i = pos.toInt().coerceIn(0, axis.size - 1)
            val j = (i + 1).coerceAtMost(axis.size - 1)
            val f = pos - i
            return axis[i] * (1 - f) + axis[j] * f
        }
    }
}
