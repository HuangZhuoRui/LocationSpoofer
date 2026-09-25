package com.suseoaa.locationspoofer

import android.app.Application
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.google.android.libraries.places.api.Places
import com.suseoaa.locationspoofer.di.appModules
import com.suseoaa.locationspoofer.utils.XposedModuleStatus
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class LocationApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()

        Thread {
            try {
                XposedServiceHelper.registerListener(this@LocationApp)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }.start()

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        val customApiKey = prefs.getString("amap_api_key", "")
        if (!customApiKey.isNullOrEmpty()) {
            MapsInitializer.setApiKey(customApiKey)
            AMapLocationClient.setApiKey(customApiKey)
            ServiceSettings.getInstance().setApiKey(customApiKey)
        }

        val baiduApiKey = prefs.getString("baidu_api_key", "")
        if (!baiduApiKey.isNullOrEmpty()) {
            com.baidu.mapapi.SDKInitializer.setApiKey(baiduApiKey)
        }

        try {
            com.baidu.mapapi.SDKInitializer.setAgreePrivacy(this, true)
            com.baidu.mapapi.SDKInitializer.setCoordType(com.baidu.mapapi.CoordType.GCJ02)
            com.baidu.mapapi.SDKInitializer.initialize(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val googleApiKey = prefs.getString("google_api_key", "")
        if (!Places.isInitialized() && !googleApiKey.isNullOrBlank()) {
            try {
                Places.initialize(this, googleApiKey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        startKoin {
            androidLogger()
            androidContext(this@LocationApp)
            modules(appModules)
        }

        Thread {
            try {
                val configManager: com.suseoaa.locationspoofer.utils.ConfigManager by org.koin.java.KoinJavaComponent.inject(
                    com.suseoaa.locationspoofer.utils.ConfigManager::class.java
                )
                configManager.syncDomainConfigs()
            } catch (_: Throwable) {}
        }.start()
    }

    override fun onServiceBind(service: XposedService) {
        XposedModuleStatus.update(service)
    }

    override fun onServiceDied(service: XposedService) {
        XposedModuleStatus.clear()
    }
}
