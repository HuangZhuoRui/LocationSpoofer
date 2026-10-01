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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath

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
            verifyCollectionMerge(dao, checks)
            verifyCollectedRoutes(dao, checks) { label, predicate -> awaitState(label, predicate) }
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

private suspend fun verifyCollectedRoutes(
    dao: EnvironmentDao,
    checks: MutableList<String>,
    awaitState: suspend (String, (ManageDataUiState) -> Boolean) -> Unit
) {
    val point = LatLng(44.0, 121.0)
    val standaloneId = dao.findOrCreateCollectionLocation(point.lat, point.lng)
    val geometry = "[{\"lat\":44.0,\"lng\":121.0},{\"lat\":44.01,\"lng\":121.0}]"
    val routeId = dao.insertCollectionRoute(CollectionRouteRecord(name = "route-test", pointsJson = geometry))
    awaitState("route-created-live") { it.collectionRoutes.any { r -> r.route.id == routeId } }
    val recordCount = dao.getRecordCount()
    val sampleId = dao.findOrCreateCollectionLocation(point.lat, point.lng, routeId)
    check(dao.getRecordCount() == recordCount)
    checks += "route-counted-once-regardless-of-samples"
    check(sampleId != standaloneId)
    awaitState("route-sample-hidden-from-standalone-list") {
        it.dataList.none { p -> p.location.id == sampleId } &&
            it.collectionRoutes.firstOrNull { r -> r.route.id == routeId }?.samples?.size == 1
    }
    val nearby = GeoMath.destination(point, 90.0, 19.0)
    check(dao.findOrCreateCollectionLocation(nearby.lat, nearby.lng, routeId) == sampleId)
    val route2 = dao.insertCollectionRoute(CollectionRouteRecord(name = "other-route", pointsJson = geometry))
    check(dao.findOrCreateCollectionLocation(point.lat, point.lng, route2) != sampleId)
    checks += "20m-merge-isolated-per-route"
    val wifi = WifiDevice("02:00:00:00:02:01", "route-wifi")
    dao.insertWifiDevice(wifi)
    dao.insertLocationWifi(LocationWifi(sampleId, wifi.bssid, -44))
    dao.insertConnectedWifi(LocationConnectedWifi(sampleId, wifi.bssid, wifi.ssid))
    dao.updateSelectedWifi(sampleId, wifi.bssid)
    awaitState("route-nested-data-updates-live") {
        it.collectionRoutes.firstOrNull { r -> r.route.id == routeId }?.samples?.singleOrNull()?.let { sample ->
            sample.wifis.size == 1 && sample.connectedWifi != null && sample.location.selectedWifiBssid == wifi.bssid
        } == true
    }
    dao.updateCollectionRouteInfo(routeId, "named-route", "route-note")
    awaitState("route-name-updates-live") {
        it.collectionRoutes.any { r -> r.route.id == routeId && r.route.name == "named-route" && r.route.remark == "route-note" }
    }
    val exported = checkNotNull(dao.getCollectionRoute(routeId))
    dao.insertCompleteCollectionRoute(exported)
    val imported = dao.getCollectionRoutes().single { it.route.id != routeId && it.route.name == "named-route" }
    check(imported.route.pointsJson == geometry)
    check(imported.samples.single().location.id != sampleId)
    check(imported.samples.single().location.collectionRouteId == imported.route.id)
    check(imported.samples.single().wifis.single().locationWifi.locationId == imported.samples.single().location.id)
    check(imported.samples.single().location.selectedWifiBssid == wifi.bssid)
    checks += "backup-import-remaps-route-and-all-sample-associations"
    dao.deleteCollectionRoute(routeId)
    awaitState("route-delete-cascades-only-own-samples") {
        it.collectionRoutes.none { r -> r.route.id == routeId } &&
            it.collectionRoutes.any { r -> r.route.id == imported.route.id && r.samples.size == 1 } &&
            it.dataList.any { p -> p.location.id == standaloneId }
    }
    check(dao.getCompleteLocationById(sampleId) == null)
    check(dao.getCollectionRoute(imported.route.id)?.samples?.single()?.wifis?.size == 1)
    checks += "route-deletion-preserves-shared-device-associations"
}

