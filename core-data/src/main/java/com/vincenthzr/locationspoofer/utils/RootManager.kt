package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RootSetupTestResult
import com.vincenthzr.locationspoofer.data.model.RootSolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootManager {

    companion object {
        private const val TAG = "LocationSpoofer"
    }

    suspend fun checkRootAccess(solution: RootSolution = RootSolution.AUTO): Boolean =
        withContext(Dispatchers.IO) {
            val hasRoot = executeCommand("id").contains("uid=0(root)")
            if (hasRoot) applyRootBackgroundExemptions()
            hasRoot
        }

    suspend fun applyRootBackgroundExemptions(packageName: String = "com.vincenthzr.locationspoofer"): Boolean =
        withContext(Dispatchers.IO) {
            val cmds = """
                dumpsys deviceidle whitelist +$packageName 2>/dev/null || true
                cmd appops set $packageName RUN_IN_BACKGROUND allow 2>/dev/null || true
                cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
                cmd appops set $packageName WAKE_LOCK allow 2>/dev/null || true
                cmd appops set $packageName AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null || true
                am set-standby-bucket $packageName active 2>/dev/null || true
            """.trimIndent()
            executeCommand(cmds) != "ERROR"
        }

    /** Read-only diagnostics: no SELinux patches, probe files, or root scripts are needed. */
    suspend fun testRootSetup(solution: RootSolution = RootSolution.AUTO): RootSetupTestResult =
        withContext(Dispatchers.IO) {
            val idOutput = executeCommand("id")
            val service = XposedModuleStatus.mService
            var accessible = false
            var published = false
            var detail = "Framework disconnected"
            if (service != null) {
                try {
                    val snapshot = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                        .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                    accessible = true
                    published = snapshot != null
                    detail = if (published) "Configuration published; check Hook status for receiver confirmation"
                        else "Framework ready; no configuration published yet"
                } catch (error: Exception) {
                    detail = "${error.javaClass.simpleName}: ${error.message}"
                }
            }
            RootSetupTestResult(
                hasRoot = idOutput.contains("uid=0(root)"),
                idOutput = idOutput,
                solution = solution,
                frameworkConnected = service != null,
                remoteConfigAccessible = accessible,
                configPublished = published,
                frameworkDetail = detail
            )
        }

    /** Force-stops selected apps so their next launch loads the current module and configuration. */
    suspend fun forceStopApps(packages: List<String>): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            packages.map { pkg -> pkg to (executeCommand("am force-stop $pkg") != "ERROR") }
        }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.vincenthzr.locationspoofer android:mock_location allow")
        result != "ERROR"
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.vincenthzr.locationspoofer android:mock_location default")
        result != "ERROR"
    }

    fun executeCommand(command: String): String = runCommand(command, null)

    fun executeCommandWithInput(command: String, input: String): String = runCommand(command, input)

    private fun runCommand(command: String, input: String?): String {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            // Drain output while feeding stdin, so neither pipe can fill and deadlock.
            var output = ""
            val reader = kotlin.concurrent.thread(name = "LocationSpoofer-root-output", isDaemon = true) {
                output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    if (input != null) writer.write(input)
                }
                val exitCode = process.waitFor()
                reader.join()
                if (exitCode == 0) output.ifEmpty { "SUCCESS" }
                else {
                    android.util.Log.e(TAG, "Root command failed ($exitCode): ${output.take(2000)}")
                    "ERROR"
                }
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Root command failed", e)
            "ERROR"
        }
    }
}
