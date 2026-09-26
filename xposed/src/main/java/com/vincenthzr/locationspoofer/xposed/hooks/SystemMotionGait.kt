package com.vincenthzr.locationspoofer.xposed.hooks

import org.json.JSONObject

/** Floating-point gait phase shared by the global native step and accelerometer streams. */
internal object SystemMotionGait {
    private var cachedTemplateSource = ""
    private var cachedTemplate: com.vincenthzr.locationspoofer.utils.GaitTemplate? = null

    @Synchronized
    internal fun gaitTemplate(config: JSONObject): com.vincenthzr.locationspoofer.utils.GaitTemplate? {
        val source = config.optString("gait_template", "")
        if (source != cachedTemplateSource) {
            cachedTemplate = com.vincenthzr.locationspoofer.utils.GaitTemplate.decode(source)
            cachedTemplateSource = source
        }
        return cachedTemplate
    }

    internal fun realismSession(config: JSONObject) = com.vincenthzr.locationspoofer.utils.MotionRealism.session(
        config.optLong("start_timestamp", 0L), config.optInt("realism_level", 0),
        config.optInt("speed_fluctuation_pct", 0)
    )

    private val phaseClock = StepPhaseClock()

    @Synchronized
    internal fun calculateCurrentStepsFloat(config: JSONObject, now: Long): Double {
        val startTime = config.optLong("start_timestamp", now)
        val speed = com.vincenthzr.locationspoofer.xposed.utils.RouteEngine.calculateCurrentPosition(config, now).speed.toDouble()
        val running = config.optBoolean("native_sensor_enabled", false) && config.optBoolean("active", false) &&
            config.optBoolean("enable_step_simulation", true) && speed.isFinite() && speed > 0.1
        val cadence = if (config.optBoolean("is_auto_cadence", true)) SensorStepHooker.calculateAutoCadence(speed)
            else config.optInt("step_cadence_spm", 165).coerceIn(60, 240)
        val realism = realismSession(config)
        val elapsedMs = (now - startTime).coerceAtLeast(0L)
        return phaseClock.advance(startTime, android.os.SystemClock.elapsedRealtime(), running) { from, to ->
            realism.steps(cadence.toDouble(), elapsedMs / 1000.0) -
                realism.steps(cadence.toDouble(), (elapsedMs - (to - from)).coerceAtLeast(0L) / 1000.0)
        }
    }
}
