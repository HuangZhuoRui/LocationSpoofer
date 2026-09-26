package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.Build
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object SystemAccelNative {
    @JvmStatic external fun install(): Boolean
    @JvmStatic external fun configure(
        enabled: Boolean,
        uids: IntArray,
        speed: Double,
        cadenceSpm: Int,
        totalSteps: Long,
        phaseSteps: Double = totalSteps.toDouble(),
        phaseCadence: Double = cadenceSpm.toDouble(),
        anchorNs: Long = android.os.SystemClock.elapsedRealtimeNanos(),
        session: Long = 0L,
        level: Int = 0,
        templateValues: FloatArray? = null
    )
    @JvmStatic external fun uids(): IntArray
    @JvmStatic external fun uninstall(): Boolean
}

/**
 * System-level form of upstream main/SensorStepHooker accelerometer simulation.
 *
 * GlobalSolution remains scoped only to android/system/phone/bluetooth. The native backend changes
 * accelerometer samples inside SensorService immediately before they are written to target apps.
 */
internal class SystemAccelHooker(
    private val module: LocationHooker
) : AutoCloseable {
    companion object {
        private const val TAG = "LS-SystemAccel"

    }

    private val started = AtomicBoolean(false)
    private var nativeLoaded = false
    private var nativeReady = false
    private var lastUidRefresh = 0L
    private var allowedUids = IntArray(0)

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LocationSpoofer-SystemAccel").apply { isDaemon = true }
        }

    private fun loadNative(): Boolean {
        if (nativeReady) return true
        if (Build.VERSION.SDK_INT != 36) {
            log("disabled: unsupported SDK ${Build.VERSION.SDK_INT}")
            return false
        }

        return try {
            if (!nativeLoaded) {
                // Fallback backend for LSPosed forks that load the JNI library but do not invoke
                // META-INF/xposed/native_init.list. Keep both libraries in the module namespace.
                System.loadLibrary("shadowhook")
                System.loadLibrary("location_accel")
                nativeLoaded = true
            }
            nativeReady = SystemAccelNative.install()
            if (!nativeReady) {
                log("native install returned false")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            log("install failed: $t")
            false
        }
    }

    @Synchronized
    fun install(): Boolean {
        if (Build.VERSION.SDK_INT != 36 || !Build.SUPPORTED_ABIS.contains("arm64-v8a")) return false
        if (!started.compareAndSet(false, true)) return true
        executor.scheduleWithFixedDelay(
            { runCatching { update() }.onFailure { log("update failed: $it") } },
            0L, 50L, TimeUnit.MILLISECONDS)
        return true
    }

    private var lastInstallAttempt = 0L
    @Synchronized
    private fun update() {
        if (!started.get()) return
        val config = module.readConfig()
        if (config != null) SystemMotionGait.calculateCurrentStepsFloat(config, System.currentTimeMillis())
        if (config == null || !config.optBoolean("native_sensor_enabled", false)) {
            if (nativeReady) SystemAccelNative.configure(false, IntArray(0), 0.0, 165, 2350L)
            return
        }
        if (!nativeReady) {
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (elapsed - lastInstallAttempt < 10000L) return
            lastInstallAttempt = elapsed
            if (!loadNative()) return
        }

        val now = System.currentTimeMillis()
        if (now - lastUidRefresh >= 500L) {
            allowedUids = SystemAccelNative.uids()
                .filter { uid -> SystemHookUtils.isTargetCaller(null, null, config, uid) }
                .toIntArray()
            lastUidRefresh = now
        }

        val enabled = config.optBoolean("active", false) &&
            config.optBoolean("enable_step_simulation", true)
        val motion = com.vincenthzr.locationspoofer.xposed.utils.RouteEngine.calculateCurrentPosition(config, now)
        val speed = if (enabled) motion.speed.toDouble().takeIf { it.isFinite() } ?: 0.0 else 0.0
        val cadence = if (config.optBoolean("is_auto_cadence", true)) SensorStepHooker.calculateAutoCadence(speed)
            else config.optInt("step_cadence_spm", 165).coerceIn(60, 240)
        val realism = SystemMotionGait.realismSession(config)
        val steps = SystemMotionGait.calculateCurrentStepsFloat(config, now)
        val elapsed = (now - config.optLong("start_timestamp", now)).coerceAtLeast(0L) / 1000.0
        val phaseCadence = realism.cadence(cadence.toDouble(), elapsed)
        val phase = steps
        val template = SystemMotionGait.gaitTemplate(config)
        SystemAccelNative.configure(
            enabled && speed > 0.1, allowedUids, speed, cadence, steps.toLong(), phase, phaseCadence,
            android.os.SystemClock.elapsedRealtimeNanos(), config.optLong("start_timestamp", 0L),
            config.optInt("realism_level", 0), template?.let { it.x + it.y + it.z }
        )

    }

    @Synchronized
    override fun close() {
        if (!started.getAndSet(false)) return
        runCatching { SystemAccelNative.configure(false, IntArray(0), 0.0, 165, 2350L) }
        executor.shutdownNow()
        if (nativeReady) runCatching { SystemAccelNative.uninstall() }
        nativeReady = false
    }

    private fun log(message: String) {
        runCatching { android.util.Log.i(TAG, message) }
        runCatching { XposedBridge.log("[$TAG] $message") }
    }
}
