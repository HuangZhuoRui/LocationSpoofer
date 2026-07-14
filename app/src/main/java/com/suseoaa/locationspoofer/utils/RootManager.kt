package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun formatShellCommandResult(exitCode: Int, output: String): String {
    return if (exitCode == 0) {
        output.ifEmpty { "SUCCESS" }
    } else {
        "ERROR(exit=$exitCode): ${output.take(500)}"
    }
}

class RootManager {

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("id").contains("uid=0(root)")
    }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.suseoaa.locationspoofer android:mock_location allow")
        !result.startsWith("ERROR")
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.suseoaa.locationspoofer android:mock_location deny")
        !result.startsWith("ERROR")
    }

    fun executeCommand(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            formatShellCommandResult(exitCode, output)
        } catch (e: Exception) {
            "ERROR: ${e.message.orEmpty()}"
        }
    }
}
