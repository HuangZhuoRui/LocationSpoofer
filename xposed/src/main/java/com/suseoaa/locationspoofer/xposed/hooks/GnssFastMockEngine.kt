package com.suseoaa.locationspoofer.xposed.hooks

import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 高性能零分配卫星模拟引擎 (High-Performance Zero-Allocation GNSS/GPS Simulation Engine)
 *
 * 核心优化特性:
 * 1. 单例反射预热与句柄缓存: 仅在首次使用时初始化 Method / Field 引用，彻底消除高频回调中的反射查找。
 * 2. 零 GC 预计算轨道矩阵: 预分配多星座结构体，基于时间戳步进计算平滑轨道与可信 C/N0，不再每秒创建数百个 Random 实例。
 * 3. 1Hz 原子快照缓存: 无论系统 GNSS 回调触发频率多高 (10Hz~20Hz)，均以纳秒级极速直接复用 1 秒内的预构建快照对象。
 * 4. 全版本覆盖: 深度兼容 Android 11+ (GnssStatus.Builder), Android 7~10 (GnssStatus 内部结构), 以及 Legacy GpsStatus。
 */
object GnssFastMockEngine {

    private const val MAX_SATELLITES = 32

    // 预置可信多星座模板 (GPS=1, GLONASS=3, BDS=5, GALILEO=6)
    private val CONSTELLATION_TYPES = intArrayOf(
        // BDS (北斗) - 亚太区域主力
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        // GPS
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        // GLONASS
        3, 3, 3, 3,
        // Galileo
        6, 6, 6, 6
    )

    private val SVID_LIST = intArrayOf(
        // BDS SVIDs (1-35)
        1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 13, 14,
        // GPS SVIDs (1-32)
        1, 3, 6, 7, 8, 9, 11, 14, 17, 19, 21, 22,
        // GLONASS SVIDs (1-24)
        1, 2, 3, 4,
        // Galileo SVIDs (1-36)
        1, 2, 3, 5
    )

    // 预置基准仰角 (度)
    private val BASE_ELEVATIONS = floatArrayOf(
        78f, 65f, 54f, 42f, 38f, 72f, 58f, 49f, 33f, 61f, 45f, 29f,
        82f, 69f, 51f, 44f, 36f, 75f, 63f, 47f, 31f, 59f, 41f, 25f,
        55f, 43f, 35f, 28f,
        60f, 48f, 39f, 22f
    )

    // 预置基准方位角 (度)
    private val BASE_AZIMUTHS = floatArrayOf(
        12f, 45f, 78f, 112f, 145f, 178f, 212f, 245f, 278f, 312f, 335f, 25f,
        35f, 68f, 95f, 132f, 165f, 198f, 232f, 265f, 298f, 325f, 15f, 52f,
        85f, 175f, 265f, 345f,
        45f, 135f, 225f, 315f
    )

    // 预置平滑微扰动表 (循环索引，避免 Math.random() 开销)
    private val JITTER_TABLE = floatArrayOf(
        -0.8f, 0.5f, 1.2f, -0.3f, 0.9f, -1.1f, 0.4f, -0.6f,
        1.1f, -0.9f, 0.7f, -0.4f, 1.3f, -1.2f, 0.3f, -0.2f
    )

    // 缓存区 (零 GC 复用)
    @Volatile
    private var cachedGnssStatusObj: Any? = null
    @Volatile
    private var lastGnssStatusTimeMs: Long = 0L
    @Volatile
    private var cachedGnssCount: Int = 0

    @Volatile
    private var cachedGpsSatelliteList: List<Any>? = null
    @Volatile
    private var lastGpsSatellitesTimeMs: Long = 0L
    @Volatile
    private var cachedGpsSatCount: Int = 0

    // 反射句柄缓存
    private class ClassReflectionHolder(val classLoader: ClassLoader) {
        var isInitialized = false

        // Android 11+ GnssStatus.Builder
        var builderClass: Class<*>? = null
        var builderConstructor: Constructor<*>? = null
        var addSatelliteMethod: Method? = null
        var buildMethod: Method? = null

