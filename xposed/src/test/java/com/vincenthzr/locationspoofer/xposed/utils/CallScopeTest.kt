package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors

class CallScopeTest {
    @Test fun `nested provider calls restore the original owner`() {
        val scope = CallScope<Any>()
        val first = Any()
        val second = Any()
        scope.withValue(first) {
            assertSame(first, scope.current)
            scope.withValue(second) { assertSame(second, scope.current) }
            assertSame(first, scope.current)
            scope.withValue(null) { assertNull(scope.current) }
            assertSame(first, scope.current)
        }
        assertNull(scope.current)
    }

    @Test fun `permission exceptions cannot leak the target onto a binder thread`() {
        val scope = CallScope<Any>()
        val denied = SecurityException("location permission denied")
        try {
            scope.withValue(Any()) { throw denied }
        } catch (actual: SecurityException) {
            assertSame(denied, actual)
        }
        assertNull(scope.current)
        assertEquals("original result", scope.withValue(null) { "original result" })
    }

    @Test fun `another binder thread never inherits the request scope`() {
        val scope = CallScope<String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            scope.withValue("target") {
                executor.submit {
                    assertNull(scope.current)
                    scope.withValue("other") { assertEquals("other", scope.current) }
                    assertNull(scope.current)
                }.get()
                assertEquals("target", scope.current)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
