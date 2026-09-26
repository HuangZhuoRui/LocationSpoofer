package com.vincenthzr.locationspoofer.xposed.hooks

/** Shared incremental phase: cadence edits, pauses and new sessions never recompute past steps. */
internal class StepPhaseClock {
    private var phase = 2350.0
    private var previousTime = Long.MIN_VALUE
    private var previousSession = Long.MIN_VALUE
    private var wasRunning = false
    fun advance(session: Long, now: Long, running: Boolean, integrate: (Long, Long) -> Double): Double {
        if (now <= previousTime && session == previousSession) {
            if (!running) wasRunning = false
            return phase
        }
        val dt = if (previousTime == Long.MIN_VALUE) Long.MAX_VALUE else now - previousTime
        if (running && wasRunning && session == previousSession && dt in 1L..2000L) {
            val increment = integrate(previousTime, now)
            if (increment.isFinite() && increment > 0.0) phase += increment
        }
        previousTime = now
        previousSession = session
        wasRunning = running
        return phase
    }
}
