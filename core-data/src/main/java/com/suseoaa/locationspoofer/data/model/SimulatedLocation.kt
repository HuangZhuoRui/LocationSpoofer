package com.suseoaa.locationspoofer.data.model

data class SimulatedLocation(
    val lat: Double,
    val lng: Double,
    val speed: Float,
    val bearing: Float,
    val accuracy: Float,
    val altitude: Double
)
