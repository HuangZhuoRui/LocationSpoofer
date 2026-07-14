package com.suseoaa.locationspoofer.data.model

data class SavedLocation(
    val name: String, 
    val lat: Double, 
    val lng: Double,
    val wifiJson: String = "[]",
    val cellJson: String = "[]",
    val bluetoothJson: String = "[]"
)
