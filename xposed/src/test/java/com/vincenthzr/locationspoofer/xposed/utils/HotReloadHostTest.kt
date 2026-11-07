package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.*
import org.junit.Test

class HotReloadHostTest {
    private val loader = ClassLoader.getSystemClassLoader()

    @Test fun newGenerationReinstallsUsingSavedHostWithoutPackageCallbackReplay() {
        val oldGeneration = HotReloadHost("android", loader)
        val events = mutableListOf<String>()
        var newGenerationHost: HotReloadHost? = null
        reinstallAfterHotReload(oldGeneration.save(), { error("Saved host must take priority") },
            { events += "unhook" }, { host -> newGenerationHost = host; events += "install" })
        assertEquals(listOf("unhook", "install"), events)
        assertEquals("android", newGenerationHost?.packageName)
        assertSame(loader, newGenerationHost?.classLoader)
        assertNull("No old module-defined state object crosses the boundary", oldGeneration.save().javaClass.classLoader)
    }

    @Test fun firstUpgradeFromLegacyVersionRecoversAlreadyRunningHost() {
        var installed = false
        reinstallAfterHotReload(null, { HotReloadHost("com.android.phone", loader) }, {}, {
            assertEquals("com.android.phone", it.packageName)
            assertSame(loader, it.classLoader)
            installed = true
        })
        assertTrue(installed)
    }

    @Test fun missingHostCannotRemoveWorkingHooks() {
        var removed = false
        try {
            reinstallAfterHotReload(null, { null }, { removed = true }, { fail("No valid host") })
            fail("Expected an explicit reload failure")
        } catch (_: IllegalStateException) {
            assertFalse(removed)
        }
    }

    @Test fun invalidOrModuleDefinedStateUsesHostRecovery() {
        val invalid = listOf(HotReloadHost("android", loader), arrayOf("", loader), arrayOf("android", "not a loader"), emptyArray<Any>())
        invalid.forEach { state ->
            reinstallAfterHotReload(state, { HotReloadHost("com.android.bluetooth", loader) }, {}, {
                assertEquals("com.android.bluetooth", it.packageName)
            })
        }
    }
}
