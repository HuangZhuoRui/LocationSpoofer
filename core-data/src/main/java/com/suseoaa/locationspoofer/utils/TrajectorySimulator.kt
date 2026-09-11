package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.SimulatedLocation
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轨迹模拟器 -- 多维度高斯随机游走引擎
 *
 * 核心设计思路:
 * 真实GPS芯片输出的坐标序列天然包含高斯分布的白噪声(由大气电离层闪烁、多径效应、
 * 接收机时钟漂移等物理因素叠加而成)。反作弊SDK(如高德、腾讯风控)会对连续若干秒的
 * 坐标序列做傅里叶变换(FFT),若频谱中出现明显的单频峰(如正弦/余弦产生的固定频率),
 * 则判定为机器生成。
 *
 * 本模块使用Random.nextGaussian()生成均值为0、标准差为sigma的白噪声,其频谱为
 * 均匀分布(无单频峰),从信号特征层面与真实GPS漂移一致。同时对Accuracy(定位精度)和
 * Altitude(海拔高度)施加独立的高斯扰动,消除固定常数带来的机器生成痕迹。
 */
object TrajectorySimulator {
    /** 地球半径(米) */
    private const val R = 6378137.0

    /**
     * 随机数生成器 -- 所有高斯噪声的唯一数据源
     *
     * 使用单例Random实例而非每次new Random(),原因:
     * 1. nextGaussian()内部维护一对Box-Muller变换的缓存值,复用实例可减少50%的底层计算
     * 2. 连续调用同一实例产生的序列具有更好的统计独立性(种子状态持续演化)
     */
    private val rng = Random()

    /**
     * 上一次高斯漂移的累计偏移量(米)
     *
     * 随机游走模型的核心状态: 每次调用时在上一次偏移量基础上叠加一个高斯增量,
     * 而非每次独立采样。这使得轨迹呈现真实GPS的"缓慢漂移+突然跳变"特征,
     * 而非围绕原点的高频震荡。
     *
     * driftLatMeters/driftLngMeters: 在南北/东西方向上的累计漂移(米)
     */
    private var driftLatMeters = 0.0
    private var driftLngMeters = 0.0

    /**
     * 上一次精度和海拔的高斯偏移量
     *
     * 真实GPS中Accuracy受卫星几何分布(GDOP)影响,通常在一个基准值附近做
     * 布朗运动式的缓慢波动;Altitude受对流层延迟影响,同样具有低频漂移特征。
     */
    private var accuracyDrift = 0.0
    private var altitudeDrift = 0.0

    /** 上一次调用的时间戳,用于计算dt(秒),驱动漂移步长的时间缩放 */
    private var lastCallTime = 0L

    /**
     * 计算模拟位置(带高斯随机游走抖动)
     * @param baseLat 基础纬度
     * @param baseLng 基础经度
     * @param startTimestamp 开始时间戳
     * @param simModeName 模拟模式(步行、跑步等)
     * @param bearingDeg 初始方位角(度)
     * @param currentTime 当前时间戳
     */
    fun calculateSimulatedLocation(
        baseLat: Double,
        baseLng: Double,
        startTimestamp: Long,
        simModeName: String,
        bearingDeg: Float,
        currentTime: Long = System.currentTimeMillis(),
        enableJitter: Boolean = true
    ): SimulatedLocation {
        val elapsedSec = (currentTime - startTimestamp) / 1000.0
        if (elapsedSec <= 0) return SimulatedLocation(baseLat, baseLng, 0f, bearingDeg, 5.0f, 0.0)

        val (speedMs, stepFreqHz, jitterRadius) = getModeParams(simModeName)
        val distance = speedMs * elapsedSec
        val bearingRad = Math.toRadians(bearingDeg.toDouble())

        val latRad = Math.toRadians(baseLat)
        val lngRad = Math.toRadians(baseLng)
        val newLatRad = Math.asin(
            sin(latRad) * cos(distance / R) + cos(latRad) * sin(distance / R) * cos(bearingRad)
        )
        val newLngRad = lngRad + Math.atan2(
            sin(bearingRad) * sin(distance / R) * cos(latRad),
            cos(distance / R) - sin(latRad) * sin(newLatRad)
        )

        var currentLat = Math.toDegrees(newLatRad)
        var currentLng = Math.toDegrees(newLngRad)

        // 步频模拟: 使用高斯噪声替代确定性正弦摆动
        // 真实行走中每步产生的横向偏移并非严格周期性的,而是受地面不平、步态差异等
        // 随机因素影响,因此用高斯采样模拟每步的随机横向偏移
        if (enableJitter && stepFreqHz > 0) {
            val stepJitterMeters = 0.15 * rng.nextGaussian()
            val perpBearingRad = bearingRad + Math.PI / 2
            currentLat += Math.toDegrees((stepJitterMeters * cos(perpBearingRad)) / R)
            currentLng += Math.toDegrees(
                (stepJitterMeters * sin(perpBearingRad)) / (R * cos(newLatRad))
            )
        }

        // 高斯随机游走: 在上一次漂移基础上叠加增量
        if (enableJitter) {
            applyGaussianDrift(currentTime, jitterRadius)
            currentLat += Math.toDegrees(driftLatMeters / R)
            currentLng += Math.toDegrees(driftLngMeters / (R * cos(newLatRad)))
        }

        // Accuracy: 基准值 + 高斯漂移(模拟GDOP变化引起的精度波动)
        val accuracy = if (enableJitter) computeGaussianAccuracy(jitterRadius) else 5.0f

        // Altitude: 基准值 + 高斯漂移(模拟对流层延迟引起的海拔漂移)
        val altitude = if (enableJitter) computeGaussianAltitude(stepFreqHz) else 10.0

        return SimulatedLocation(
            currentLat,
            currentLng,
            speedMs.toFloat(),
            bearingDeg,
            accuracy,
            altitude
        )
    }

