package com.vincenthzr.locationspoofer.di

import com.vincenthzr.locationspoofer.data.repository.SpoofingServiceController
import com.vincenthzr.locationspoofer.service.SpoofingServiceControllerImpl
import org.koin.dsl.module

val serviceModule = module {
    single<SpoofingServiceController> { SpoofingServiceControllerImpl() }
}
