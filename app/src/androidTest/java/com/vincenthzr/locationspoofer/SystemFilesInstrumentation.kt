package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.vincenthzr.locationspoofer.data.repository.HookStatusRepository
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Checks framework configuration and system reports without changing simulation state. */
class SystemFilesInstrumentation : Instrumentation() {
    private var environmentRefresh = false
    private var moduleRuntime = false
    private var moduleHotReload = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        environmentRefresh = arguments?.getString("check") == "environment-refresh"
        moduleRuntime = arguments?.getString("check") == "module-runtime"
        moduleHotReload = arguments?.getString("check") == "module-hot-reload"
        start()
    }

    override fun onStart() {
        if (moduleHotReload) {
            val output = verifyModuleHotReload()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (moduleRuntime) {
            val output = verifyModuleRuntime()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (environmentRefresh) {
            val output = verifyEnvironmentRefresh()
            finish(if (output.getString("result")?.startsWith("PASS") == true) -1 else 0, output)
            return
        }
        val output = Bundle()
        try {
            val service = runBlocking {
                repeat(150) {
                    XposedModuleStatus.mService?.let { return@runBlocking it }
                    delay(100)
                }
                error("Framework did not connect; enable the module and restart the device")
            }
            val text = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                ?: error("No framework configuration; start a simulation first")
            val envelope = JSONObject(text)
            check(envelope.getInt("version") == FrameworkConfigChannel.VERSION)
            val file = envelope.optString("file")
            val payload = if (file.isEmpty()) envelope.getString("payload") else {
                check(FrameworkConfigChannel.isConfigFile(file))
                ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(file))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            check(JSONObject(payload).has("active"))
            val root = RootManager()
            val noLegacyTmp = root.executeCommand("""
                for name in locationspoofer_config.json locationspoofer_config_tmp.json .lsp_selinux_probe .lsp_sepolicy_apply.sh; do
                    test ! -e /proc/1/root/data/local/tmp/${'$'}name || exit 1
                done
            """.trimIndent())
            check(noLegacyTmp != "ERROR") { "Legacy tmp artifacts remain or root access is unavailable" }
            if (BuildConfig.GLOBAL_SCHEME) {
                val reports = runBlocking {
                    val repository = HookStatusRepository(root)
                    // Reconnecting the app may republish its snapshot; status files are debounced.
                    // Wait for that publication instead of comparing an immediately stale report.
                    repeat(40) {
                        val current = repository.readAll()
                        val latest = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                            .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                            ?.let { JSONObject(it).getLong("published_at") }
                        if (latest != null && current.values.all {
                                it?.configPath?.startsWith("libxposed:") == true && it.configModified == latest
                            }) return@runBlocking current
                        delay(250)
                    }
                    error("System processes have not converged on the latest publication; retry while coordinates are stationary")
                }
                output.putString("receivers", reports.keys.joinToString())
            }
            output.putString("transport", if (file.isEmpty()) "remote-preferences" else "remote-file")
            output.putString("result", "PASS")
            finish(-1, output)
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.stackTraceToString()}")
            finish(0, output)
        }
    }
}
