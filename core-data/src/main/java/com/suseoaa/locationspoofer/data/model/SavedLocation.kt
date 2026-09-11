package com.suseoaa.locationspoofer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
    val wifiJson: String = "[]",
    val cellJson: String = "[]",
    val bluetoothJson: String = "[]",
    /** 收藏来自哪条环境采集记录（EnvironmentDao 里的 LocationRecord.id），用它做关联
     *  才不受坐标编辑影响；手动收藏（不来自采集记录，比如"定位"页直接保存当前坐标）
     *  没有来源可关联，留空退回按坐标匹配。老版本写入的收藏没有这个字段，读出来也是 null。 */
    val sourceLocationId: Long? = null
)
