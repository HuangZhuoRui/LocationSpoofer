package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import android.Manifest
import android.app.AppOpsManager
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.SystemClock
import android.os.WorkSource
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.network.buildScanRecordBytes
import com.vincenthzr.locationspoofer.xposed.hooks.network.createBluetoothDevice
import com.vincenthzr.locationspoofer.xposed.hooks.network.createScanResult
import com.vincenthzr.locationspoofer.xposed.hooks.network.normalizeMacAddress
import com.vincenthzr.locationspoofer.xposed.utils.CallScope
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate

/** ColorOS moved BLE scanning out of GattService into le_scan.TransitionalScanHelper. */
internal class ColorOs16BleDelivery(module: LocationHooker, loader: ClassLoader) :
    ColorOs16HookSupport(module, loader, "BLE") {
    private class Owner {
        val queued = AtomicBoolean()
        // Accessed only on ScanManager's own handler. Keys are the actual ScanClient objects.
        val scans = WeakHashMap<Any, BleDeliveryState<ScanResult>>()
    }
    private data class Registration(val who: ColorOsCaller, val helper: WeakReference<Any>, val app: WeakReference<Any>)
    private val callbacks = Collections.synchronizedMap(WeakHashMap<Any, Registration>())
    private val callbackClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())
    private val owners = Collections.synchronizedMap(WeakHashMap<Any, Owner>())
    private val synthetic = CallScope<Boolean>()
    private val explicitFlush = CallScope<Boolean>()
    private val flushRequests = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    @Volatile private var ready = false
    private lateinit var scannerInterface: Class<*>
    private lateinit var permission: Method
    private lateinit var matches: Method
    private lateinit var sendBatch: Method
    private lateinit var sendIntent: Method
    private lateinit var dead: Method

    fun install() = section("TransitionalScanHelper") {
        val helper = SystemClassLocator.locate(SystemComponent.BLUETOOTH_SCAN_SERVICE, loader)
            ?: error("BLE scan helper unavailable")
        val client = helper.declaredMethods.first { it.name == "hasScanResultPermission" }.parameterTypes[0]
        val app = helper.declaredMethods.first { it.name == "sendBatchScanResults" }.parameterTypes[0]
        val intentInfo = type(helper.name + "\$PendingIntentInfo")
        scannerInterface = type("android.bluetooth.le.IScannerCallback")
        permission = method(helper, "hasScanResultPermission", client)
        matches = method(helper, "matchesFilters", client, ScanResult::class.java)
        sendBatch = method(helper, "sendBatchScanResults", app, client, ArrayList::class.java)
        sendIntent = method(helper, "sendResultsByPendingIntent", intentInfo, ArrayList::class.java, Int::class.javaPrimitiveType!!)
        dead = method(helper, "handleDeadScanClient", client)
        val manager = field(helper, "mScanManager").type
        field(manager, "mHandler") // Probe before installing the callback filters.
        method(manager, "getRegularScanQueue")
        method(manager, "getBatchScanQueue")

        // ContextMap.add is reached only after the platform accepted scanner registration.
        val mapClass = field(helper, "mScannerMap").type
        val add = mapClass.methods.first { it.name == "add" && it.parameterTypes.lastOrNull() == helper }
        hook(add) { chain ->
            val uid = Binder.getCallingUid()
            val registered = chain.proceed(chain.args.toTypedArray())
            if (registered != null) guard("register") {
                val helperObject = chain.args.last()!!
                owner(helperObject)
                val callback = get(registered, "callback")
                if (callback != null) {
                    callbacks[callback] = Registration(ColorOsCaller(uid, get(registered, "name") as? String),
                        WeakReference(helperObject), WeakReference(registered))
                    hookCallback(callback.javaClass)
                }
            }
            registered
        }
        helper.declaredMethods.filter { it.name in setOf("startScan", "registerPiAndStartScan", "continuePiStartScan") }
            .forEach { entry -> hook(entry) { chain ->
                val result = chain.proceed(chain.args.toTypedArray())
                chain.thisObject?.let { owner(it) }
                result
            } }
        hook(sendIntent) { chain ->
            val info = chain.args[0]!!
            val who = ColorOsCaller(get(info, "callingUid") as Int, get(info, "callingPackage") as? String)
            val config = module.readConfig()
            if (ready && synthetic.current != true && config != null &&
                config.optBoolean("mock_bluetooth", true) && target(who, config, chain.thisObject)) null
            else chain.proceed(chain.args.toTypedArray())
        }
        // Only an app-requested flush may bypass reportDelay. Hardware thresholds and
        // periodic controller flushes share the same handler and must not shorten it.
        hook(method(helper, "flushPendingBatchResults", Int::class.javaPrimitiveType!!, AttributionSource::class.java)) { chain ->
            val source = chain.args[1] as AttributionSource
            explicitFlush.withValue(source.uid >= 10000 && source.uid == Binder.getCallingUid()) {
                chain.proceed(chain.args.toTypedArray())
            }
        }
        hook(method(manager, "flushBatchScanResults", client)) { chain ->
            if (explicitFlush.current == true) flushRequests[chain.args[0]!!] = true
            chain.proceed(chain.args.toTypedArray())
        }
        // Preserve permission checking and ordering on ScanManager's own handler.
        val handlerClass = type(manager.name + "\$ClientHandler")
        hook(method(handlerClass, "handleFlushBatchResults", client)) { chain ->
            val forced = flushRequests.remove(chain.args[0]) == true
            val result = chain.proceed(chain.args.toTypedArray())
            guard("flush") {
                val scanManager = get(chain.thisObject!!, "this\$0")!!
                val helperObject = get(scanManager, "mScanHelper")!!
                tick(helperObject, owner(helperObject), get(chain.args[0]!!, "scannerId") as Int, forced)
            }
            result
        }
        helper.declaredMethods.filter { it.name in setOf("registerScanner", "registerAndStartScan", "flushPendingBatchResults", "onScanResultInternal", "onBatchScanReportsInternal",
            "sendBatchScanResults", "sendResultByPendingIntent", "onTrackAdvFoundLost") }.forEach {
            check(module.deoptimize(it)) { "Cannot deoptimize BLE delivery ${it.name}" }
        }
        every(1000) {
            val snapshot = synchronized(owners) { owners.entries.map { it.key to it.value } }
            for ((helperObject, state) in snapshot) guard("schedule") {
                val scanManager = get(helperObject, "mScanManager") ?: return@guard
                val handler = get(scanManager, "mHandler") as? Handler ?: return@guard
                if (state.queued.compareAndSet(false, true)) {
                    if (!handler.post {
                        try { if (live && ready) guard("delivery") { tick(helperObject, state) } }
                        finally { state.queued.set(false) }
                    }) state.queued.set(false)
                }
            }
        }
        ready = true
    }

    private fun owner(helper: Any): Owner = synchronized(owners) { owners.getOrPut(helper) { Owner() } }

    private fun tick(helper: Any, state: Owner, flushId: Int? = null, forceFlush: Boolean = false) {
        if (!ready || !live) return
        val config = module.readConfig()
        if (config == null || !config.optBoolean("active") || !config.optBoolean("mock_bluetooth", true)) {
            state.scans.clear()
            return
        }
        val manager = get(helper, "mScanManager") ?: return
        val map = get(helper, "mScannerMap") ?: return
        val regular = (call(manager, "getRegularScanQueue") as Collection<*>).filterNotNull()
        val batch = (call(manager, "getBatchScanQueue") as Collection<*>).filterNotNull()
        val all = regular + batch
        state.scans.keys.retainAll(all.toSet())
        val fixtures = results(config)
        for (client in all) guard("scan") {
            val id = get(client, "scannerId") as Int
            if (flushId != null && (id != flushId || client !in batch)) return@guard
            if (get(client, "appDied") == true) { state.scans.remove(client); return@guard }
            val app = method(map.javaClass, "getById", Int::class.javaPrimitiveType!!).invoke(map, id) ?: return@guard
            val callback = get(app, "callback")
            val info = get(app, "info")
            val who = if (callback != null) callbacks[callback]?.who?.let {
                it.copy(pkg = get(client, "callingPackage") as? String ?: it.pkg)
            } else info?.let {
                ColorOsCaller(get(it, "callingUid") as Int, get(it, "callingPackage") as? String)
            }
            if (who == null || !target(who, config, helper) || !permitted(helper, client, who)) {
                state.scans.remove(client); return@guard
            }
            val settings = get(client, "settings") as ScanSettings
            if (settings.scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC && all.none {
                (get(it, "settings") as ScanSettings).scanMode != ScanSettings.SCAN_MODE_OPPORTUNISTIC
            }) return@guard
            @Suppress("UNCHECKED_CAST")
            val denylist = get(helper, "mLocationDenylistPredicate") as Predicate<ScanResult>
            val matched = fixtures.filter {
                matches.invoke(helper, client, it) == true &&
                    (get(client, "hasDisavowedLocation") != true || !denylist.test(it))
            }
            val now = SystemClock.elapsedRealtime()
            val tracker = state.scans.getOrPut(client) { BleDeliveryState(now) }
            try {
                synthetic.withValue(true) {
                    if (client in batch) {
                        if (tracker.batchDue(now, settings.reportDelayMillis, forceFlush) && matched.isNotEmpty()) {
                            sendBatch.invoke(helper, app, client, ArrayList(matched))
                        }
                    } else if (settings.callbackType == 1 || settings.callbackType == 8) {
                        if (callback != null) {
                            matched.forEach { scannerInterface.getMethod("onScanResult", ScanResult::class.java).invoke(callback, it) }
                        } else if (info != null && matched.isNotEmpty()) sendIntent.invoke(helper, info, ArrayList(matched), 1)
                    } else {
                        val changes = tracker.track(matched.associateBy { it.device.address }, now)
                        for ((found, values) in listOf(true to changes.found, false to changes.lost)) {
                            val flag = if (found) 2 else 4
                            if (settings.callbackType and flag != 0) {
                                if (callback != null) values.forEach {
                                    scannerInterface.getMethod("onFoundOrLost", Boolean::class.javaPrimitiveType, ScanResult::class.java)
                                        .invoke(callback, found, it)
                                } else if (info != null && values.isNotEmpty()) sendIntent.invoke(helper, info, ArrayList(values), flag)
                            }
                        }
                    }
                }
            } catch (error: java.lang.reflect.InvocationTargetException) {
                val cause = error.targetException
                if (cause is android.os.RemoteException || cause is android.app.PendingIntent.CanceledException) {
                    dead.invoke(helper, client); state.scans.remove(client)
                } else throw cause
            }
        }
    }

    private fun hookCallback(type: Class<*>) {
        if (!callbackClasses.add(type)) return
        type.declaredMethods.filter { it.name in setOf("onScanResult", "onBatchScanResults", "onFoundOrLost") }
            .forEach { entry -> hook(entry) { chain ->
                val registration = callbacks[chain.thisObject]
                val config = module.readConfig()
                val suppress = if (ready && synthetic.current != true && registration != null && config != null &&
                    config.optBoolean("mock_bluetooth", true)) guard("filter") {
                    val helper = registration.helper.get() ?: return@guard false
                    val app = registration.app.get() ?: return@guard false
                    val map = get(helper, "mScannerMap") ?: return@guard false
                    val current = method(map.javaClass, "getById", Int::class.javaPrimitiveType!!)
                        .invoke(map, get(app, "id"))
                    current === app && target(registration.who, config, helper)
                } == true else false
                if (suppress) null else chain.proceed(chain.args.toTypedArray())
            } }
    }

    private fun permitted(helper: Any, client: Any, who: ColorOsCaller): Boolean {
        if (permission.invoke(helper, client) != true) return false
        val context = get(helper, "mContext") as Context
        val pkg = who.pkg ?: return false
        val ops = context.getSystemService(AppOpsManager::class.java)
        // Recheck revocation while a session is already running; no fabricated denied result.
        if (context.checkPermission(Manifest.permission.BLUETOOTH_SCAN, -1, who.uid) != PackageManager.PERMISSION_GRANTED) return false
        val scanOp = AppOpsManager.permissionToOp(Manifest.permission.BLUETOOTH_SCAN) ?: return false
        if (ops.noteOpNoThrow(scanOp, who.uid, pkg) != AppOpsManager.MODE_ALLOWED) return false
        if (get(client, "hasDisavowedLocation") != true && get(client, "hasScanWithoutLocationPermission") != true &&
            get(client, "hasNetworkSettingsPermission") != true && get(client, "hasNetworkSetupWizardPermission") != true) {
            val fine = get(client, "isQApp") == true
            val perm = if (fine) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
            val op = if (fine) AppOpsManager.OPSTR_FINE_LOCATION else AppOpsManager.OPSTR_COARSE_LOCATION
            if (context.checkPermission(perm, -1, who.uid) != PackageManager.PERMISSION_GRANTED ||
                ops.noteOpNoThrow(op, who.uid, pkg) != AppOpsManager.MODE_ALLOWED) return false
        }
        return true
    }

    private fun results(config: JSONObject): List<ScanResult> {
        if (!config.optBoolean("mock_bluetooth", true)) return emptyList()
        val entries = config.optJSONArray("bluetooth_json") ?: return emptyList()
        val parse = method(type("android.bluetooth.le.ScanRecord"), "parseFromBytes", ByteArray::class.java)
        return (0 until entries.length().coerceAtMost(200)).mapNotNull { index -> guard("fixture") {
            val data = entries.optJSONObject(index) ?: return@guard null
            val address = normalizeMacAddress(data.optString("address"))
            if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return@guard null
            val device = createBluetoothDevice(loader, address) ?: return@guard null
            val bytes = buildScanRecordBytes(data.optString("name"),
                data.optString("scanRecordHex", data.optString("rawBytes")), address)
            val record = parse.invoke(null, bytes)
            createScanResult(loader, device, record, data.optInt("rssi", -65).coerceIn(-127, 20),
                SystemClock.elapsedRealtimeNanos()) as? ScanResult
        } }.distinctBy { it.device.address }
    }

    override fun close() { ready = false; super.close(); owners.clear(); callbacks.clear(); callbackClasses.clear(); flushRequests.clear() }
}
