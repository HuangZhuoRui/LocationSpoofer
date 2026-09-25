package com.vincenthzr.locationspoofer.utils

import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LSPosed 模块连接状态的全局持有者，从 Application 子类中拆出来，
 * 避免 utils 包反向依赖 :app 模块。由 LocationApp 在收到
 * XposedServiceHelper 回调时更新，供 LSPosedManager / ViewModel 读取。
 */
object XposedModuleStatus {
    private val _isModuleActive = MutableStateFlow(false)
    val isModuleActive: StateFlow<Boolean> = _isModuleActive

    var mService: XposedService? = null
        private set

    fun update(service: XposedService) {
        mService = service
        _isModuleActive.value = true
    }

    fun clear() {
        mService = null
        _isModuleActive.value = false
    }
}