    /**
     * 计算路线上的当前位置
     * @param points 路点列表
     * @param startTimestamp 开始时间戳
     * @param simModeName 模拟模式
     * @param currentTime 当前时间戳
     */
    fun calculateRoutePosition(
        points: List<RoutePoint>,
        startTimestamp: Long,
        simModeName: String,
        currentTime: Long = System.currentTimeMillis(),
        enableJitter: Boolean = true
    ): SimulatedLocation {
        if (points.size < 2) {
            val p = points.firstOrNull() ?: RoutePoint(0.0, 0.0)
            return SimulatedLocation(p.lat, p.lng, 0f, 0f, 5f, 0.0)
        }

        val elapsedSec = (currentTime - startTimestamp) / 1000.0
        if (elapsedSec <= 0) {
            val p = points.first()
            return SimulatedLocation(p.lat, p.lng, 0f, 0f, 5f, 0.0)
        }

        val (speedMs, stepFreqHz, jitterRadius) = getModeParams(simModeName)
        var remainingDist = speedMs * elapsedSec

        val totalDistance =
            (0 until points.size - 1).sumOf { i -> haversineDistance(points[i], points[i + 1]) }
        val gapDistance = haversineDistance(points.last(), points.first())
        val isLoop = gapDistance < 100.0

        var from: RoutePoint = points.first()
        var to: RoutePoint = points.first()
        var fraction: Double = 1.0

        if (isLoop) {
            val fullLoopDistance = (0 until points.size).sumOf { i ->
                val nextI = if (i == points.size - 1) 0 else i + 1
                haversineDistance(points[i], points[nextI]) + points[i].waitSec * speedMs
            }
            if (fullLoopDistance > 0) {
                remainingDist %= fullLoopDistance
            }

            var segStartIdx = 0
            while (true) {
                val fromPoint = points[segStartIdx]
                val waitDist = fromPoint.waitSec * speedMs

                if (remainingDist <= waitDist) {
                    from = fromPoint
                    to = fromPoint
                    fraction = 0.0
                    break
                }
                remainingDist -= waitDist

                val isLastToFirst = (segStartIdx == points.size - 1)
                val toPoint = if (isLastToFirst) points.first() else points[segStartIdx + 1]

                val segLen = haversineDistance(fromPoint, toPoint)
                if (remainingDist <= segLen) {
                    from = fromPoint
                    to = toPoint
                    fraction = if (segLen > 0) (remainingDist / segLen).coerceIn(0.0, 1.0) else 1.0
                    break
                }
                remainingDist -= segLen
                segStartIdx++
                if (segStartIdx >= points.size) segStartIdx = 0
            }
        } else {
            var segStartIdx = 0
            var found = false
            while (segStartIdx < points.size - 1) {
                val fromPoint = points[segStartIdx]
                val waitDist = fromPoint.waitSec * speedMs

                if (remainingDist <= waitDist) {
                    from = fromPoint
                    to = fromPoint
                    fraction = 0.0
                    found = true
                    break
                }
                remainingDist -= waitDist

                val toPoint = points[segStartIdx + 1]
                val segLen = haversineDistance(fromPoint, toPoint)
                if (remainingDist <= segLen) {
                    from = fromPoint
                    to = toPoint
                    fraction = if (segLen > 0) (remainingDist / segLen).coerceIn(0.0, 1.0) else 1.0
                    found = true
                    break
                }
                remainingDist -= segLen
                segStartIdx++
            }

            if (!found) {
                val lastPoint = points.last()
                val waitDist = lastPoint.waitSec * speedMs
                if (remainingDist <= waitDist) {
                    from = lastPoint
                    to = lastPoint
                    fraction = 0.0
                } else {
                    from = lastPoint
                    to = lastPoint
                    fraction = 1.0
                }
            }
        }

        val bearing = bearing(from, to)
        val bearingRad = Math.toRadians(bearing)
        val segLenFinal = haversineDistance(from, to)
        val interpDist = segLenFinal * fraction
        val fromLatRad = Math.toRadians(from.lat)
        val fromLngRad = Math.toRadians(from.lng)

        val newLatRad = Math.asin(
            sin(fromLatRad) * cos(interpDist / R) + cos(fromLatRad) * sin(interpDist / R) * cos(
                bearingRad
            )
        )
        val newLngRad = fromLngRad + Math.atan2(
            sin(bearingRad) * sin(interpDist / R) * cos(fromLatRad),
            cos(interpDist / R) - sin(fromLatRad) * sin(newLatRad)
        )

        var lat = Math.toDegrees(newLatRad)
        var lng = Math.toDegrees(newLngRad)

        // 步频模拟: 高斯随机横向偏移
        if (enableJitter && stepFreqHz > 0) {
            val stepJitterMeters = 0.15 * rng.nextGaussian()
            val perpBearingRad = bearingRad + Math.PI / 2
            lat += Math.toDegrees((stepJitterMeters * cos(perpBearingRad)) / R)
            lng += Math.toDegrees((stepJitterMeters * sin(perpBearingRad)) / (R * cos(newLatRad)))
        }

        // 高斯随机游走漂移
        if (enableJitter) {
            applyGaussianDrift(currentTime, jitterRadius)
            lat += Math.toDegrees(driftLatMeters / R)
            lng += Math.toDegrees(driftLngMeters / (R * cos(newLatRad)))
        }

        // Accuracy和Altitude: 高斯波动
        val accuracy = if (enableJitter) computeGaussianAccuracy(jitterRadius) else 5.0f
        val altitude = if (enableJitter) computeGaussianAltitude(stepFreqHz) else 10.0

        return SimulatedLocation(lat, lng, speedMs.toFloat(), bearing.toFloat(), accuracy, altitude)
    }

