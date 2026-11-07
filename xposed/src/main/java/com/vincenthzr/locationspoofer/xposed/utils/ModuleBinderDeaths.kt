package com.vincenthzr.locationspoofer.xposed.utils

import android.os.IBinder

/** Unlink module-owned callbacks before retiring their classloader. */
internal object ModuleBinderDeaths {
    private val recipients = LinkedHashMap<IBinder.DeathRecipient, IBinder>()
    private var closed = false

    @Synchronized
    fun watch(binder: IBinder, onDeath: () -> Unit) {
        if (closed) return
        val recipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                val invoke = synchronized(this@ModuleBinderDeaths) {
                    recipients.remove(this) != null && !closed
                }
                if (invoke) onDeath()
            }
        }
        recipients[recipient] = binder
        try {
            binder.linkToDeath(recipient, 0)
        } catch (error: Throwable) {
            recipients.remove(recipient)
            throw error
        }
    }

    @Synchronized
    fun close() {
        closed = true
        recipients.forEach { (recipient, binder) -> runCatching { binder.unlinkToDeath(recipient, 0) } }
        recipients.clear()
    }
}
