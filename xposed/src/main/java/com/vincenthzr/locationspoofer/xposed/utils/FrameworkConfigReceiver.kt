package com.vincenthzr.locationspoofer.xposed.utils

import android.content.SharedPreferences
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All framework access and JSON decoding happen outside the hooked call path. */
internal class FrameworkConfigReceiver(
    private val getPreferences: () -> SharedPreferences,
    private val readFile: (String) -> String,
    private val onConfig: (FrameworkConfigSnapshot) -> Unit,
    private val onError: (Exception) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "LocationSpoofer-ConfigReceiver").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var preferences: SharedPreferences? = null
    private var lastSnapshot: String? = null
    private var lastErrorAt = 0L
    // Keep a strong reference: SharedPreferences implementations may store weak listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == FrameworkConfigChannel.SNAPSHOT_KEY) {
            if (!closed) runCatching { executor.execute { refresh() } }
        }
    }

    fun start() {
        // Module lifecycle initialization precedes hook installation. Hook reads remain memory-only.
        refresh()
        // Retry early-boot failures and reconcile the latest snapshot if a notification was missed.
        executor.scheduleWithFixedDelay({ refresh() }, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized
    internal fun refresh() {
        if (closed) return
        try {
            val prefs = preferences ?: getPreferences().also {
                it.registerOnSharedPreferenceChangeListener(listener)
                preferences = it
            }
            val text = prefs.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null) ?: return
            if (text == lastSnapshot) return
            val snapshot = FrameworkConfigSnapshot.decode(text, readFile)
            if (closed) return
            onConfig(snapshot)
            lastSnapshot = text
        } catch (error: Exception) {
            // Leave the last valid memory snapshot intact; a failed blob read is retried next tick.
            preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
            preferences = null
            val now = System.currentTimeMillis()
            if (now - lastErrorAt >= 60_000) {
                lastErrorAt = now
                runCatching { onError(error) }
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        executor.shutdownNow()
        preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        preferences = null
    }
}
