package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import com.vincenthzr.locationspoofer.data.db.*
import com.vincenthzr.locationspoofer.ui.screen.managedata.ManageDataUiState
import com.vincenthzr.locationspoofer.viewmodel.ManageDataViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real Room invalidation and ViewModel regression, isolated from the user's database. */
internal fun Instrumentation.verifyEnvironmentRefresh(): Bundle {
    val output = Bundle()
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    val store = ViewModelStore()
    try {
        lateinit var viewModel: ManageDataViewModel
        runOnMainSync {
            viewModel = ManageDataViewModel(database.environmentDao())
            store.put("environment-refresh", viewModel)
        }
        val checks = mutableListOf<String>()
        runBlocking {
            suspend fun awaitState(label: String, predicate: (ManageDataUiState) -> Boolean) {
                withTimeout(10_000) { viewModel.uiState.first(predicate) }
                checks += label
            }
            val dao = database.environmentDao()
            awaitState("initial-load") { !it.isLoading && it.dataList.isEmpty() }
            val id = dao.insertLocation(LocationRecord(lat = 30.0, lng = 104.0))
            awaitState("new-point") { it.dataList.singleOrNull()?.location?.id == id }

            val wifi = WifiDevice("02:00:00:00:00:01", ssid = "refresh-test")
            dao.insertWifiDevice(wifi)
            dao.insertLocationWifi(LocationWifi(id, wifi.bssid, -60))
            awaitState("append-wifi-same-point") { it.dataList.singleOrNull()?.wifis?.size == 1 }
            dao.insertConnectedWifi(LocationConnectedWifi(id, wifi.bssid, ssid = wifi.ssid))
            awaitState("connected-wifi") { it.dataList.singleOrNull()?.connectedWifi?.bssid == wifi.bssid }

            val cell = CellDevice("refresh-cell", tac = 1, ci = 1)
            dao.insertCellDevice(cell)
            dao.insertLocationCell(LocationCell(id, cell.cellKey))
            awaitState("append-cell-same-point") { it.dataList.singleOrNull()?.cells?.size == 1 }
            val bluetooth = BluetoothDevice("02:00:00:00:00:02", name = "refresh-test")
            dao.insertBluetoothDevice(bluetooth)
            dao.insertLocationBluetooth(LocationBluetooth(id, bluetooth.address))
            awaitState("append-bluetooth-same-point") { it.dataList.singleOrNull()?.bluetooths?.size == 1 }

            database.withTransaction {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE wifi_devices SET ssid = ? WHERE bssid = ?",
                    arrayOf("updated-device", wifi.bssid)
                )
            }
            awaitState("nested-device-update") {
                it.dataList.singleOrNull()?.wifis?.singleOrNull()?.device?.ssid == "updated-device"
            }
            dao.insertLocationWifi(LocationWifi(id, wifi.bssid, -42))
            awaitState("signal-update-same-count") {
                it.dataList.singleOrNull()?.wifis?.singleOrNull()?.locationWifi?.level == -42
            }
            dao.updateMetadata(id, 31.0, 105.0, "updated-place", "updated-remark", null, null, null)
            awaitState("metadata-update") {
                it.dataList.singleOrNull()?.location?.let { record ->
                    record.lat == 31.0 && record.remark == "updated-remark"
                } == true
            }
            dao.deleteLocationBluetooth(id, bluetooth.address)
            awaitState("remove-related-data") { it.dataList.singleOrNull()?.bluetooths?.isEmpty() == true }
            dao.deleteLocation(id)
            awaitState("delete-point") { it.dataList.isEmpty() }
        }
        output.putString("checks", checks.joinToString())
        output.putString("result", "PASS (${checks.size} checks, same ViewModel instance)")
        return output
    } catch (error: Throwable) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
        return output
    } finally {
        runOnMainSync { store.clear() }
        database.close()
    }
}
