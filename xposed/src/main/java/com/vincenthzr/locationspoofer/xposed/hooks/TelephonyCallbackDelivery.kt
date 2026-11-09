package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.Bundle
import android.os.IInterface
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.network.buildFakeCellInfoList
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Transform only the recipient's final callback, after TelephonyRegistry's permission checks. */
internal class TelephonyCallbackDelivery(
    private val module: LocationHooker,
    private val loader: ClassLoader
) : AutoCloseable {
    private val registries = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val callbackClasses = mutableSetOf<Class<*>>()
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    @Volatile private var live = true

    fun install(registry: Class<*>) {
        try {
            registry.getDeclaredField("mRecords") // Probe before changing any delivery.
            for (name in registry.declaredMethods.filter { it.name.startsWith("listen") }.map { it.name }.distinct()) {
                handles += XposedHelpers.hookAllMethods(registry, name) { chain, _ ->
                    if (live) {
                        chain.thisObject?.let { registries[it] = true }
                        chain.args.filterNotNull().firstOrNull {
                            LocationHooker.hasTypeByName(it.javaClass, "com.android.internal.telephony.IPhoneStateListener")
                        }?.let { callback ->
                            runCatching { hookCallback(callback.javaClass) }.onFailure {
                                HookStatus.error("TelephonyRegistry/callback", it)
                            }
                        }
                    }
                    chain.proceed(chain.args.toTypedArray())
                }
            }
            // This includes notifyNow, subscriber updates, and cached user/subscription updates.
            // Keep callback calls from being inlined past the recipient-level hook.
            registry.declaredMethods.filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers) }
                .forEach { check(module.deoptimize(it)) { "Cannot deoptimize ${it.name}" } }
        } catch (error: Throwable) {
            close()
            HookStatus.error("TelephonyRegistry/recipient-delivery", error)
        }
    }

    @Synchronized private fun hookCallback(type: Class<*>) {
        if (!live || !callbackClasses.add(type)) return
        for (name in listOf("onCellInfoChanged", "onCellLocationChanged")) {
            handles += XposedHelpers.hookAllMethods(type, name) { chain, method ->
                val args = chain.args.toTypedArray()
                if (live) {
                    try {
                        val recipient = recipient(chain.thisObject)
                        val config = module.readConfig()
                        if (recipient != null && config != null && SystemHookUtils.isTargetCaller(
                                recipient.registry, recipient.pkg, config, recipient.uid)) {
                            val cells = if (config.optBoolean("mock_cell", true)) {
                                module.buildFakeCellInfoList(loader, config.optDouble("lat"), config.optDouble("lng"), config)
                            } else arrayListOf()
                            if (name == "onCellInfoChanged") args[0] = cells
                            else {
                                val identity = cells.firstOrNull()?.let { XposedHelpers.callMethod(it, "getCellIdentity") }
                                val parameter = method.parameterTypes[0]
                                args[0] = when {
                                    parameter == Bundle::class.java -> Bundle().also { bundle ->
                                        identity?.let { XposedHelpers.callMethod(it, "asCellLocation") }?.let {
                                            XposedHelpers.callMethod(it, "fillInNotifierBundle", bundle)
                                        }
                                    }
                                    parameter.name == "android.telephony.CellLocation" -> identity?.let {
                                        XposedHelpers.callMethod(it, "asCellLocation")
                                    }
                                    else -> identity
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        XposedBridge.logOpenCellIdEvery("cell-recipient", "Cell callback transform failed: $error", 10000L)
                    }
                }
                chain.proceed(args)
            }
        }
    }

    private data class Recipient(val registry: Any, val uid: Int, val pkg: String?)

    private fun recipient(callback: Any?): Recipient? {
        val binder = (callback as? IInterface)?.asBinder() ?: return null
        val snapshot = synchronized(registries) { registries.keys.toList() }
        for (registry in snapshot) {
            val records = XposedHelpers.getObjectField(registry, "mRecords") as? Collection<*> ?: continue
            synchronized(records) {
                for (record in records.filterNotNull()) {
                    val registered = XposedHelpers.getObjectField(record, "callback") as? IInterface ?: continue
                    if (registered.asBinder() == binder) {
                        return Recipient(registry, XposedHelpers.getIntField(record, "callerUid"),
                            XposedHelpers.getObjectField(record, "callingPackage") as? String)
                    }
                }
            }
        }
        // Deregistration and binder death are owned by the framework; no stale local target cache.
        return null
    }

    @Synchronized override fun close() {
        live = false
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
        callbackClasses.clear()
        registries.clear()
    }
}