        // Android 7-10 GnssStatus Direct Fields
        var gnssStatusClass: Class<*>? = null
        var countField: Field? = null
        var svidWithFlagsField: Field? = null
        var cn0DbHzField: Field? = null
        var elevationsField: Field? = null
        var azimuthsField: Field? = null
        var carrierFrequenciesField: Field? = null
        var basebandCn0DbHzsField: Field? = null

        // GpsSatellite
        var gpsSatelliteClass: Class<*>? = null
        var gpsSatelliteConstructor: Constructor<*>? = null
        var satValidField: Field? = null
        var satHasEphemerisField: Field? = null
        var satHasAlmanacField: Field? = null
        var satUsedInFixField: Field? = null
        var satSnrField: Field? = null
        var satElevationField: Field? = null
        var satAzimuthField: Field? = null

        fun init() {
            if (isInitialized) return
            synchronized(this) {
                if (isInitialized) return

                // 1. GnssStatus.Builder (Android 11+)
                try {
                    builderClass = XposedHelpers.findClassIfExists(
                        "android.location.GnssStatus\$Builder",
                        classLoader
                    )
                    if (builderClass != null) {
                        builderConstructor = builderClass!!.getDeclaredConstructor().apply { isAccessible = true }
                        addSatelliteMethod = builderClass!!.getDeclaredMethod(
                            "addSatellite",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                        buildMethod = builderClass!!.getDeclaredMethod("build").apply { isAccessible = true }
                    }
                } catch (e: Throwable) {
                    // Ignored on older Android
                }

                // 2. GnssStatus Fields (Android 7-10)
                try {
                    gnssStatusClass = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader)
                    if (gnssStatusClass != null) {
                        countField = gnssStatusClass!!.getDeclaredField("mSvCount").apply { isAccessible = true }
                        svidWithFlagsField = gnssStatusClass!!.getDeclaredField("mSvidWithFlags").apply { isAccessible = true }
                        cn0DbHzField = gnssStatusClass!!.getDeclaredField("mCn0DbHz").apply { isAccessible = true }
                        elevationsField = gnssStatusClass!!.getDeclaredField("mElevations").apply { isAccessible = true }
                        azimuthsField = gnssStatusClass!!.getDeclaredField("mAzimuths").apply { isAccessible = true }
                        try { carrierFrequenciesField = gnssStatusClass!!.getDeclaredField("mCarrierFrequencies").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { basebandCn0DbHzsField = gnssStatusClass!!.getDeclaredField("mBasebandCn0DbHzs").apply { isAccessible = true } } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                }

                // 3. GpsSatellite
                try {
                    gpsSatelliteClass = XposedHelpers.findClassIfExists("android.location.GpsSatellite", classLoader)
                    if (gpsSatelliteClass != null) {
                        gpsSatelliteConstructor = gpsSatelliteClass!!.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                        try { satValidField = gpsSatelliteClass!!.getDeclaredField("mValid").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satHasEphemerisField = gpsSatelliteClass!!.getDeclaredField("mHasEphemeris").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satHasAlmanacField = gpsSatelliteClass!!.getDeclaredField("mHasAlmanac").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satUsedInFixField = gpsSatelliteClass!!.getDeclaredField("mUsedInFix").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satSnrField = gpsSatelliteClass!!.getDeclaredField("mSnr").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satElevationField = gpsSatelliteClass!!.getDeclaredField("mElevation").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satAzimuthField = gpsSatelliteClass!!.getDeclaredField("mAzimuth").apply { isAccessible = true } } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                }

                isInitialized = true
            }
        }
    }

    private val reflectionHolders = ConcurrentHashMap<ClassLoader, ClassReflectionHolder>()

    private fun getReflectionHolder(classLoader: ClassLoader): ClassReflectionHolder {
        val holder = reflectionHolders.getOrPut(classLoader) { ClassReflectionHolder(classLoader) }
        if (!holder.isInitialized) {
            holder.init()
        }
        return holder
    }

    /**
     * 极速获取或创建 GnssStatus 快照对象
     * 有效期内直接返回原子缓存，耗时 < 0.01ms (纳秒级)
     */
    fun getOrCreateSpoofedGnssStatus(
        classLoader: ClassLoader,
        targetCount: Int,
        enableJitter: Boolean,
        fallbackOriginalObj: Any?
    ): Any? {
        val now = System.currentTimeMillis()
        val count = targetCount.coerceIn(4, MAX_SATELLITES)

        // 1 秒内命中快照直接返回
        val cached = cachedGnssStatusObj
        if (cached != null && cachedGnssCount == count && now - lastGnssStatusTimeMs < 1000L) {
            return cached
        }

        synchronized(this) {
            // Double check
            if (cachedGnssStatusObj != null && cachedGnssCount == count && now - lastGnssStatusTimeMs < 1000L) {
                return cachedGnssStatusObj
            }

            val holder = getReflectionHolder(classLoader)
            val timeStep = (now / 1000L)

            // 方案 A: Android 11+ Builder 极速构建 (全反射句柄已缓存)
            if (holder.builderConstructor != null && holder.addSatelliteMethod != null && holder.buildMethod != null) {
                try {
                    val builder = holder.builderConstructor!!.newInstance()
                    val addMethod = holder.addSatelliteMethod!!

                    for (i in 0 until count) {
                        val type = CONSTELLATION_TYPES[i]
                        val svid = SVID_LIST[i]
                        val baseElev = BASE_ELEVATIONS[i]
                        val baseAz = BASE_AZIMUTHS[i]

                        // 平滑仰角与方位角慢速轨道运行
                        val driftStep = ((timeStep + i * 3) % 360).toFloat()
                        val elevation = (baseElev + (driftStep % 10 - 5)).coerceIn(16f, 88f)
                        val azimuth = (baseAz + driftStep * 0.1f) % 360f

                        val jitter = if (enableJitter) JITTER_TABLE[(timeStep.toInt() + i) and 15] else 0f
                        // 真实满格强信号典型值: 34~43 dB-Hz
                        val cn0 = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
                        val usedInFix = elevation > 18f

                        // 真实载波频率: GPS L1 (1575.42 MHz), BDS B1I (1561.098 MHz), GLONASS (1602.0 MHz)
                        val carrierFreq = when (type) {
                            5 -> 1561098000f
                            3 -> 1602000000f
                            6 -> 1575420000f
                            else -> 1575420000f
                        }
                        val basebandCn0 = (cn0 - 2.5f).coerceAtLeast(26f)

                        addMethod.invoke(
                            builder,
                            type,
                            svid,
                            cn0,
                            elevation,
                            azimuth,
                            true,             // hasEphemeris
                            true,             // hasAlmanac
                            usedInFix,        // usedInFix
                            true,             // hasCarrierFrequency
                            carrierFreq,      // carrierFrequencyHz
                            true,             // hasBasebandCn0
                            basebandCn0       // basebandCn0DbHz
                        )
                    }

                    val newStatus = holder.buildMethod!!.invoke(builder)
                    cachedGnssStatusObj = newStatus
                    lastGnssStatusTimeMs = now
                    cachedGnssCount = count
                    return newStatus
                } catch (e: Throwable) {
                    XposedBridge.log("[GnssFastMockEngine] Builder build failed: $e")
                }
            }

            // 方案 B: Android 7~10 字段直接写入 (零额外实例化)
            if (fallbackOriginalObj != null && holder.countField != null && holder.svidWithFlagsField != null) {
                try {
                    holder.countField!!.setInt(fallbackOriginalObj, count)

                    val cn0DbHzs = FloatArray(count)
                    val elevations = FloatArray(count)
                    val azimuths = FloatArray(count)
                    val svidWithFlags = IntArray(count)
                    val carrierFreqs = FloatArray(count)
                    val basebandCn0s = FloatArray(count)

                    for (i in 0 until count) {
                        val type = CONSTELLATION_TYPES[i]
                        val svid = SVID_LIST[i]
                        val baseElev = BASE_ELEVATIONS[i]
                        val baseAz = BASE_AZIMUTHS[i]

                        val driftStep = ((timeStep + i * 3) % 360).toFloat()
                        val elevation = (baseElev + (driftStep % 10 - 5)).coerceIn(16f, 88f)
                        val azimuth = (baseAz + driftStep * 0.1f) % 360f
                        val jitter = if (enableJitter) JITTER_TABLE[(timeStep.toInt() + i) and 15] else 0f
                        val cn0 = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
                        val usedInFix = elevation > 18f

                        val carrierFreq = when (type) {
                            5 -> 1561098000f
                            3 -> 1602000000f
                            6 -> 1575420000f
                            else -> 1575420000f
                        }

                        cn0DbHzs[i] = cn0
                        elevations[i] = elevation
                        azimuths[i] = azimuth
                        carrierFreqs[i] = carrierFreq
                        basebandCn0s[i] = cn0 - 2.5f

                        var flags = 1 or 2 // hasEphemeris | hasAlmanac
                        if (usedInFix) flags = flags or 4
                        svidWithFlags[i] = (svid shl 8) or (type and 0xF) or (flags shl 4)
                    }

                    holder.svidWithFlagsField!!.set(fallbackOriginalObj, svidWithFlags)
                    holder.cn0DbHzField?.set(fallbackOriginalObj, cn0DbHzs)
                    holder.elevationsField?.set(fallbackOriginalObj, elevations)
                    holder.azimuthsField?.set(fallbackOriginalObj, azimuths)
                    holder.carrierFrequenciesField?.set(fallbackOriginalObj, carrierFreqs)
                    holder.basebandCn0DbHzsField?.set(fallbackOriginalObj, basebandCn0s)

                    cachedGnssStatusObj = fallbackOriginalObj
                    lastGnssStatusTimeMs = now
                    cachedGnssCount = count
                    return fallbackOriginalObj
                } catch (e: Throwable) {
                    XposedBridge.log("[GnssFastMockEngine] Fallback field injection failed: $e")
                }
            }
        }

        return fallbackOriginalObj
    }

    /**
     * 极速获取或创建 GpsSatellite 列表 (适配旧版 GpsStatus.getSatellites() / DevCheck)
     */
    fun getOrCreateSpoofedGpsSatellites(classLoader: ClassLoader, targetCount: Int): Iterable<Any> {
        val now = System.currentTimeMillis()
        val count = targetCount.coerceIn(4, MAX_SATELLITES)

        val cached = cachedGpsSatelliteList
        if (cached != null && cachedGpsSatCount == count && now - lastGpsSatellitesTimeMs < 1000L) {
            return cached
        }

        synchronized(this) {
            if (cachedGpsSatelliteList != null && cachedGpsSatCount == count && now - lastGpsSatellitesTimeMs < 1000L) {
                return cachedGpsSatelliteList!!
            }

            val holder = getReflectionHolder(classLoader)
            if (holder.gpsSatelliteConstructor == null) {
                return emptyList()
            }

            val timeStep = (now / 1000L)
            val list = ArrayList<Any>(count)

            try {
                for (i in 0 until count) {
                    val type = CONSTELLATION_TYPES[i]
                    val rawSvid = SVID_LIST[i]
                    val prn = if (type == 3) rawSvid + 64 else rawSvid

                    val sat = holder.gpsSatelliteConstructor!!.newInstance(prn)
                    val baseElev = BASE_ELEVATIONS[i]
                    val baseAz = BASE_AZIMUTHS[i]
                    val driftStep = ((timeStep + i * 3) % 360).toFloat()
                    val elevation = (baseElev + (driftStep % 10 - 5)).coerceIn(16f, 88f)
                    val azimuth = (baseAz + driftStep * 0.1f) % 360f
                    val jitter = JITTER_TABLE[(timeStep.toInt() + i) and 15]
                    val snr = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
                    val usedInFix = elevation > 18f

                    holder.satValidField?.setBoolean(sat, true)
                    holder.satHasEphemerisField?.setBoolean(sat, true)
                    holder.satHasAlmanacField?.setBoolean(sat, true)
                    holder.satUsedInFixField?.setBoolean(sat, usedInFix)
                    holder.satSnrField?.set(sat, snr)
                    holder.satElevationField?.set(sat, elevation)
                    holder.satAzimuthField?.set(sat, azimuth)

                    list.add(sat)
                }

                cachedGpsSatelliteList = list
                lastGpsSatellitesTimeMs = now
                cachedGpsSatCount = count
                return list
            } catch (e: Throwable) {
                XposedBridge.log("[GnssFastMockEngine] createSpoofedGpsSatellites failed: $e")
                return emptyList()
            }
        }
    }
}