    /**
     * 高斯随机游走核心算法
     *
     * 数学模型: X(t+dt) = X(t) + sigma * sqrt(dt) * N(0,1) - alpha * X(t) * dt
     *
     * 其中:
     * - X(t): 当前累计漂移量(米)
     * - sigma: 漂移强度,由jitterRadius决定,控制每秒产生的随机偏移幅度
     * - sqrt(dt): 时间缩放因子,确保不同采样频率下漂移的统计特性一致(布朗运动标准化)
     * - N(0,1): 标准正态分布采样(Random.nextGaussian())
     * - alpha: 均值回归系数(0.05),防止漂移无限增长导致偏离目标过远
     *   物理含义: 真实GPS的滤波器(如Kalman滤波)会将异常漂移逐渐拉回,alpha模拟这一行为
     *
     * 为什么这样设计能绕过风控:
     * 1. nextGaussian()产生的序列在频域上是均匀分布(白噪声),FFT检测不到单频峰
     * 2. 累加式随机游走产生低频漂移分量,与真实GPS的多径效应漂移特征吻合
     * 3. 均值回归项使漂移量有界,避免因累计偏移过大触发"位置跳变"告警
     *
     * @param currentTime 当前时间戳,用于计算与上一次调用的时间差dt
     * @param jitterRadius 漂移强度基准(米),由移动模式决定(步行5m,驾驶2m等)
     */
    private fun applyGaussianDrift(currentTime: Long, jitterRadius: Double) {
        val dt = if (lastCallTime > 0) {
            ((currentTime - lastCallTime) / 1000.0).coerceIn(0.01, 5.0)
        } else {
            1.0
        }
        lastCallTime = currentTime

        // sigma: 将jitterRadius缩放为每秒的漂移标准差
        // 除以3.0是因为3-sigma法则: 99.7%的采样值落在[-jitterRadius, +jitterRadius]区间内
        val sigma = jitterRadius / 3.0

        // alpha: 均值回归强度,0.05表示每秒将当前漂移拉回5%
        val alpha = 0.05

        // 布朗运动增量 + Ornstein-Uhlenbeck均值回归
        driftLatMeters += sigma * sqrt(dt) * rng.nextGaussian() - alpha * driftLatMeters * dt
        driftLngMeters += sigma * sqrt(dt) * rng.nextGaussian() - alpha * driftLngMeters * dt
    }

