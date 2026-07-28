package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class RootManager {

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("id").contains("uid=0(root)")
    }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.suseoaa.locationspoofer android:mock_location allow")
        result != "ERROR"
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.suseoaa.locationspoofer android:mock_location deny")
        result != "ERROR"
    }

    fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.ifEmpty { "SUCCESS" }
        } catch (e: Exception) {
            "ERROR"
        }
    }

    fun executeCommandWithInput(command: String, input: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(input)
                writer.flush()
            }
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.ifEmpty { "SUCCESS" }
        } catch (e: Exception) {
            "ERROR"
        }
    }
}
