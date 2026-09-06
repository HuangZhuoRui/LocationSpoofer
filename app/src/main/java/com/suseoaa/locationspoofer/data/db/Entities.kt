@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.suseoaa.locationspoofer.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Embedded
import androidx.room.Relation
import com.suseoaa.locationspoofer.data.model.SavedLocation
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "location_records",
    indices = [
        Index(value = ["lat", "lng"]),
        Index(value = ["timestamp"])
    ]
)
data class LocationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val placeName: String = "",
    val remark: String = "",
    val selectedWifiBssid: String? = null,
    val selectedBluetoothAddress: String? = null,
    val selectedCellKey: String? = null
)

@Serializable
@Entity(tableName = "wifi_devices")
data class WifiDevice(
    @PrimaryKey val bssid: String,
    val ssid: String = "",
    val frequency: Int = 2412,
    val capabilities: String = "",
    val vendor: String = ""
)

@Serializable
@Entity(
    tableName = "location_connected_wifi",
    foreignKeys = [
        ForeignKey(
            entity = LocationRecord::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LocationConnectedWifi(
    @PrimaryKey val locationId: Long = 0,
    val bssid: String,
    val ssid: String = "",
    val vendor: String = "",
    val macAddress: String = "",
    val frequency: Int = 2412,
    val linkSpeed: Int = 0,
    val level: Int = -50,
    val capabilities: String = "",
    val networkId: Int = 0,
    val wifiStandard: Int = 0
)

@Entity(
    tableName = "location_wifi",
    primaryKeys = ["locationId", "bssid"],
    foreignKeys = [
        ForeignKey(
            entity = LocationRecord::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WifiDevice::class,
            parentColumns = ["bssid"],
            childColumns = ["bssid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bssid")]
)
@Serializable
data class LocationWifi(
    val locationId: Long = 0,
    val bssid: String,
    val level: Int = -60
)

@Serializable
@Entity(tableName = "bluetooth_devices")
data class BluetoothDevice(
    @PrimaryKey val address: String,
    val name: String = "",
    val scanRecordHex: String = ""
)

@Entity(
    tableName = "location_bluetooth",
    primaryKeys = ["locationId", "address"],
    foreignKeys = [
        ForeignKey(
            entity = LocationRecord::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BluetoothDevice::class,
            parentColumns = ["address"],
            childColumns = ["address"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("address")]
)
@Serializable
data class LocationBluetooth(
    val locationId: Long = 0,
    val address: String,
    val rssi: Int = -70
)

@Serializable
@Entity(tableName = "cell_devices")
data class CellDevice(
    @PrimaryKey val cellKey: String,
    val type: String = "LTE",
    val mcc: Int = 460,
    val mnc: Int = 0,
    // LTE
    val tac: Int = 0,
    val ci: Int = 0,
    val pci: Int = 0,
    // GSM/WCDMA
    val lac: Int = 0,
    val cid: Int = 0,
    // WCDMA
    val psc: Int = 0,
    // NR
    val nci: Long = 0,
    // CDMA
    val networkId: Int = 0,
    val systemId: Int = 0,
    val basestationId: Int = 0
)

@Entity(
    tableName = "location_cells",
    primaryKeys = ["locationId", "cellKey"],
    foreignKeys = [
        ForeignKey(
            entity = LocationRecord::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CellDevice::class,
            parentColumns = ["cellKey"],
            childColumns = ["cellKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cellKey")]
)
@Serializable
data class LocationCell(
    val locationId: Long = 0,
    val cellKey: String,
    val dbm: Int = -80,
    val isRegistered: Boolean = true
)

@Serializable
data class LocationWithWifi(
    @Embedded val locationWifi: LocationWifi,
    @Relation(parentColumn = "bssid", entityColumn = "bssid")
    val device: WifiDevice
)

@Serializable
data class LocationWithBluetooth(
    @Embedded val locationBluetooth: LocationBluetooth,
    @Relation(parentColumn = "address", entityColumn = "address")
    val device: BluetoothDevice
)

@Serializable
data class LocationWithCell(
    @Embedded val locationCell: LocationCell,
    @Relation(parentColumn = "cellKey", entityColumn = "cellKey")
    val device: CellDevice
)

@Serializable
data class CompleteLocation(
    @Embedded val location: LocationRecord,
    @Relation(parentColumn = "id", entityColumn = "locationId")
    val connectedWifi: LocationConnectedWifi? = null,
    @Relation(entity = LocationWifi::class, parentColumn = "id", entityColumn = "locationId")
    val wifis: List<LocationWithWifi> = emptyList(),
    @Relation(entity = LocationBluetooth::class, parentColumn = "id", entityColumn = "locationId")
    val bluetooths: List<LocationWithBluetooth> = emptyList(),
    @Relation(entity = LocationCell::class, parentColumn = "id", entityColumn = "locationId")
    val cells: List<LocationWithCell> = emptyList()
)

@Serializable
@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val pointsJson: String = "[]",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 可随数据包一起导出的软件设置（模拟参数与地图偏好）。
 * 只放"换台设备也应该保持一致"的项；
 * 运行时状态（isSpoofingActive/lastSpoofedLat/Lng）、本机偏好（语言、深色模式、忽略的版本）
 * 一律不导出，跟着数据包走只会互相干扰。
 */
@Serializable
data class ExportedSettings(
    val mockWifi: Boolean = true,
    val mockCell: Boolean = true,
    val mockBluetooth: Boolean = true,
    val enableJitter: Boolean = true,
    val altitude: String = "",
    val satelliteCount: String = "",
    val mapType: String = "",
    val mapEngine: String = ""
)

/**
 * API 密钥属于个人凭据，与普通设置分开成一类，导出时默认不勾选：
 * 分享给他人的文件里带上密钥，等于把自己实名认证的开发者配额交出去。
 */
@Serializable
data class ExportedApiKeys(
    val amapApiKey: String = "",
    val baiduApiKey: String = "",
    val googleApiKey: String = "",
    val wigleApiToken: String = "",
    val opencellidApiToken: String = ""
)

// 综合导出与导入数据包（支持全量多版本互通）
// version 3 起新增 settings / apiKeys 两个可空字段，null 表示本次导出没有包含该分类；
// 旧的 version 2 文件缺这两个字段时会落到默认值 null，无需特殊兼容处理。
@Serializable
data class LocationSpooferDataPackage(
    val version: Int = 3,
    val exportTimestamp: Long = System.currentTimeMillis(),
    val appVersion: String = "2.0.0",
    val locations: List<CompleteLocation> = emptyList(),
    val savedLocations: List<SavedLocation> = emptyList(),
    val savedRoutes: List<SavedRouteEntity> = emptyList(),
    val appCoordinateSystems: Map<String, String> = emptyMap(),
    val settings: ExportedSettings? = null,
    val apiKeys: ExportedApiKeys? = null
)
