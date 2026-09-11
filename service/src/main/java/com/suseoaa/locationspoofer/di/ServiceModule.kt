package com.suseoaa.locationspoofer.di

import com.suseoaa.locationspoofer.data.repository.SpoofingServiceController
import com.suseoaa.locationspoofer.service.SpoofingServiceControllerImpl
import org.koin.dsl.module

val serviceModule = module {
    single<SpoofingServiceController> { SpoofingServiceControllerImpl() }
}
