package com.vincenthzr.locationspoofer.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.RouteEngine
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

internal object SystemStepNative {
    @JvmStatic external fun install(handles: IntArray, types: IntArray): Boolean
    @JvmStatic external fun uids(): IntArray
    @JvmStatic external fun tick(enabled: Boolean, uids: IntArray, total: Long, timestamps: LongArray)
    @JvmStatic external fun stats(): LongArray
    @JvmStatic external fun uninstall(): Boolean
}

/**
 * System-server STEP_COUNTER / STEP_DETECTOR delivery for GlobalSolution.
 *
 * The synthetic step phase comes from [SystemMotionGait], the same source used by
 * [SystemAccelHooker], so standard step callbacks and accelerometer gait cannot drift apart.
 * Native delivery is loaded only when native_sensor_enabled is enabled.
 */
internal class SystemStepHooker(private val module: LocationHooker) : AutoCloseable {
    companion object {
        private const val TAG = "LS-SystemSteps"
    }

    private val started = AtomicBoolean(false)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "LocationSpoofer-SystemSteps").apply { isDaemon = true }
    }

    private var nativeLoaded = false
    private var ready = false
    private var lastInitAttempt = 0L
    private var allowed = IntArray(0)
    private var lastTargets = 0L
    private var lastPolicy = ""
    private var lastLog = 0L

    private var previousPhase = Double.NaN
    private var previousNs = -1L
    private var previousSession = Long.MIN_VALUE
    private var previousEnabled = false

    @Synchronized
    fun install(): Boolean {
        if (Build.VERSION.SDK_INT != 36 || !Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            log("disabled: unsupported sensor ABI")
            return false
        }
        if (!started.compareAndSet(false, true)) return true
        executor.scheduleWithFixedDelay(
            { runCatching { update() }.onFailure { log("update failed: $it") } },
            0L, 20L, TimeUnit.MILLISECONDS
        )
        return true
    }

    private fun initialize(): Boolean {
        if (ready) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastInitAttempt < 10_000L) return false
        lastInitAttempt = now

        val context = SystemHookUtils.getSystemContext() ?: return false
        val manager = context.getSystemService(SensorManager::class.java) ?: return false
        val sensors = manager.getSensorList(Sensor.TYPE_ALL).filter {
            !it.isWakeUpSensor && (it.type == Sensor.TYPE_STEP_COUNTER || it.type == Sensor.TYPE_STEP_DETECTOR)
        }
        if (sensors.isEmpty()) return false

        return try {
            if (!nativeLoaded) {
                System.loadLibrary("shadowhook")
                System.loadLibrary("location_steps")
                nativeLoaded = true
            }
            val getHandle = Sensor::class.java.getDeclaredMethod("getHandle").apply { isAccessible = true }
            val handles = sensors.map { getHandle.invoke(it) as Int }.toIntArray()
            val types = sensors.map { it.type }.toIntArray()
            ready = SystemStepNative.install(handles, types)
            log("native ready=$ready sensors=${types.contentToString()} handles=${handles.contentToString()}")
            ready
        } catch (t: Throwable) {
            log("native init failed: $t")
            false
        }
    }

    @Synchronized
    private fun update() {
        if (!started.get()) return
        val config = module.readConfig()
        if (config != null) SystemMotionGait.calculateCurrentStepsFloat(config, System.currentTimeMillis())
        if (config == null || !config.optBoolean("native_sensor_enabled", false)) {
            if (ready) SystemStepNative.tick(false, IntArray(0), 0L, LongArray(0))
            resetTimeline()
            return
        }
        if (!ready && !initialize()) return

        val elapsed = SystemClock.elapsedRealtime()
        val policy = "${config.optBoolean("active")}|${config.optBoolean("system_hook_global_mode")}|${config.optJSONArray("system_hook_packages")}"
        if (elapsed - lastTargets >= 500L || policy != lastPolicy) {
            allowed = SystemStepNative.uids()
                .filter { uid -> SystemHookUtils.isTargetCaller(null, null, config, uid) }
                .toIntArray()
            lastTargets = elapsed
            lastPolicy = policy
        }

        val wallMs = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val motion = RouteEngine.calculateCurrentPosition(config, wallMs)
        val enabled = config.optBoolean("active", false) &&
            config.optBoolean("enable_step_simulation", true) &&
            motion.speed.isFinite() && motion.speed > 0.1f
        val session = config.optLong("start_timestamp", 0L)
        val phase = if (enabled) SystemMotionGait.calculateCurrentStepsFloat(config, wallMs) else previousPhase
        val timestamps = if (enabled && phase.isFinite()) crossingTimestamps(phase, elapsedNs, session) else LongArray(0)
        val total = if (phase.isFinite()) phase.toLong().coerceAtLeast(0L) else 0L

        SystemStepNative.tick(enabled, allowed, total, timestamps)
        previousEnabled = enabled
        previousSession = session
        previousNs = elapsedNs
        if (phase.isFinite()) previousPhase = phase

        if (elapsed - lastLog >= 10_000L) {
            val stats = SystemStepNative.stats()
            log("active=$enabled targets=${allowed.contentToString()} total=$total emittedNow=${timestamps.size} " +
                "connections=${stats.getOrElse(1) { -1 }} submitted=${stats.getOrElse(2) { -1 }} suppressed=${stats.getOrElse(3) { -1 }}")
            lastLog = elapsed
        }
    }

    private fun crossingTimestamps(phase: Double, nowNs: Long, session: Long): LongArray {
        val dt = nowNs - previousNs
        val continuous = previousEnabled && previousPhase.isFinite() && previousNs >= 0L &&
            previousSession == session && dt in 1L..2_000_000_000L && phase >= previousPhase
        if (!continuous) return LongArray(0)

        val delta = phase - previousPhase
        if (delta <= 0.0 || !delta.isFinite()) return LongArray(0)
        val first = floor(previousPhase) + 1.0
        val last = floor(phase)
        val count = (last - first + 1.0).toInt().coerceIn(0, 8)
        if (count == 0) return LongArray(0)

        return LongArray(count) { i ->
            val crossing = first + i
            val fraction = ((crossing - previousPhase) / delta).coerceIn(0.0, 1.0)
            previousNs + (dt * fraction).toLong().coerceIn(1L, dt)
        }
    }

    private fun resetTimeline() {
        previousPhase = Double.NaN
        previousNs = -1L
        previousSession = Long.MIN_VALUE
        previousEnabled = false
    }

    @Synchronized
    override fun close() {
        if (!started.getAndSet(false)) return
        if (ready) runCatching { SystemStepNative.tick(false, IntArray(0), 0L, LongArray(0)) }
        executor.shutdownNow()
        if (ready) runCatching { SystemStepNative.uninstall() }
        ready = false
        resetTimeline()
    }

    private fun log(message: String) {
        runCatching { android.util.Log.i(TAG, message) }
        runCatching { XposedBridge.log("[$TAG] $message") }
    }
}
