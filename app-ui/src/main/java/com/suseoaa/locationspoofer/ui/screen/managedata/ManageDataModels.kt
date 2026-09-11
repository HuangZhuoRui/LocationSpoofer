package com.suseoaa.locationspoofer.ui.screen.managedata

data class EditableWifiItem(
    val bssid: String,
    val ssid: String,
    val frequency: Int,
    val level: Int,
    val capabilities: String,
    val vendor: String,
    val isConnected: Boolean,
    val isDesignated: Boolean
)

data class EditableCellItem(
    val cellKey: String,
    val type: String,
    val mcc: Int,
    val mnc: Int,
    val tac: Int = 0,
    val ci: Int = 0,
    val pci: Int = 0,
    val lac: Int = 0,
    val cid: Int = 0,
    val psc: Int = 0,
    val nci: Long = 0,
    val networkId: Int = 0,
    val systemId: Int = 0,
    val basestationId: Int = 0,
    val dbm: Int = -85,
    val isRegistered: Boolean = false,
    val isDesignated: Boolean = false
)

data class EditableBluetoothItem(
    val address: String,
    val name: String,
    val scanRecordHex: String = "",
    val rssi: Int = -60,
    val isDesignated: Boolean = false
)

enum class EditDataTab {
    WIFI, CELL, BLUETOOTH
}
