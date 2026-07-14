package com.suseoaa.locationspoofer.data.repository

import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SavedRoute
import com.suseoaa.locationspoofer.utils.SettingsManager

class SettingsRepository(private val settingsManager: SettingsManager) {

    fun getSavedLocations(): List<SavedLocation> = settingsManager.getSavedLocations()

    fun addSavedLocation(location: SavedLocation) = settingsManager.addSavedLocation(location)

    fun removeSavedLocation(location: SavedLocation) = settingsManager.removeSavedLocation(location)

    fun getSavedRoutes(): List<SavedRoute> = settingsManager.getSavedRoutes()

    fun addSavedRoute(route: SavedRoute) = settingsManager.addSavedRoute(route)

    fun removeSavedRoute(route: SavedRoute) = settingsManager.removeSavedRoute(route)

    fun isLanguageSet(): Boolean = settingsManager.isLanguageSet

    fun setLanguageSet(value: Boolean) {
        settingsManager.isLanguageSet = value
    }

    fun getLanguage(): String = settingsManager.language

    fun setLanguage(value: String) {
        settingsManager.language = value
    }

    fun getAmapApiKey(): String = settingsManager.amapApiKey

    fun setAmapApiKey(value: String) {
        settingsManager.amapApiKey = value
    }

    fun getBaiduApiKey(): String = settingsManager.baiduApiKey

    fun setBaiduApiKey(value: String) {
        settingsManager.baiduApiKey = value
    }

    fun getGoogleApiKey(): String = settingsManager.googleApiKey

    fun setGoogleApiKey(value: String) {
        settingsManager.googleApiKey = value
    }

    fun getWigleApiToken(): String = settingsManager.wigleApiToken

    fun setWigleApiToken(value: String) {
        settingsManager.wigleApiToken = value
    }

    fun getOpencellidApiToken(): String = settingsManager.opencellidApiToken

    fun setOpencellidApiToken(value: String) {
        settingsManager.opencellidApiToken = value
    }

    fun getMapType(): String = settingsManager.mapType

    fun setMapType(value: String) {
        settingsManager.mapType = value
    }

    fun getMapEngine(): String = settingsManager.mapEngine

    fun setMapEngine(value: String) {
        settingsManager.mapEngine = value
    }

    fun getIgnoredVersion(): String = settingsManager.ignoredVersion

    fun setIgnoredVersion(value: String) {
        settingsManager.ignoredVersion = value
    }

    fun getAppCoordinateSystems(): Map<String, String> = settingsManager.getAppCoordinateSystems()

    fun setAppCoordinateSystems(map: Map<String, String>) = settingsManager.setAppCoordinateSystems(map)

    var isSpoofingActive: Boolean
        get() = settingsManager.isSpoofingActive
        set(value) { settingsManager.isSpoofingActive = value }

    var isRouteSpoofingActive: Boolean
        get() = settingsManager.isRouteSpoofingActive
        set(value) { settingsManager.isRouteSpoofingActive = value }

    var lastSpoofedLat: String
        get() = settingsManager.lastSpoofedLat
        set(value) { settingsManager.lastSpoofedLat = value }

    var lastSpoofedLng: String
        get() = settingsManager.lastSpoofedLng
        set(value) { settingsManager.lastSpoofedLng = value }

    var mockWifi: Boolean
        get() = settingsManager.mockWifi
        set(value) { settingsManager.mockWifi = value }

    var mockCell: Boolean
        get() = settingsManager.mockCell
        set(value) { settingsManager.mockCell = value }

    var mockBluetooth: Boolean
        get() = settingsManager.mockBluetooth
        set(value) { settingsManager.mockBluetooth = value }

    var enableJitter: Boolean
        get() = settingsManager.enableJitter
        set(value) { settingsManager.enableJitter = value }

    var altitude: String
        get() = settingsManager.altitude
        set(value) { settingsManager.altitude = value }

    var satelliteCount: String
        get() = settingsManager.satelliteCount
        set(value) { settingsManager.satelliteCount = value }
}
