package com.suseoaa.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootManagerTest {
    @Test
    fun successfulEmptyCommandReturnsSuccessMarker() {
        assertEquals("SUCCESS", formatShellCommandResult(0, ""))
    }

    @Test
    fun nonZeroCommandIncludesExitCodeAndOutput() {
        val result = formatShellCommandResult(17, "permission denied")

        assertTrue(result.startsWith("ERROR(exit=17):"))
        assertTrue(result.contains("permission denied"))
    }
}
