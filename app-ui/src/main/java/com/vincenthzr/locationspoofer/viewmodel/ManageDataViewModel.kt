package com.vincenthzr.locationspoofer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.data.db.EnvironmentDao
import com.vincenthzr.locationspoofer.ui.screen.managedata.ManageDataUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManageDataViewModel(private val environmentDao: EnvironmentDao) : ViewModel() {
    private val _uiState = MutableStateFlow(ManageDataUiState())
    val uiState: StateFlow<ManageDataUiState> = _uiState.asStateFlow()
    private var observationJob: Job? = null

    init {
        loadManageData()
    }

    fun loadManageData() {
        observationJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        observationJob = viewModelScope.launch {
            combine(environmentDao.observeAllCompleteLocations(), environmentDao.observeCollectionRoutes()) { list, routes ->
                list.filter { it.location.collectionRouteId == null } to routes
            }.distinctUntilChanged().collect { (list, routes) ->
                _uiState.update { it.copy(dataList = list, collectionRoutes = routes, isLoading = false) }
            }
        }
    }

    fun deleteManageData(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocations(ids)
        }
    }

    fun deleteManageDataSingle(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocation(id)
        }
    }

    fun updateManageDataMetadata(
        id: Long,
        lat: Double,
        lng: Double,
        placeName: String,
        remark: String,
        selectedWifiBssid: String?,
        selectedBluetoothAddress: String?,
        selectedCellKey: String?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.updateMetadata(
                id,
                lat,
                lng,
                placeName,
                remark,
                selectedWifiBssid,
                selectedBluetoothAddress,
                selectedCellKey
            )
        }
    }

    fun saveOrUpdateLocationWifi(
        locationId: Long,
        bssid: String,
        ssid: String,
        frequency: Int,
        level: Int,
        capabilities: String,
        vendor: String = "",
        isConnected: Boolean = false,
        isDesignatedSimulation: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val device = com.vincenthzr.locationspoofer.data.db.WifiDevice(
                bssid = bssid.uppercase().trim(),
                ssid = ssid.trim(),
                frequency = frequency,
                capabilities = capabilities.ifBlank { "[WPA2-PSK-CCMP][RSN]" },
                vendor = vendor
            )
            environmentDao.insertWifiDevice(device)
            environmentDao.insertLocationWifi(
                com.vincenthzr.locationspoofer.data.db.LocationWifi(
                    locationId = locationId,
                    bssid = bssid.uppercase().trim(),
                    level = level
                )
            )

            if (isConnected) {
                val conn = com.vincenthzr.locationspoofer.data.db.LocationConnectedWifi(
                    locationId = locationId,
                    bssid = bssid.uppercase().trim(),
                    ssid = ssid.trim(),
                    vendor = vendor,
                    macAddress = bssid.uppercase().trim(),
                    frequency = frequency,
                    linkSpeed = 65,
                    level = level,
                    capabilities = capabilities.ifBlank { "[WPA2-PSK-CCMP][RSN]" },
                    networkId = 1,
                    wifiStandard = 6
                )
                environmentDao.insertConnectedWifi(conn)
            }

            if (isDesignatedSimulation) {
                environmentDao.updateSelectedWifi(locationId, bssid.uppercase().trim())
            }
        }
    }

    fun deleteLocationWifi(locationId: Long, bssid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocationWifi(locationId, bssid)
            val target = _uiState.value.dataList.find { it.location.id == locationId }
            if (target?.connectedWifi?.bssid.equals(bssid, ignoreCase = true)) {
                environmentDao.deleteConnectedWifi(locationId)
            }
            if (target?.location?.selectedWifiBssid.equals(bssid, ignoreCase = true)) {
                environmentDao.updateSelectedWifi(locationId, null)
            }
        }
    }

    fun updateSelectedWifiBssid(locationId: Long, selectedBssid: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.updateSelectedWifi(locationId, selectedBssid)
        }
    }

    fun saveOrUpdateLocationCell(
        locationId: Long,
        cellKey: String,
        type: String,
        mcc: Int,
        mnc: Int,
        tac: Int = 0,
        ci: Int = 0,
        pci: Int = 0,
        lac: Int = 0,
        cid: Int = 0,
        psc: Int = 0,
        nci: Long = 0,
        networkId: Int = 0,
        systemId: Int = 0,
        basestationId: Int = 0,
        dbm: Int = -85,
        isRegistered: Boolean = false,
        isDesignated: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val device = com.vincenthzr.locationspoofer.data.db.CellDevice(
                cellKey = cellKey.trim(),
                type = type.uppercase().trim(),
                mcc = mcc,
                mnc = mnc,
                tac = tac,
                ci = ci,
                pci = pci,
                lac = lac,
                cid = cid,
                psc = psc,
                nci = nci,
                networkId = networkId,
                systemId = systemId,
                basestationId = basestationId
            )
            environmentDao.insertCellDevice(device)
            environmentDao.insertLocationCell(
                com.vincenthzr.locationspoofer.data.db.LocationCell(
                    locationId = locationId,
                    cellKey = cellKey.trim(),
                    dbm = dbm,
                    isRegistered = isRegistered || isDesignated
                )
            )
            if (isDesignated) {
                environmentDao.updateSelectedCell(locationId, cellKey.trim())
            }
        }
    }

    fun deleteLocationCell(locationId: Long, cellKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocationCell(locationId, cellKey)
            val target = _uiState.value.dataList.find { it.location.id == locationId }
            if (target?.location?.selectedCellKey.equals(cellKey, ignoreCase = true)) {
                environmentDao.updateSelectedCell(locationId, null)
            }
        }
    }

    fun updateSelectedCellKey(locationId: Long, selectedCellKey: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.updateSelectedCell(locationId, selectedCellKey)
        }
    }

    fun saveOrUpdateLocationBluetooth(
        locationId: Long,
        address: String,
        name: String,
        scanRecordHex: String = "",
        rssi: Int = -60,
        isDesignated: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val device = com.vincenthzr.locationspoofer.data.db.BluetoothDevice(
                address = address.uppercase().trim(),
                name = name.trim(),
                scanRecordHex = scanRecordHex.trim()
            )
            environmentDao.insertBluetoothDevice(device)
            environmentDao.insertLocationBluetooth(
                com.vincenthzr.locationspoofer.data.db.LocationBluetooth(
                    locationId = locationId,
                    address = address.uppercase().trim(),
                    rssi = rssi
                )
            )
            if (isDesignated) {
                environmentDao.updateSelectedBluetooth(locationId, address.uppercase().trim())
            }
        }
    }

    fun deleteLocationBluetooth(locationId: Long, address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocationBluetooth(locationId, address)
            val target = _uiState.value.dataList.find { it.location.id == locationId }
            if (target?.location?.selectedBluetoothAddress.equals(address, ignoreCase = true)) {
                environmentDao.updateSelectedBluetooth(locationId, null)
            }
        }
    }

    fun updateSelectedBluetoothAddress(locationId: Long, selectedBluetoothAddress: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.updateSelectedBluetooth(locationId, selectedBluetoothAddress)
        }
    }

    fun clearAllManageData() {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.clearCollectionRoutes()
            environmentDao.clearAll()
        }
    }
}
