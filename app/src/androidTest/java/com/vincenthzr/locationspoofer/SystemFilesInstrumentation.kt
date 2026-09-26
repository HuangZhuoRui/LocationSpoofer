package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import android.util.Base64
import com.vincenthzr.locationspoofer.data.repository.HookStatusRepository
import com.vincenthzr.locationspoofer.utils.ConfigManager
import com.vincenthzr.locationspoofer.utils.RootManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Device-only regression: execute production code under the app's UID and mount namespace. */
class SystemFilesInstrumentation : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }

    override fun onStart() {
        val output = Bundle()
        try {
            val root = RootManager()
            check(root.executeCommand("exit 7") == "ERROR") { "Nonzero exit was reported as success" }
            check(root.executeCommandWithInput("cat >/dev/null; exit 9", "probe") == "ERROR")
            val reports = runBlocking { HookStatusRepository(root).readAll() }
            check(reports.values.all { it != null }) { "Missing reports: ${reports.filterValues { it == null }.keys}" }
            output.putString("reports", reports.keys.joinToString())
            val manager = ConfigManager(targetContext, root)
            val command = ConfigManager::class.java.getDeclaredMethod("globalWriteCommand")
                .apply { isAccessible = true }.invoke(manager) as String
            val paths = listOf("/data/system", "/data/local/tmp", "/data/user_de/0/com.android.phone/files",
                "/data/user_de/0/com.android.bluetooth/files", "/data/data/com.vincenthzr.locationspoofer/files")
                .map { "/proc/1/root$it/locationspoofer_config.json" }
            val originals = paths.associateWith { root.executeCommand("cat $it") }
            check(originals.values.all { it.startsWith("{") })
            val supplied = options.getString("config")
            val payload = if (supplied != null) String(Base64.decode(supplied, Base64.DEFAULT), Charsets.UTF_8)
                else JSONObject(originals.getValue(paths[0])).put("namespace_regression", System.nanoTime()).toString()
            val backup = "/data/local/tmp/locationspoofer-namespace-test-${android.os.Process.myPid()}"
            check(root.executeCommand("mkdir $backup") != "ERROR")
            paths.forEachIndexed { index, path -> check(root.executeCommand("cp -a $path $backup/$index") != "ERROR") }
            try {
                check(root.executeCommandWithInput(command, payload) != "ERROR") { "Production config write failed" }
                paths.forEach { check(root.executeCommand("cat $it") == payload) { "Stale config: $it" } }
                manager.syncDomainConfigs()
                paths.take(4).forEach { check(root.executeCommand("cat $it") == payload) }
                output.putString("config", "All five copies match after production write and domain sync")
            } finally {
                if (supplied == null) {
                    paths.forEachIndexed { index, path ->
                        val current = root.executeCommand("cat $path")
                        check(current == payload || current == originals[path]) { "User edit detected: backup at $backup" }
                        check(root.executeCommand("cp -a $backup/$index $path") != "ERROR")
                        check(root.executeCommand("cat $path") == originals[path])
                    }
                    check(root.executeCommand("rm -f $backup/0 $backup/1 $backup/2 $backup/3 $backup/4; rmdir $backup") != "ERROR")
                } else output.putString("backup", backup)
            }
            output.putString("result", "PASS")
            finish(-1, output)
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.stackTraceToString()}")
            finish(0, output)
        }
    }
}
