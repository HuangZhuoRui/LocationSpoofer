package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.xposed.hooks.getCurrentSpoofedMotion
import android.location.Location
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import com.vincenthzr.locationspoofer.xposed.utils.CallScope
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.getJitteredAccuracy
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.WeakHashMap
import java.util.function.Function
import java.util.function.Predicate

/**
 * ColorOS 16 (Android 16) adapter. Validation scope is documented in docs/ColorOS16.md.
 * Only per-request copies enter the existing framework delivery path. Provider caches,
 * Binder permission checks, coarse-location filtering and cancellation remain Android's job.
 */
internal class ColorOs16LocationDelivery(
    private val module: LocationHooker,
    private val loader: ClassLoader
) : AutoCloseable {
    private val managers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val lastLocationScope = CallScope<Any>()
    private var policyKey: String? = null
    private var updateRegistrations: Method? = null
    private val hooks = mutableListOf<XposedInterface.HookHandle>()
    @Volatile private var enabled = false
    private var timer: Timer? = null
    private lateinit var registrationClass: Class<*>
    private lateinit var getIdentity: Method
    private lateinit var getUid: Method
    private lateinit var getPackageName: Method
    private lateinit var acceptLocation: Method
    private lateinit var deliverToListeners: Method
    private lateinit var wrapResult: Method
    private lateinit var deepCopyResult: Method
    private lateinit var resultAsList: Method
    private lateinit var nameField: Field
    private lateinit var internalField: Field
    private lateinit var ownerField: Field
    private lateinit var isProvider: Method

    fun install(): Boolean {
        try {
            val manager = SystemClassLocator.locate(SystemComponent.LOCATION_PROVIDER_MANAGER, loader)
                ?: error("Location provider manager unavailable")
            val managerName = manager.name
            registrationClass = Class.forName("$managerName\$Registration", false, loader)
            val continuous = Class.forName("$managerName\$LocationRegistration", false, loader)
            val current = Class.forName("$managerName\$GetCurrentLocationListenerRegistration", false, loader)
            val identity = Class.forName("android.location.util.identity.CallerIdentity", false, loader)
            val result = Class.forName("android.location.LocationResult", false, loader)
            val request = Class.forName("android.location.LastLocationRequest", false, loader)

            // Probe all required signatures before installing anything. Do not silently fall
            // back to the legacy global cache mutation when a different ROM changes them.
            getIdentity = registrationClass.getDeclaredMethod("getIdentity").accessible()
            getUid = identity.getMethod("getUid").accessible()
            getPackageName = identity.getMethod("getPackageName").accessible()
            acceptLocation = registrationClass.getDeclaredMethod("acceptLocationChange", result).accessible()
            deliverToListeners = findMethod(manager, "deliverToListeners", Function::class.java)
            updateRegistrations = findMethod(manager, "updateRegistrations", Predicate::class.java)
            wrapResult = result.getMethod("wrap", Array<Location>::class.java).accessible()
            deepCopyResult = result.getMethod("deepCopy").accessible()
            resultAsList = result.getMethod("asList").accessible()
            nameField = findField(manager, "mName")
            internalField = findField(manager, "mLocationManagerInternal")
            ownerField = findField(registrationClass, "this\$0")
            isProvider = internalField.type.getMethod("isProvider", String::class.java, identity).accessible()
            val added = manager.getDeclaredMethod("onRegistrationAdded", Any::class.java, registrationClass)
            val reported = manager.getDeclaredMethod("onReportLocation", result)
            val getLast = manager.getDeclaredMethod("getLastLocation", request, identity, Int::class.javaPrimitiveType)
            val getLastUnsafe = manager.getDeclaredMethod(
                "getLastLocationUnsafe", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            val deliveries = listOf(continuous, current).map {
                it.getDeclaredMethod("acceptLocationChange", result)
            }
            val active = manager.getDeclaredMethod("isActive", Boolean::class.javaPrimitiveType, identity)
            hook(continuous.getDeclaredMethod("onProviderEnabledChanged", String::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) { chain ->
                val args = chain.args.toTypedArray()
                val registration = chain.thisObject!!
                val config = module.readConfig()
                if (config != null && config.optBoolean("force_location_enabled", false) &&
                    isTarget(ownerField.get(registration), getIdentity.invoke(registration), config)) args[2] = true
                chain.proceed(args)
            }
            check(module.deoptimize(active)) { "Unable to deoptimize provider activation" }
            check(module.deoptimize(manager.getDeclaredMethod("isActive", registrationClass))) {
                "Unable to deoptimize registration activation"
            }

            hook(added) { chain ->
                val original = chain.proceed(chain.args.toTypedArray())
                chain.thisObject?.let { managers.add(it) }
                original
            }
            hook(reported) { chain ->
                // Observe existing managers after a hot reload; never rewrite provider input.
                chain.thisObject?.let { managers.add(it) }
                chain.proceed(chain.args.toTypedArray())
            }
            hook(getLast) { chain ->
                val owner = chain.thisObject
                val config = module.readConfig()
                val caller = chain.args[1]
                val target = owner != null && caller != null && config != null &&
                    isTarget(owner, caller, config)
                val original = lastLocationScope.withValue(if (target) owner else null) {
                    // All original enabled-state, AppOps and coarse-location checks still run.
                    chain.proceed(chain.args.toTypedArray())
                }
                original
            }
            hook(getLastUnsafe) { chain ->
                val owner = chain.thisObject
                if (owner != null && lastLocationScope.current === owner) {
                    val fake = guarded("last location") { makeLocation(owner) }
                    if (fake != null) return@hook fake
                }
                chain.proceed(chain.args.toTypedArray())
            }
            for (delivery in deliveries) {
                hook(delivery) { chain ->
                    val registration = chain.thisObject
                    val originalResult = chain.args[0]
                    // A null single-shot result means cancellation/timeout/inactive: keep it null.
                    val replacement = if (registration != null && originalResult != null) {
                        guarded("location delivery") {
                            val owner = ownerField.get(registration)
                            val config = module.readConfig()
                            val caller = getIdentity.invoke(registration)
                            if (config != null && isTarget(owner, caller, config)) {
                                copyResult(originalResult, config)
                            } else null
                        }
                    } else null
                    val args = chain.args.toTypedArray()
                    if (replacement != null) args[0] = replacement
                    chain.proceed(args)
                }
            }

            // getLastLocationUnsafe may already be inlined into this system-server method.
            // Its caller must run through the hook for the scoped replacement to take effect.
            check(module.deoptimize(getLast)) { "Unable to deoptimize getLastLocation" }

            enabled = true
            timer = Timer("LocationSpoofer-ColorOS16", true).apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        if (enabled) guarded("heartbeat") { tick() }
                    }
                }, 1000L, 1000L)
            }
            return true
        } catch (error: Throwable) {
            HookStatus.error("ColorOS16 location delivery", error)
            close()
            XposedBridge.log("[ColorOS16] Adapter disabled: incompatible framework or hook failure: $error")
            return false
        }
    }

    private fun tick() {
        val config = module.readConfig()
        val snapshot = synchronized(managers) { managers.toList() }
        val key = listOf(config?.optBoolean("active"), config?.optBoolean("force_location_enabled", false),
            config?.optBoolean("system_hook_global_mode"), config?.optJSONArray("system_hook_packages")).joinToString("|")
        if (key != policyKey) {
            snapshot.forEach { owner -> guarded("activation refresh") {
                updateRegistrations?.invoke(owner, Predicate<Any> { true })
            } }
            policyKey = key
        }
        if (config == null || !config.optBoolean("active", false)) return
        for (owner in snapshot) {
            guarded("provider heartbeat") {
                // Use Android's multiplexer lock, reentrancy guard and active-registration
                // filter. Android performs expiration, AppOps, throttling, removal and IPC.
                val operation = Function<Any, Any?> { registration ->
                    guarded("registration heartbeat") registration@{
                        if (!enabled) return@registration null
                        val currentConfig = module.readConfig() ?: return@registration null
                        val caller = getIdentity.invoke(registration)
                        if (!isTarget(owner, caller, currentConfig)) return@registration null
                        val location = makeLocation(owner) ?: return@registration null
                        val result = wrapResult.invoke(null, arrayOf(location) as Any)
                        acceptLocation.invoke(registration, result)
                    }
                }
                deliverToListeners.invoke(owner, operation)
            }
        }
    }

    private fun isTarget(owner: Any, caller: Any, config: JSONObject): Boolean =
        guarded("caller identity") {
            if (!config.optBoolean("active", false)) return@guarded false
            val uid = getUid.invoke(caller) as Int
            val pkg = getPackageName.invoke(caller) as String
            if (!SystemHookUtils.isTargetCaller(owner, pkg, config, uid)) return@guarded false
            // Protect whatever provider the ROM actually binds, in addition to known packages.
            val locationInternal = internalField.get(owner) ?: return@guarded false
            isProvider.invoke(locationInternal, null, caller) != true
        } ?: false


    private fun makeLocation(owner: Any): Location? {
        val config = module.readConfig() ?: return null
        if (!config.optBoolean("active", false)) return null
        val motion = module.getCurrentSpoofedMotion("WGS-84") ?: return null
        val provider = nameField.get(owner) as String
        return (SystemHookUtils.buildFakeLocation(
            loader, provider, motion, config.optDouble("altitude", 25.0), module.getJitteredAccuracy()
        ) as? Location)?.also { applySatelliteExtras(it, config) }
    }

    private fun copyResult(original: Any, config: JSONObject): Any? {
        val motion = module.getCurrentSpoofedMotion("WGS-84") ?: return null
        // Deep-copy both Location and extras. Never mutate data shared with another registration.
        val copy = deepCopyResult.invoke(original)
        val locations = resultAsList.invoke(copy) as List<*>
        for (item in locations) {
            val location = item as Location
            SystemHookUtils.applyFakeLocationFields(
                location, motion, config.optDouble("altitude", 25.0), module.getJitteredAccuracy()
            )
            location.removeMslAltitude()
            location.removeMslAltitudeAccuracy()
            applySatelliteExtras(location, config)
        }
        return copy
    }

    private fun applySatelliteExtras(location: Location, config: JSONObject) {
        val satellites = config.optInt("satellite_count", 20).coerceIn(4, 32)
        location.extras = android.os.Bundle(location.extras ?: android.os.Bundle()).apply {
            putInt("satellites", satellites)
            putInt("satellites_in_view", satellites)
            putInt("satellites_visible", satellites)
            putInt("satellites_used_in_fix", satellites.coerceAtMost(12))
        }
    }

    private fun hook(method: Method, interceptor: (XposedInterface.Chain) -> Any?) {
        val installed = XposedHelpers.hookAllMethods(method.declaringClass, method.name) { chain, hooked ->
            if (!enabled || hooked != method) chain.proceed(chain.args.toTypedArray())
            else interceptor(chain)
        }
        hooks.addAll(installed)
        check(installed.isNotEmpty()) { "Unable to hook $method" }
    }

    private fun <T> guarded(operation: String, block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        XposedBridge.logOpenCellIdEvery("ColorOS16-$operation", "$operation failed: $error", 10000L)
        null
    }

    override fun close() {
        enabled = false
        timer?.cancel()
        timer = null
        hooks.asReversed().forEach { handle -> runCatching { handle.unhook() } }
        hooks.clear()
        synchronized(managers) { managers.toList() }.forEach { owner -> guarded("restore activation") {
            updateRegistrations?.invoke(owner, Predicate<Any> { true })
        } }
        managers.clear()
    }

    private fun Method.accessible() = apply { isAccessible = true }

    private fun findMethod(type: Class<*>, name: String, vararg args: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *args).accessible()
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("${type.name}.$name")
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }
}