private suspend fun verifyCollectionMerge(dao: EnvironmentDao, checks: MutableList<String>) {
    val origin = LatLng(39.0, 116.0)
    val id = dao.findOrCreateCollectionLocation(origin.lat, origin.lng)
    dao.updateBasicInfo(id, "existing-name", "existing-note")
    val wifi = WifiDevice("02:00:00:00:01:01", "first-ssid")
    val bluetooth = BluetoothDevice("02:00:00:00:01:02", "first-name")
    val cell = CellDevice("merge-cell", tac = 1, ci = 1)
    dao.insertWifiDevice(wifi)
    dao.insertBluetoothDevice(bluetooth)
    dao.insertCellDevice(cell)
    dao.insertLocationWifi(LocationWifi(id, wifi.bssid))
    dao.insertLocationBluetooth(LocationBluetooth(id, bluetooth.address))
    dao.insertLocationCell(LocationCell(id, cell.cellKey))
    dao.insertConnectedWifi(LocationConnectedWifi(id, wifi.bssid))
    dao.updateSelectedWifi(id, wifi.bssid)
    dao.updateSelectedBluetooth(id, bluetooth.address)
    dao.updateSelectedCell(id, cell.cellKey)

    val inside = GeoMath.destination(origin, 45.0, 19.9)
    check(dao.findOrCreateCollectionLocation(inside.lat, inside.lng) == id)
    val merged = checkNotNull(dao.getCompleteLocationById(id))
    check(merged.location.lat == origin.lat && merged.location.lng == origin.lng)
    check(merged.location.placeName == "existing-name" && merged.location.remark == "existing-note")
    check(merged.wifis.size == 1 && merged.bluetooths.size == 1 && merged.cells.size == 1 && merged.connectedWifi != null)
    check(merged.location.selectedWifiBssid == wifi.bssid && merged.location.selectedCellKey == cell.cellKey)
    check(merged.location.selectedBluetoothAddress == bluetooth.address)
    checks += "merge-19.9m-preserves-anchor-metadata-devices"

    // 更新全局设备信息不能通过 REPLACE 级联删除任何点位的关联。
    val otherId = dao.insertLocation(LocationRecord(lat = 40.0, lng = 117.0))
    dao.insertLocationWifi(LocationWifi(otherId, wifi.bssid))
    dao.insertLocationBluetooth(LocationBluetooth(otherId, bluetooth.address))
    dao.insertLocationCell(LocationCell(otherId, cell.cellKey))
    dao.insertWifiDevice(wifi.copy(ssid = "second-ssid"))
    dao.insertBluetoothDevice(bluetooth.copy(name = "second-name"))
    dao.insertCellDevice(cell.copy(pci = 42))
    for (pointId in listOf(id, otherId)) {
        val updated = checkNotNull(dao.getCompleteLocationById(pointId))
        check(updated.wifis.single().device.ssid == "second-ssid")
        check(updated.bluetooths.single().device.name == "second-name")
        check(updated.cells.single().device.pci == 42)
    }
    checks += "device-updates-preserve-all-point-associations"

    val outside = GeoMath.destination(origin, 0.0, 20.1)
    val outsideId = dao.findOrCreateCollectionLocation(outside.lat, outside.lng)
    check(outsideId != id)
    checks += "20.1m-creates-separate-point"
    val nearest = GeoMath.destination(origin, 0.0, 15.0)
    check(dao.findOrCreateCollectionLocation(nearest.lat, nearest.lng) == outsideId)
    checks += "nearest-point-chosen"

    val diagonalOrigin = LatLng(41.0, 118.0)
    val diagonalId = dao.findOrCreateCollectionLocation(diagonalOrigin.lat, diagonalOrigin.lng)
    val diagonal = GeoMath.destination(diagonalOrigin, 45.0, 26.0)
    check(dao.findOrCreateCollectionLocation(diagonal.lat, diagonal.lng) != diagonalId)
    checks += "bounding-box-corner-does-not-merge"

    val chainOrigin = LatLng(42.0, 119.0)
    val chainId = dao.findOrCreateCollectionLocation(chainOrigin.lat, chainOrigin.lng)
    val step1 = GeoMath.destination(chainOrigin, 0.0, 19.0)
    val step2 = GeoMath.destination(chainOrigin, 0.0, 38.0)
    check(dao.findOrCreateCollectionLocation(step1.lat, step1.lng) == chainId)
    check(dao.findOrCreateCollectionLocation(step2.lat, step2.lng) != chainId)
    checks += "moving-scans-do-not-chain-merge"

    val highLatitude = LatLng(70.0, 100.0)
    val highId = dao.findOrCreateCollectionLocation(highLatitude.lat, highLatitude.lng)
    val highNearby = GeoMath.destination(highLatitude, 90.0, 19.0)
    check(dao.findOrCreateCollectionLocation(highNearby.lat, highNearby.lng) == highId)
    checks += "longitude-distance-at-high-latitude"
    val dateLine = LatLng(10.0, 179.99995)
    val dateLineId = dao.findOrCreateCollectionLocation(dateLine.lat, dateLine.lng)
    val acrossDateLine = GeoMath.destination(dateLine, 90.0, 19.0)
    val wrappedLng = (acrossDateLine.lng + 540.0) % 360.0 - 180.0
    check(dao.findOrCreateCollectionLocation(acrossDateLine.lat, wrappedLng) == dateLineId)
    checks += "date-line-nearby-points-merge"

    val simultaneous = coroutineScope {
        (1..8).map { async { dao.findOrCreateCollectionLocation(43.0, 120.0) } }.awaitAll()
    }
    check(simultaneous.distinct().size == 1)
    checks += "concurrent-captures-share-one-point"

    dao.updateBasicInfo(id, "updated-name", "updated-note")
    val info = checkNotNull(dao.getCompleteLocationById(id))
    check(info.location.placeName == "updated-name" && info.location.remark == "updated-note")
    check(info.location.lat == origin.lat && info.location.selectedWifiBssid == wifi.bssid)
    check(info.wifis.isNotEmpty() && info.cells.isNotEmpty() && info.bluetooths.isNotEmpty())
    checks += "basic-info-save-preserves-data-and-selections"
}
