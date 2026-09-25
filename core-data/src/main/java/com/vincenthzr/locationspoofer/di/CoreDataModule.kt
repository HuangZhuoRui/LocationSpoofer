package com.vincenthzr.locationspoofer.di

import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.repository.LocationRepository
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.utils.ConfigManager
import com.vincenthzr.locationspoofer.utils.EnvironmentScanner
import com.vincenthzr.locationspoofer.utils.LSPosedManager
import com.vincenthzr.locationspoofer.utils.OpenCellIdClient
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.utils.SettingsManager
import com.vincenthzr.locationspoofer.utils.WigleClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreDataModule = module {
    single { RootManager() }
    single { ConfigManager(androidContext(), get()) }
    single { LSPosedManager() }
    single { SettingsManager(androidContext()) }
    single { EnvironmentScanner(androidContext()) }

    single { WigleClient() }
    single { OpenCellIdClient() }
    single { WifiRepository(get()) }

    single { LocationRepository(get(), get(), get(), get(), get(), get()) }
    single { SettingsRepository(get()) }

    single { AppDatabase.getDatabase(androidContext()) }
    single { get<AppDatabase>().environmentDao() }
    single { get<AppDatabase>().savedRouteDao() }
}