    /**
     * 计算高斯波动的定位精度(Accuracy)
     *
     * 真实GPS的Accuracy值由接收机根据卫星几何分布(GDOP)和信噪比实时计算,
     * 其数值在一个基准值附近做缓慢的随机波动,而非固定常数或确定性正弦函数。
     *
     * 模型: baseAccuracy + gaussianDrift
     * - baseAccuracy: 由移动模式决定的基准精度(如步行模式约7m,驾驶模式约4m)
     * - gaussianDrift: 累加式高斯漂移,模拟GDOP随卫星运动的缓慢变化
     * - clamp到[2.0, 50.0]范围: 真实GPS的精度不可能为负或异常大
     *
     * @param jitterRadius 漂移半径基准(米),用于计算baseAccuracy
     * @return 带高斯波动的精度值(米)
     */
    private fun computeGaussianAccuracy(jitterRadius: Double): Float {
        // 精度漂移: 每次叠加一个小幅高斯增量,模拟GDOP缓慢变化
        accuracyDrift += 0.3 * rng.nextGaussian() - 0.02 * accuracyDrift
        val baseAccuracy = jitterRadius + 2.0
        return (baseAccuracy + accuracyDrift).coerceIn(2.0, 50.0).toFloat()
    }

    /**
     * 计算高斯波动的海拔高度(Altitude)
     *
     * 真实GPS的海拔精度通常比水平精度差2-3倍(垂直方向GDOP较大),
     * 因此海拔值的波动幅度应大于水平坐标。
     *
     * 模型: 10.0(基准海拔) + altitudeDrift
     * - 基准值10.0m: 模拟城市地面的典型海拔(实际应根据目标位置的真实海拔配置)
     * - altitudeDrift: 累加式高斯漂移,标准差0.5m,模拟对流层延迟的缓慢变化
     * - clamp到[0.0, 100.0]: 防止出现负海拔或异常高值
     *
     * @param stepFreqHz 步频(Hz),行走模式下海拔会有额外的步行起伏噪声
     * @return 带高斯波动的海拔值(米)
     */
    private fun computeGaussianAltitude(stepFreqHz: Double): Double {
        // 海拔漂移: 标准差0.5m,均值回归系数0.01(变化更缓慢)
        altitudeDrift += 0.5 * rng.nextGaussian() - 0.01 * altitudeDrift
        // 步行模式额外叠加微小起伏(模拟每步的重心升降,约2-3cm)
        val stepNoise = if (stepFreqHz > 0) 0.03 * rng.nextGaussian() else 0.0
        return (10.0 + altitudeDrift + stepNoise).coerceIn(0.0, 100.0)
    }

    data class ModeParams(val speedMs: Double, val stepFreqHz: Double, val jitterRadius: Double)

    fun getModeParams(simModeName: String): ModeParams = when (simModeName) {
        "WALKING" -> ModeParams(1.4, 2.0, 5.0)
        "RUNNING" -> ModeParams(3.0, 3.0, 8.0)
        "CYCLING" -> ModeParams(5.5, 0.0, 3.0)
        "DRIVING" -> ModeParams(15.0, 0.0, 2.0)
        else -> ModeParams(0.0, 0.0, 2.0)
    }

    /** 计算两点间的哈弗辛距离(米) */
    private fun haversineDistance(a: RoutePoint, b: RoutePoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h =
            sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * R * Math.atan2(sqrt(h), sqrt(1 - h))
    }

    /** 计算两点间的方位角(度) */
    private fun bearing(from: RoutePoint, to: RoutePoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }
}
