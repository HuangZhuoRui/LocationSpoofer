package com.vincenthzr.locationspoofer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookStatusReportTest {

    private val sample = """
        {"format":1,"process":"SYSTEM_SERVER","updated_at":1000,
         "vendor":{"id":"hyperos","family":"HYPEROS_MIUI","manual":false},
         "system":"HyperOS 4.0 · Android 17","model":"2509FPN0BC",
         "components":[
           {"name":"WIFI_SERVICE","found":true,"searched":true,"class":"com.android.server.wifi.WifiServiceImpl",
            "source":"thread:WifiHandlerThread","methods":{"getScanResults":1,"getConnectionInfo":1}},
           {"name":"CONNECTIVITY_SERVICE","found":true,"searched":true,"class":"com.android.server.ConnectivityService",
            "source":"classLoader","methods":{"getNetworkCapabilities":1,"getActiveNetworkInfo":0}},
           {"name":"APPOPS_SERVICE","found":true,"searched":true,"class":"x.AppOps","source":"classLoader","methods":{"checkOperation":0}},
           {"name":"TELEPHONY_REGISTRY","found":false,"searched":true},
           {"name":"LOCATION_PROVIDER_MANAGER","found":false,"searched":false}
         ],
         "errors":["hookSystemWifiService: NullPointerException: boom"]}
    """.trimIndent()

    @Test
    fun `parses a report and classifies each component`() {
        val report = HookStatusReport.parse(sample)!!
        assertEquals("SYSTEM_SERVER", report.process)
        assertEquals("hyperos", report.vendorId)
        val states = report.components.associate { it.name to it.state }
        assertEquals(HookStatusReport.State.HOOKED, states["WIFI_SERVICE"])
        assertEquals(HookStatusReport.State.HOOKED, states["CONNECTIVITY_SERVICE"]) // 个别备用方法名 ×0 不影响
        assertEquals(HookStatusReport.State.NO_METHODS, states["APPOPS_SERVICE"])
        assertEquals(HookStatusReport.State.NOT_FOUND, states["TELEPHONY_REGISTRY"])
        assertEquals(HookStatusReport.State.PENDING, states["LOCATION_PROVIDER_MANAGER"])
        assertEquals(2, report.hookedCount)
        assertEquals(1, report.errors.size)
        assertTrue(report.isStale(bootTimeMs = 2000))
    }

    @Test
    fun `root command failures are not reports`() {
        assertNull(HookStatusReport.parse("ERROR"))
        assertNull(HookStatusReport.parse("SUCCESS"))
        assertNull(HookStatusReport.parse(""))
    }
}
