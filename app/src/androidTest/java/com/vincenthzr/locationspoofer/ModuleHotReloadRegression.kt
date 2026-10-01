package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.vendor.HookStatusFiles
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

private suspend fun moduleService(): XposedService {
    repeat(150) {
        XposedModuleStatus.mService?.let { return it }
        delay(100)
    }
    error("Framework service unavailable")
}

/** Module-scoped diagnostics; does not start or stop a simulation. */
internal fun Instrumentation.verifyModuleRuntime(): Bundle {
    val output = Bundle()
    try {
        val service = runBlocking { moduleService() }
        output.putString("framework", "${service.frameworkName} ${service.frameworkVersion} API ${service.apiVersion}")
        output.putString("scope", service.scope.sorted().joinToString())
        if (service.apiVersion >= 102) {
            output.putString("targets", service.runningTargets.joinToString { target ->
                "${target.processName}:pid=${target.pid},state=${target.state},version=${target.loadedVersionCode}"
            })
        }
        val text = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
            .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
        if (text != null) {
            val envelope = JSONObject(text)
            val payload = envelope.optString("payload")
            output.putString("configuration", "published=${envelope.optLong("published_at")},inline=${payload.isNotEmpty()},active=${if (payload.isNotEmpty()) JSONObject(payload).optBoolean("active") else "remote-file"}")
        }
        output.putString("result", "PASS")
    } catch (error: Throwable) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    }
    return output
}

/** Exercises actual API 102 generation replacement, preserving scope and simulation payload. */
internal fun Instrumentation.verifyModuleHotReload(): Bundle {
    val output = Bundle()
    try {
        runBlocking {
            val service = moduleService()
            check(BuildConfig.GLOBAL_SCHEME && service.apiVersion >= 102)
            val scope = service.scope.sorted()
            val targets = service.runningTargets
            val pids = targets.associate { it.processName to it.pid }
            check(pids.keys == setOf("system", "com.android.phone", "com.android.bluetooth")) {
                "Unexpected targets: $pids"
            }
            val preferences = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
            val original = JSONObject(requireNotNull(preferences.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)))
            // This regression only republishes the inactive existing payload, never starts a simulation.
            check(original.optString("file").isEmpty() && !JSONObject(original.getString("payload")).optBoolean("active"))
            val root = RootManager()
            fun reports(): Map<String, JSONObject> = HookStatusFiles.PATHS.mapValues { (_, paths) ->
                val command = paths.joinToString(" || ") { "cat '/proc/1/root$it' 2>/dev/null" }
                JSONObject(root.executeCommand(command))
            }
            fun hookCounts(report: JSONObject): Map<String, Int> = buildMap {
                val components = report.getJSONArray("components")
                repeat(components.length()) { index ->
                    val component = components.getJSONObject(index)
                    if (component.optBoolean("found")) {
                        val methods = component.getJSONObject("methods")
                        methods.keys().forEach { name -> put("${component.getString("name")}.$name", methods.getInt(name)) }
                    }
                }
            }
            var baseline = reports()
            repeat(2) { cycle ->
                for (target in service.runningTargets) {
                    val completion = CompletableDeferred<HotReloadResult>()
                    service.hotReloadModule(target, null) { _, result -> completion.complete(result) }
                    val result = withTimeout(30_000) { completion.await() }
                    check(result.status() == HotReloadResult.Status.SUCCEEDED) {
                        "${target.processName}: ${result.status()} ${result.message()}"
                    }
                }
                val newSnapshot = JSONObject(original.toString()).apply {
                    put("id", UUID.randomUUID().toString())
                    put("published_at", System.currentTimeMillis())
                }
                check(preferences.edit().putString(FrameworkConfigChannel.SNAPSHOT_KEY, newSnapshot.toString()).commit())
                val timestamp = newSnapshot.getLong("published_at")
                var after: Map<String, JSONObject>? = null
                withTimeout(20_000) {
                    while (after == null) {
                        delay(500)
                        val current = reports()
                        if (current.all { (process, report) ->
                                report.optString("generation").isNotBlank() &&
                                    report.optString("generation") != baseline.getValue(process).optString("generation") &&
                                    report.optJSONObject("config")?.optLong("modified") == timestamp
                            }) after = current
                    }
                }
                val verified = requireNotNull(after)
                verified.forEach { (process, report) ->
                    val target = when (process) {
                        "SYSTEM_SERVER" -> "system"
                        "PHONE" -> "com.android.phone"
                        else -> "com.android.bluetooth"
                    }
                    check(report.getInt("pid") == pids.getValue(target)) { "$process restarted" }
                    check(report.getString("load_kind") == "hot-reload")
                    check(report.getJSONArray("errors").length() == 0) { "$process: ${report.getJSONArray("errors")}" }
                    val counts = hookCounts(report)
                    check(counts.isNotEmpty() && hookCounts(baseline.getValue(process)).all { (method, count) ->
                        counts.getOrDefault(method, 0) >= count
                    }) { "$process lost hooks" }
                }
                check(service.scope.sorted() == scope)
                check(service.runningTargets.associate { it.processName to it.pid } == pids)
                output.putString("cycle_${cycle + 1}", "All three generations replaced; hooks intact; latest configuration received; PIDs unchanged")
                baseline = verified
            }
            output.putString("pids", pids.toString())
            output.putString("scope", scope.joinToString())
        }
        output.putString("result", "PASS")
    } catch (error: Throwable) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    }
    return output
}
