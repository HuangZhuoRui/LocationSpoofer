package com.vincenthzr.locationspoofer.xposed.utils

/** Only a host loader and bootstrap-owned values cross module generations. */
internal data class HotReloadHost(val packageName: String, val classLoader: ClassLoader) {
    fun save(): Array<Any> = arrayOf(packageName, classLoader)

    companion object {
        fun restore(value: Any?): HotReloadHost? {
            val values = value as? Array<*> ?: return null
            val name = values.getOrNull(0) as? String ?: return null
            val loader = values.getOrNull(1) as? ClassLoader ?: return null
            return if (name.isBlank()) null else HotReloadHost(name, loader)
        }
    }
}

/** Resolve first, so an unavailable host never silently removes the working hooks. */
internal fun reinstallAfterHotReload(
    state: Any?,
    resolveHost: () -> HotReloadHost?,
    removeOldHooks: () -> Unit,
    install: (HotReloadHost) -> Unit
) {
    val host = HotReloadHost.restore(state) ?: resolveHost()
        ?: error("Cannot resolve the host for module hot reload")
    removeOldHooks()
    install(host)
}
