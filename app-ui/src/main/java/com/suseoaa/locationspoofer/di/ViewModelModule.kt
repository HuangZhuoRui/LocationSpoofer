package com.suseoaa.locationspoofer.di

import com.suseoaa.locationspoofer.viewmodel.ManageDataViewModel
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.viewmodel.UpdateViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { UpdateViewModel(androidContext()) }
    viewModel { ManageDataViewModel(get()) }
}
