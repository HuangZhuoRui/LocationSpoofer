package com.suseoaa.locationspoofer.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.lang.reflect.Member

// --- Legacy Compatibility Layer ---
abstract class XC_MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    class MethodHookParam {
        var method: Member? = null
        var thisObject: Any? = null
        var args: Array<Any?> = emptyArray()
        var returnEarly = false
        var result: Any? = null
            set(value) {
                field = value
                returnEarly = true
            }
        var throwable: Throwable? = null
            set(value) {
                field = value
                returnEarly = true
            }
    }
}

object XposedHelpers {
    lateinit var module: XposedModule

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try { findClass(className, classLoader) } catch (e: Throwable) { null }
    }

    fun getObjectField(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    fun setIntField(obj: Any, fieldName: String, value: Int) { setObjectField(obj, fieldName, value) }
    fun setDoubleField(obj: Any, fieldName: String, value: Double) { setObjectField(obj, fieldName, value) }
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) { setObjectField(obj, fieldName, value) }
    fun setLongField(obj: Any, fieldName: String, value: Long) { setObjectField(obj, fieldName, value) }

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val argTypes = args.map { it?.javaClass ?: Any::class.java }
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (m in clazz.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == methodName && m.parameterCount == args.size && java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    return m.invoke(null, *args)
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(methodName, *parameterTypes)
                m.isAccessible = true
                return m
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(methodName)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        for (c in clazz.declaredConstructors) {
            if (c.parameterCount == args.size) {
                c.isAccessible = true
                return c.newInstance(*args)
            }
        }
        throw NoSuchMethodException("Constructor for " + clazz.name + " not found")
    }

    fun findAndHookMethod(className: String, classLoader: ClassLoader?, methodName: String, vararg args: Any?) {
        try {
            val clazz = findClass(className, classLoader)
            findAndHookMethod(clazz, methodName, *args)
        } catch (e: Throwable) {
            // log
        }
    }

    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg args: Any?) {
        val hookIndex = args.indexOfLast { it is XC_MethodHook }
        if (hookIndex == -1) return
        val callback = args[hookIndex] as XC_MethodHook
        val paramTypes = args.slice(0 until hookIndex).map {
            when (it) {
                is Class<*> -> it
                is String -> findClass(it, clazz.classLoader)
                else -> throw IllegalArgumentException("Invalid argument type")
            }
        }.toTypedArray()

        val method = findMethodExact(clazz, methodName, *paramTypes)
        hookMethod(method, callback)
    }

    fun hookMethod(executable: java.lang.reflect.Executable, callback: XC_MethodHook) {
        module.hook(executable).intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
            override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                val param = XC_MethodHook.MethodHookParam().apply {
                    this.method = executable
                    this.thisObject = chain.thisObject
                    this.args = chain.args.toTypedArray()
                }

                try {
                    callback.beforeHookedMethod(param)
                } catch (e: Throwable) {
                    param.throwable = e
                }

                if (!param.returnEarly) {
                    try {
                        param.result = chain.proceed(param.args)
                    } catch (e: Throwable) {
                        param.throwable = e
                    }
                }

                try {
                    callback.afterHookedMethod(param)
                } catch (e: Throwable) {
                    param.throwable = e
                }

                if (param.throwable != null) throw param.throwable!!
                return param.result
            }
        })
    }
}

object XposedBridge {
    private val openCellLogLastTimes = ConcurrentHashMap<String, Long>()

    fun log(msg: String) {
        android.util.Log.i("LocationSpoofer_Xposed", msg)
        try { XposedHelpers.module.log(android.util.Log.INFO, "LocationSpoofer", msg) } catch (e: Throwable) {}
    }
    fun logOpenCellId(msg: String) {
        val text = "[XposedCell] $msg"
        android.util.Log.d("OpenCellID", text)
        try { XposedHelpers.module.log(android.util.Log.DEBUG, "OpenCellID", text) } catch (e: Throwable) {}
    }
    fun logOpenCellId(msg: String, t: Throwable) {
        val text = "[XposedCell] $msg"
        android.util.Log.e("OpenCellID", text, t)
        try { XposedHelpers.module.log(android.util.Log.ERROR, "OpenCellID", text, t) } catch (e: Throwable) {}
    }
    fun logOpenCellIdEvery(key: String, msg: String, intervalMs: Long = 10_000L) {
        val now = System.currentTimeMillis()
        val last = openCellLogLastTimes[key]
        if (last == null || now - last >= intervalMs) {
            openCellLogLastTimes[key] = now
            logOpenCellId(msg)
        }
    }
    fun log(t: Throwable) {
        android.util.Log.e("LocationSpoofer_Xposed", "Error", t)
        try { XposedHelpers.module.log(android.util.Log.ERROR, "LocationSpoofer", "Error", t) } catch (e: Throwable) {}
    }
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook) {
        var hooked = false
        for (m in clazz.declaredMethods) {
            if (m.name == methodName) {
                XposedHelpers.hookMethod(m, callback)
                hooked = true
            }
        }
    }
}

class LocationHooker : XposedModule() {
    init {
        XposedHelpers.module = this
    }

    private val nmeaTimers = ConcurrentHashMap<Any, java.util.Timer>()
    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()
    @Volatile
    private var currentPackageName: String = ""

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // Nothing here for now
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        val classLoader = param.classLoader
        handleLoadPackage(pkg, classLoader)
    }
    
    // --- Original Logic ---


    companion object {

        // 系统进程同样需要覆盖（android进程持有LocationManagerService）
        val SYSTEM_PACKAGES = setOf("android", "system", "com.android.phone")
        private const val VERBOSE_CELL_BUILD_LOGS = false
    }

    fun handleLoadPackage(pkg: String, classLoader: ClassLoader) {
        currentPackageName = pkg
        

        // 宿主App自报平安
        if (pkg == "com.suseoaa.locationspoofer") {
            return // 宿主App不需要注入定位Hook
        }

        // 系统进程：允许执行所有的环境数据Hook，实现系统原生界面的完美覆盖
        // if (SYSTEM_PACKAGES.contains(pkg)) {
        //     hookLocationAPIs(classLoader, pkg)
        //     return
        // }


        XposedBridge.log("[LocationSpoofer] Hooking package: $pkg")
        XposedBridge.logOpenCellId("handleLoadPackage pkg=$pkg classLoader=$classLoader")

        // ★ 反检测: 必须在其他Hook之前安装,隐藏Xposed环境
        hookAntiDetection(classLoader)

        hookLocationAPIs(classLoader, pkg)
        hookWifiEnvironment(classLoader)
        hookCellEnvironment(classLoader)
        hookConnectivityLayer(classLoader)
        hookBluetoothLE(classLoader)
        hookGnssStatus(classLoader)
    }

    /**
     * ★ 反检测: 隐藏Xposed环境,防止反作弊SDK检测到Hook
     *
     * 设计原则:
     * 1. 只使用精确匹配,绝不使用宽泛的contains/startsWith,避免误杀正常类
     * 2. 不Hook ClassLoader.loadClass的宽泛模式(会导致App卡死)
     * 3. 不Hook BufferedReader.readLine(开销巨大)
     * 4. 不Hook File.exists/Runtime.exec(干扰正常功能)
     */
    private fun hookAntiDetection(classLoader: ClassLoader) {

        // ── 1. 堆栈帧过滤 ──
        // 反作弊SDK通过getStackTrace()检查调用链,发现Xposed帧即判定为Hook环境
        // 只过滤精确匹配的Xposed类名,不影响正常堆栈
        val xposedClassNames = setOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "de.robv.android.xposed.XC_MethodReplacement",
            "de.robv.android.xposed.XposedHelpers",
            "de.robv.android.xposed.XC_MethodHook\$MethodHookParam",
            "io.github.libxposed.api.XposedModule",
            "io.github.libxposed.api.XposedInterface",
            "io.github.libxposed.api.XposedModuleInterface",
            "org.lsposed.manager.MainApplication",
            "io.github.lsposed.manager.App"
        )

        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.Throwable", classLoader, "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stackTrace = param.result as? Array<StackTraceElement> ?: return
                        val filtered = stackTrace.filter { elem ->
                            elem.className !in xposedClassNames
                        }.toTypedArray()
                        if (filtered.size != stackTrace.size) {
                            param.result = filtered
                        }
                    }
                })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.Thread", classLoader, "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stackTrace = param.result as? Array<StackTraceElement> ?: return
                        val filtered = stackTrace.filter { elem ->
                            elem.className !in xposedClassNames
                        }.toTypedArray()
                        if (filtered.size != stackTrace.size) {
                            param.result = filtered
                        }
                    }
                })
        } catch (_: Throwable) {}

        // ── 2. Class.forName 精确匹配 ──
        // 反作弊SDK通过Class.forName()尝试加载Xposed类,成功则判定为Hook环境
        // 使用精确匹配(不是contains),只拦截已知Xposed类名
        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.Class", classLoader, "forName",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (className in xposedClassNames) {
                            throw ClassNotFoundException()
                        }
                    }
                })
        } catch (_: Throwable) {
            // 降级: 尝试2参数版本
            try {
                XposedHelpers.findAndHookMethod(
                    "java.lang.Class", classLoader, "forName",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val className = param.args[0] as? String ?: return
                            if (className in xposedClassNames) {
                                throw ClassNotFoundException()
                            }
                        }
                    })
            } catch (_: Throwable) {}
        }

        // ── 3. ClassLoader.loadClass 精确匹配 ──
        // 同样使用精确匹配,只拦截已知Xposed类名
        // loadClass被调用频率很高,精确匹配确保零误杀
        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.ClassLoader", classLoader, "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (className in xposedClassNames) {
                            throw ClassNotFoundException()
                        }
                    }
                })
        } catch (_: Throwable) {}

// ── 4. 拦截 AppOpsManager 的 OP_MOCK_LOCATION (58) ──
        // 很多深度定制系统（如 MIUI）和硬核反作弊会检查 AppOps 权限
        try {
            val appOpsHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig() ?: return
                    if (!config.optBoolean("active", false)) return
                    
                    val opArg = param.args[0]
                    val isMockOp = if (opArg is Int) {
                        opArg == 58 // OP_MOCK_LOCATION
                    } else if (opArg is String) {
                        opArg == "android:mock_location"
                    } else false

                    if (isMockOp) {
                        // MODE_IGNORED = 1, MODE_ERRORED = 2
                        param.result = 1 // MODE_IGNORED，让对方以为我们没有被授权模拟位置
                    }
                }
            }
            
            val appOpsClass = XposedHelpers.findClass("android.app.AppOpsManager", classLoader)
            try { XposedBridge.hookAllMethods(appOpsClass, "checkOp", appOpsHook) } catch (e: Throwable) {}
            try { XposedBridge.hookAllMethods(appOpsClass, "checkOpNoThrow", appOpsHook) } catch (e: Throwable) {}
            try { XposedBridge.hookAllMethods(appOpsClass, "noteOp", appOpsHook) } catch (e: Throwable) {}
            try { XposedBridge.hookAllMethods(appOpsClass, "noteOpNoThrow", appOpsHook) } catch (e: Throwable) {}
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 5. 拦截 Settings.Secure 的 mock_location 开关查询 ──
        try {
            val secureHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig() ?: return
                    if (!config.optBoolean("active", false)) return
                    
                    val name = param.args[1] as? String
                    if (name == "mock_location") {
                        if (param.method?.name == "getInt") {
                            param.result = 0
                        } else if (param.method?.name == "getString") {
                            param.result = "0"
                        }
                    }
                }
            }
            
            val secureClass = XposedHelpers.findClass("android.provider.Settings\$Secure", classLoader)
            try { XposedHelpers.findAndHookMethod(secureClass, "getInt", android.content.ContentResolver::class.java, String::class.java, secureHook) } catch (e: Throwable) {}
            try { XposedHelpers.findAndHookMethod(secureClass, "getInt", android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, secureHook) } catch (e: Throwable) {}
            try { XposedHelpers.findAndHookMethod(secureClass, "getString", android.content.ContentResolver::class.java, String::class.java, secureHook) } catch (e: Throwable) {}
        } catch (e: Throwable) { XposedBridge.log(e) }

        XposedBridge.log("[LocationSpoofer] Anti-detection hooks installed")
    }

    private var startTimestamp = System.currentTimeMillis()

    // ── GCJ-02 → WGS-84 转换（Xposed模块运行在目标App进程，必须自带转换代码）──
    private val GCJ_A = 6378245.0
    private val GCJ_EE = 0.00669342162296594

    private fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (gcjLng < 72.004 || gcjLng > 137.8347 || gcjLat < 0.8293 || gcjLat > 55.8271)
            return Pair(gcjLat, gcjLng)
        val dLat = gcjTransformLat(gcjLng - 105.0, gcjLat - 35.0)
        val dLng = gcjTransformLng(gcjLng - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val mLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        val mLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(gcjLat - mLat, gcjLng - mLng)
    }

    private fun gcjTransformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun gcjTransformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    // ── GCJ-02 → BD-09 转换(百度坐标系) ──
    //
    // BD-09是百度在GCJ-02基础上施加的二次偏移坐标系。百度地图/百度定位SDK(BDLocation)
    // 内部期望接收BD-09坐标,若直接传入GCJ-02会产生约100-500米的固定偏移。
    //
    // 算法原理:
    // 1. 将GCJ-02坐标解释为以(0,0)为中心的直角坐标(x=lng, y=lat)
    // 2. 施加百度公开的偏移常量(x偏移0.0065度, y偏移0.006度)
    // 3. 将偏移后的直角坐标转为极坐标(r, theta),其中r=sqrt(x^2+y^2), theta=atan2(y,x)
    // 4. 对极角theta叠加一个与r相关的微小旋转量: theta += BD_PI * sin(r * BD_PI) * 0.000003
    //    BD_PI = pi * 3000/180 ≈ 52.3598..., 这是百度定义的旋转频率系数
    // 5. 对极径r叠加微小伸缩: r += BD_PI * cos(r * BD_PI) * 0.00002
    // 6. 将修正后的极坐标转回直角坐标,即为BD-09经纬度
    //
    // 为何不能省略此转换:
    // BDLocation.getLatitude()被Hook后如果返回GCJ-02坐标,百度SDK内部不会再做转换,
    // 直接将该值作为BD-09渲染到地图上,导致显示位置相对真实位置偏移数百米。

    /** 百度坐标系旋转频率常量: pi * 3000 / 180 */
    private val BD_PI = Math.PI * 3000.0 / 180.0

    /**
     * GCJ-02坐标转BD-09坐标
     *
     * @param gcjLat GCJ-02纬度(高德/腾讯坐标系)
     * @param gcjLng GCJ-02经度
     * @return Pair(BD-09纬度, BD-09经度)
     */
    private fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        val x = gcjLng
        val y = gcjLat
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * BD_PI)
        val theta = Math.atan2(y, x) + 0.000003 * cos(x * BD_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return Pair(bdLat, bdLng)
    }

    /**
     * 高斯随机游走状态(Xposed进程内独立维护)
     * 使用Ornstein-Uhlenbeck过程: X(t+dt) = X(t) + sigma*sqrt(dt)*N(0,1) - alpha*X(t)*dt
     * 产生白噪声频谱,FFT检测无法发现单频峰
     */
    private val rng = Random()
    private var hookDriftLat = 0.0
    private var hookDriftLng = 0.0
    private var hookAccuracyDrift = 0.0
    private var hookLastCallTime = 0L

    private fun getJitteredLocation(baseLat: Double, baseLng: Double): Pair<Double, Double> {
        val enableJitter = lastConfig?.optBoolean("enable_jitter", true) ?: true
        if (!enableJitter) return Pair(baseLat, baseLng)

        val now = System.currentTimeMillis()
        val dt = if (hookLastCallTime > 0) {
            ((now - hookLastCallTime) / 1000.0).coerceIn(0.01, 5.0)
        } else 1.0
        hookLastCallTime = now

        // sigma=0.000002度(约0.2米步长), alpha=0.05(均值回归)
        val sigma = 0.000002
        val alpha = 0.05
        
        // 使用 Ornstein-Uhlenbeck 过程生成自然偏移，并硬性限制在 4 米以内 (约 0.00004 度)
        hookDriftLat = (hookDriftLat + sigma * sqrt(dt) * rng.nextGaussian() - alpha * hookDriftLat * dt)
            .coerceIn(-0.00004, 0.00004)
        hookDriftLng = (hookDriftLng + sigma * sqrt(dt) * rng.nextGaussian() - alpha * hookDriftLng * dt)
            .coerceIn(-0.00004, 0.00004)

        return Pair(baseLat + hookDriftLat, baseLng + hookDriftLng)
    }

    private fun getJitteredAccuracy(): Float {
        // 精度值在基准20m附近做高斯漂移,模拟GDOP变化
        hookAccuracyDrift += 0.5 * rng.nextGaussian() - 0.03 * hookAccuracyDrift
        return (20.0 + hookAccuracyDrift).coerceIn(3.0, 45.0).toFloat()
    }


    private fun hookLocationAPIs(classLoader: ClassLoader, currentPkg: String) {
        try {
            // android.location.Location 标准接口: 返回GCJ-02坐标
            //
            // 关键决策 -- 为何不返回WGS-84:
            // 在中国大陆,系统GPS HAL层已内置GCJ-02强制加偏(国家测绘法规要求)。
            // 因此android.location.Location.getLatitude()在中国设备上实际返回的是GCJ-02坐标,
            // 而非API文档声称的WGS-84。所有中国地图App(高德/腾讯/百度)都基于这一事实编写:
            // 它们从Location拿到坐标后不会再做WGS-84到GCJ-02的转换,而是直接使用。
            //
            // 如果我们返回真正的WGS-84,App会把它当GCJ-02直接传给地图SDK渲染,
            // 由于WGS-84与GCJ-02之间存在约300-500米的非线性偏移,地图上会出现固定偏移。
            // 这正是之前微信和学习通出现定位偏移的根本原因。
            val getLatHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val appSystems = config.optJSONObject("app_coordinate_systems")
                        val basePkg = currentPkg.substringBefore(":")
                        // optString 在 key 不存在时返回 ""（空字符串），不是 null！
                        // 必须用 has() 先检查，否则会错误匹配到空字符串分支
                        val targetSys = if (appSystems?.has(basePkg) == true) {
                            appSystems.optString(basePkg, "GCJ-02")
                        } else {
                            "GCJ-02"
                        }
                        val baseLat = when (targetSys) {
                            "WGS-84" -> config.optDouble("wgs84_lat", param.result as Double)
                            "BD-09" -> config.optDouble("bd09_lat", param.result as Double)
                            else -> config.optDouble("lat", param.result as Double)
                        }
                        val baseLng = when (targetSys) {
                            "WGS-84" -> config.optDouble("wgs84_lng", 0.0)
                            "BD-09" -> config.optDouble("bd09_lng", 0.0)
                            else -> config.optDouble("lng", 0.0)
                        }
                        param.result = getJitteredLocation(baseLat, baseLng).first
                    }
                }
            }

            val getLngHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val appSystems = config.optJSONObject("app_coordinate_systems")
                        val basePkg = currentPkg.substringBefore(":")
                        // optString 在 key 不存在时返回 ""（空字符串），不是 null！
                        val targetSys = if (appSystems?.has(basePkg) == true) {
                            appSystems.optString(basePkg, "GCJ-02")
                        } else {
                            "GCJ-02"
                        }
                        val baseLat = when (targetSys) {
                            "WGS-84" -> config.optDouble("wgs84_lat", 0.0)
                            "BD-09" -> config.optDouble("bd09_lat", 0.0)
                            else -> config.optDouble("lat", 0.0)
                        }
                        val baseLng = when (targetSys) {
                            "WGS-84" -> config.optDouble("wgs84_lng", param.result as Double)
                            "BD-09" -> config.optDouble("bd09_lng", param.result as Double)
                            else -> config.optDouble("lng", param.result as Double)
                        }
                        param.result = getJitteredLocation(baseLat, baseLng).second
                    }
                }
            }

            val getAccHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = getJitteredAccuracy()
                    }
                }
            }

            val getAltHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val baseAlt = config.optDouble("altitude", 0.0)
                        val enableJitter = config.optBoolean("enable_jitter", true)
                        param.result = if (enableJitter && baseAlt > 0.0) {
                            // 稍微抖动海拔，真实气压计存在起伏，±0.5米
                            baseAlt + (rng.nextDouble() - 0.5)
                        } else {
                            baseAlt
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLatitude",
                getLatHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLongitude",
                getLngHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getAccuracy",
                getAccHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getAltitude",
                getAltHook
            )

            // ★ 核心反检测：抹除 isFromMockProvider 标志位（strategy:100 的根本来源）
            val antiMockHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = false
                    }
                }
            }
            // Android 6~11: isFromMockProvider()
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isFromMockProvider",
                antiMockHook
            )
            // Android 12+: isMock()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.Location",
                    classLoader,
                    "isMock",
                    antiMockHook
                )
            } catch (e: Throwable) { /* API < 31 没有此方法 */
            }

            // ★ Android 13 专项：直接对 Location 对象的 mMock / mIsFromMockProvider 字段写 false
            // (Android 12+ 字段名改为 mMock，Android 6-11 为 mIsFromMockProvider)
            val fieldCleanHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val loc = param.thisObject ?: return
                        try {
                            XposedHelpers.setBooleanField(loc, "mMock", false)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false)
                        } catch (e: Throwable) {
                        }
                        // 清理 extras bundle 中可能残留的 mock 标记
                        try {
                            val extras =
                                XposedHelpers.callMethod(loc, "getExtras") as? android.os.Bundle
                            extras?.remove("mockLocation")
                            extras?.remove("isMock")
                            // ★ Add fake satellites count to bundle
                            val satCount = config.optInt("satellite_count", 10)
                            if (extras == null) {
                                val newBundle = android.os.Bundle()
                                newBundle.putInt("satellites", satCount)
                                XposedHelpers.setObjectField(loc, "mExtras", newBundle)
                            } else {
                                extras.putInt("satellites", satCount)
                            }
                        } catch (e: Throwable) {
                        }
                    }
                }
            }
            // 在 getLatitude/getLongitude/getAccuracy 时同步清字段，确保在实际读值前已抹除
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLatitude",
                fieldCleanHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLongitude",
                fieldCleanHook
            )

            // ★ 拦截 Settings.Secure.getInt("mock_location") — 部分ROM通过这个判断是否开了开发者模式模拟位置
            try {
                XposedHelpers.findAndHookMethod(
                    "android.provider.Settings\$Secure",
                    classLoader,
                    "getInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val key = param.args[1] as? String ?: return
                                if (key == "mock_location" || key == "allow_mock_location") {
                                    param.result = 0 // 0 = 关闭模拟位置（欺骗系统认为我们没开）
                                }
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ★ 拦截 getProvider：将 "mock" / "test" 提供者名隐藏，换成 "gps"
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val provider = param.result as? String ?: return
                            if (provider.contains("mock", ignoreCase = true) ||
                                provider.contains("test", ignoreCase = true) ||
                                provider.contains("fake", ignoreCase = true)
                            ) {
                                param.result = android.location.LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                })

            // ★ 拦截 LocationManager.getProviders() / getAllProviders()：移除 mock/test 提供者
            val providerListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<String> ?: return
                        val cleaned = list.filterNot {
                            it.contains("mock", ignoreCase = true) ||
                                    it.contains("test", ignoreCase = true) ||
                                    it.contains("fake", ignoreCase = true)
                        }.toMutableList()
                        if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                            cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                        param.result = cleaned
                    }
                }
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getProviders",
                    Boolean::class.javaPrimitiveType!!, providerListHook
                )
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getAllProviders",
                    providerListHook
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ★ NMEA-0183 报文劫持
            try {
                val addNmeaListenerHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 强制启动代理注入，无论当前active是true还是false。
                        // 真正的状态校验在代理注入器的Timer中进行。
                        
                        val args = param.args
                        for (i in args.indices) {
                            val arg = args[i] ?: continue
                            
                            // Check if it implements OnNmeaMessageListener
                            val isOnNmea = try {
                                val clazz = classLoader.loadClass("android.location.OnNmeaMessageListener")
                                clazz.isInstance(arg)
                            } catch (e: Exception) {
                                false
                            }
                            
                            // Check if it implements GpsStatus.NmeaListener
                            val isGpsNmea = try {
                                val clazz = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
                                clazz.isInstance(arg)
                            } catch (e: Exception) {
                                false
                            }
                            
                            if (isOnNmea) {
                                XposedBridge.log("[GPS_Spoofer] Detected addNmeaListener(OnNmeaMessageListener)! Starting active injector.")
                                args[i] = createOnNmeaMessageListenerProxy(arg, classLoader)
                            } else if (isGpsNmea) {
                                XposedBridge.log("[GPS_Spoofer] Detected addNmeaListener(GpsStatus.NmeaListener)! Starting active injector.")
                                args[i] = createGpsStatusNmeaListenerProxy(arg, classLoader)
                            }
                        }
                    }
                }
                val locationManagerClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
                XposedBridge.hookAllMethods(locationManagerClazz, "addNmeaListener", addNmeaListenerHook)
                
                // Hook removeNmeaListener
                XposedBridge.hookAllMethods(locationManagerClazz, "removeNmeaListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            if (arg != null) {
                                nmeaTimers.remove(arg)?.cancel()
                                XposedBridge.log("[GPS_Spoofer] removeNmeaListener called, canceled timer.")
                            }
                        }
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ── 高德SDK专属Hook(含抖动,与原生Location保持同步) ──
            // 使用findClassIfExists安全探测: 微信小程序子进程(:appbrand0等)不加载高德SDK,
            // 直接findAndHookMethod会抛出ClassNotFoundError,中断整个hookLocationAPIs执行流。
            // findClassIfExists在类不存在时返回null而非抛异常,可安全跳过。
            val amapLocClazz = XposedHelpers.findClassIfExists(
                "com.amap.api.location.AMapLocation", classLoader
            )

            if (amapLocClazz != null) {
                XposedBridge.log("[LocationSpoofer] AMapLocation class found, installing AMap hooks")
                val amapLocClass = "com.amap.api.location.AMapLocation"

                // AMap SDK 专属 Hook（含抖动，与原生Location保持同步）
                val amapHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            val jittered = getJitteredLocation(baseLat, baseLng)
                            when (param.method!!.name) {
                                "getLatitude" -> param.result = jittered.first
                                "getLongitude" -> param.result = jittered.second
                            }
                        }
                    }
                }
                try {
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getLatitude", amapHook)
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getLongitude", amapHook)
                } catch (e: Throwable) { /* AMap SDK方法签名不匹配则跳过 */ }

                // ★★★ 高德SDK深度反检测（strategy:500 的来源）
                // mockData JSON 就是 AMapLocation.getMockData() 的返回值，直接抹零
                val amapNullHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = null
                        }
                    }
                }
                val amapFalseHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = false
                        }
                    }
                }
                val amapZeroHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = 0
                        }
                    }
                }

                try {
                    // 1. getMockData() -> null（直接砍掉mockData字段的数据来源）
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getMockData", amapNullHook)
                    // 2. getMockFlag() / getMockType() -> 0
                    try { XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getMockFlag", amapZeroHook) } catch (e: Throwable) {}
                    try { XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getMockType", amapZeroHook) } catch (e: Throwable) {}
                    // 3. isMocked() -> false（AMap SDK 12.0+ 新接口）
                    try { XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "isMocked", amapFalseHook) } catch (e: Throwable) {}
                    // 4. getErrorCode() -> 0（非0表示定位失败）
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getErrorCode", amapZeroHook)
                    // 5. getLocationType() -> 动态保留网络定位类型，否则强制返回GPS类型（1）
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getLocationType",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) {
                                    val originalLocationType = param.result as? Int ?: 1
                                    // 高德地图SDK中：1代表GPS定位，5代表Wifi网络定位，6代表基站网络定位，12代表系统网络定位
                                    // 为了防止反作弊SDK检测到在室内（无卫星）却返回GPS定位的异常情况，
                                    // 我们需要保留原有的网络定位类型，使其显得更加真实自然。
                                    if (originalLocationType == 5 || originalLocationType == 6 || originalLocationType == 12) {
                                        param.result = originalLocationType
                                    } else {
                                        param.result = 1 // 默认强制设置为GPS定位
                                    }
                                }
                            }
                        })
                    // 6. getProvider() -> 动态保留网络提供者，否则强制返回"gps"
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getProvider",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) {
                                    val originalProvider = param.result as? String ?: "gps"
                                    // 同样为了真实性，如果原始提供者是网络（network）相关的，则保留原样
                                    if (originalProvider == "network" || originalProvider.contains("wifi", ignoreCase = true)) {
                                        param.result = originalProvider
                                    } else {
                                        param.result = "gps"
                                    }
                                }
                            }
                        })
                    // 7. 直接写底层 mock 相关字段（防反射读字段绕过 getter）
                    val setFieldHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val obj = param.thisObject ?: return
                                try { XposedHelpers.setObjectField(obj, "mockData", null) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "mockFlag", 0) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "mockType", 0) } catch (e: Throwable) {}
                                try { XposedHelpers.setBooleanField(obj, "isMocked", false) } catch (e: Throwable) {}
                                try { XposedHelpers.setBooleanField(obj, "mMock", false) } catch (e: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "errorCode", 0) } catch (e: Throwable) {}
                            }
                        }
                    }
                    XposedHelpers.findAndHookMethod(amapLocClass, classLoader, "getLatitude", setFieldHook)
                } catch (e: Throwable) {
                    XposedBridge.log(e)
                }

                // 8. AMapLocationQualityReport 质量报告也要清零
                val qualityClazz = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocationQualityReport", classLoader
                )
                if (qualityClazz != null) {
                    try { XposedHelpers.findAndHookMethod(qualityClazz, "getMockInfo", amapNullHook) } catch (e: Throwable) {}
                    try { XposedHelpers.findAndHookMethod(qualityClazz, "isMockLocation", amapFalseHook) } catch (e: Throwable) {}
                }

                // 9. setMockEnable(false) 让高德SDK禁用自身的 mock 校验流程
                val clientClazz = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocationClient", classLoader
                )
                if (clientClazz != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            clientClazz, "setMockEnable",
                            Boolean::class.javaPrimitiveType!!,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val config = readConfig()
                                    if (config != null && config.optBoolean("active", false)) {
                                        // 强制设为 true，让高德自己相信当前位置是真实的
                                        param.args[0] = true
                                    }
                                }
                            }
                        )
                    } catch (e: Throwable) {}
                }
            } else {
                XposedBridge.log("[LocationSpoofer] AMapLocation class not found in ${classLoader}, skipping AMap hooks")
            }

        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // ── 第三方地图SDK深度Hook(腾讯/百度) ──
        hookTencentSDK(classLoader)
        hookBaiduSDK(classLoader)
    }

    /**
     * 腾讯定位SDK深度Hook
     *
     * 架构分析:
     * TencentLocation在腾讯SDK中是一个**接口(interface)**,不是具体类。
     * 其方法签名为: public interface TencentLocation { double getLatitude(); ... }
     * Xposed的findAndHookMethod无法Hook接口方法(接口没有方法体),
     * 必须找到实现该接口的具体类并对其进行Hook。
     *
     * 腾讯SDK常见的实现类名(不同版本可能不同):
     * - com.tencent.map.geolocation.internal.TencentLocationImpl
     * - com.tencent.map.geolocation.TencentLocationImpl
     * - 部分版本使用ProGuard混淆后类名不固定
     *
     * 策略: 先尝试已知实现类名,若均不存在则降级为hookAllMethods扫描所有实现。
     *
     * 坐标系: GCJ-02(与高德相同)
     */
    private fun hookTencentSDK(classLoader: ClassLoader) {
        // 腾讯SDK已知的实现类名(按优先级排列)
        val implCandidates = listOf(
            "com.tencent.map.geolocation.internal.TencentLocationImpl",
            "com.tencent.map.geolocation.TencentLocationImpl",
            "com.tencent.tencentmap.mapsdk.map.model.TencentLocationImpl"
        )

        // 阶段1: 尝试直接Hook已知实现类
        var hooked = false
        for (implClass in implCandidates) {
            val clazz = XposedHelpers.findClassIfExists(implClass, classLoader)
            if (clazz != null) {
                hookTencentLocationClass(clazz, classLoader)
                hooked = true
                XposedBridge.log("[LocationSpoofer] TencentLocation impl found: $implClass")
                break
            }
        }

        // 阶段2: 若已知类名均不存在,尝试通过接口反向查找
        if (!hooked) {
            val interfaceClazz = XposedHelpers.findClassIfExists(
                "com.tencent.map.geolocation.TencentLocation", classLoader
            )
            if (interfaceClazz != null && interfaceClazz.isInterface) {
                // TencentLocation是接口,无法直接Hook。
                // 但腾讯SDK的定位结果最终会通过TencentLocationListener.onLocationChanged(TencentLocation)
                // 回调给App。我们Hook这个回调,在App拿到结果前篡改TencentLocation实例的字段。
                hookTencentLocationCallback(classLoader)
                hooked = true
            } else if (interfaceClazz != null) {
                // 某些版本中TencentLocation是具体类而非接口
                hookTencentLocationClass(interfaceClazz, classLoader)
                hooked = true
            }
        }

        if (!hooked) {
            XposedBridge.log("[LocationSpoofer] TencentLocation SDK not found, skipped")
        }
    }

    /**
     * 对TencentLocation的具体实现类进行方法Hook
     */
    private fun hookTencentLocationClass(clazz: Class<*>, classLoader: ClassLoader) {
        val coordHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val baseLat = config.optDouble("lat", 0.0)
                    val baseLng = config.optDouble("lng", 0.0)
                    val jittered = getJitteredLocation(baseLat, baseLng)
                    when (param.method!!.name) {
                        "getLatitude" -> param.result = jittered.first
                        "getLongitude" -> param.result = jittered.second
                    }
                }
            }
        }

        try {
            // hookAllMethods: 不管方法签名如何变化,只要方法名匹配就Hook
            XposedBridge.hookAllMethods(clazz, "getLatitude", coordHook)
            XposedBridge.hookAllMethods(clazz, "getLongitude", coordHook)
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] TencentLocation class hook failed: $e")
            return
        }

        // 动态保留网络定位提供者标识，避免室内强行返回GPS引发风控检测
        try {
            XposedBridge.hookAllMethods(clazz, "getProvider", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val originalProvider = param.result as? String ?: "gps"
                        // 腾讯地图SDK的定位提供者通常也是"gps"或者"network"
                        if (originalProvider == "network" || originalProvider.contains("wifi", ignoreCase = true)) {
                            param.result = originalProvider
                        } else {
                            param.result = "gps" // 默认强制修改为GPS定位
                        }
                    }
                }
            })
        } catch (e: Throwable) { /* 忽略 */ }

        // getAccuracy -> 抖动精度
        try {
            XposedBridge.hookAllMethods(clazz, "getAccuracy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = getJitteredAccuracy()
                    }
                }
            })
        } catch (e: Throwable) { /* 忽略 */ }

        // isMockGps -> 0(非模拟)
        try {
            XposedBridge.hookAllMethods(clazz, "isMockGps", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = 0
                    }
                }
            })
        } catch (e: Throwable) { /* 忽略 */ }

        XposedBridge.log("[LocationSpoofer] TencentLocation hooks installed on ${clazz.name}")
    }

    /**
     * 通过拦截TencentLocationListener回调来修改坐标
     *
     * 当无法直接Hook TencentLocation实现类时的降级方案:
     * Hook TencentLocationListener.onLocationChanged(TencentLocation, int, String)回调,
     * 在回调触发时通过反射修改TencentLocation实例的内部字段。
     */
    private fun hookTencentLocationCallback(classLoader: ClassLoader) {
        val listenerClass = XposedHelpers.findClassIfExists(
            "com.tencent.map.geolocation.TencentLocationListener", classLoader
        ) ?: return

        try {
            // hookAllMethods可以Hook接口的所有实现类中的方法
            XposedBridge.hookAllMethods(
                listenerClass, "onLocationChanged",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config == null || !config.optBoolean("active", false)) return
                        if (param.args.isEmpty()) return

                        val tencentLoc = param.args[0] ?: return
                        val baseLat = config.optDouble("lat", 0.0)
                        val baseLng = config.optDouble("lng", 0.0)
                        val jittered = getJitteredLocation(baseLat, baseLng)

                        // 通过反射直接写入TencentLocation实现类的经纬度字段
                        try { XposedHelpers.callMethod(tencentLoc, "setLatitude", jittered.first) } catch (e: Throwable) {
                            try { XposedHelpers.setDoubleField(tencentLoc, "latitude", jittered.first) } catch (e2: Throwable) {
                                try { XposedHelpers.setDoubleField(tencentLoc, "mLatitude", jittered.first) } catch (e3: Throwable) {
                                    try { XposedHelpers.setDoubleField(tencentLoc, "a", jittered.first) } catch (e4: Throwable) {}
                                }
                            }
                        }
                        try { XposedHelpers.callMethod(tencentLoc, "setLongitude", jittered.second) } catch (e: Throwable) {
                            try { XposedHelpers.setDoubleField(tencentLoc, "longitude", jittered.second) } catch (e2: Throwable) {
                                try { XposedHelpers.setDoubleField(tencentLoc, "mLongitude", jittered.second) } catch (e3: Throwable) {
                                    try { XposedHelpers.setDoubleField(tencentLoc, "b", jittered.second) } catch (e4: Throwable) {}
                                }
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[LocationSpoofer] TencentLocationListener callback hook installed")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] TencentLocationListener hook failed: $e")
        }
    }

    /**
     * 百度定位SDK深度Hook
     *
     * 百度定位SDK的核心定位回调对象为com.baidu.location.BDLocation。
     * 百度地图使用BD-09坐标系,这是在GCJ-02基础上施加二次偏移的专有坐标系。
     *
     * 关键区别:
     * - 高德/腾讯: 使用GCJ-02,直接返回config中的lat/lng
     * - 百度: 使用BD-09,必须调用gcj02ToBd09()转换后再返回
     *
     * 双重保险策略:
     * 1. 直接Hook BDLocation.getLatitude/getLongitude(方法级拦截)
     * 2. Hook BDAbstractLocationListener.onReceiveLocation回调(回调级拦截)
     * 两者互为补充,确保无论百度SDK内部架构如何变化,BD-09坐标都能正确注入。
     */
    private fun hookBaiduSDK(classLoader: ClassLoader) {
        val baiduLocClass = "com.baidu.location.BDLocation"

        // 安全探测: 当前进程是否加载了百度定位SDK
        val baiduClazz = XposedHelpers.findClassIfExists(baiduLocClass, classLoader)
        if (baiduClazz == null) {
            XposedBridge.log("[LocationSpoofer] BDLocation class not found, skipping")
            return
        }

        // ── 方案1: 直接Hook BDLocation的Getter方法 ──
        val baiduCoordHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    // 动态获取当前BDLocation期望的坐标系(App可通过LocationClientOption.setCoorType设置)
                    val coorType = try {
                        XposedHelpers.callMethod(param.thisObject!!, "getCoorType") as? String
                    } catch (e: Throwable) { null }

                    val targetLat: Double
                    val targetLng: Double
                    
                    when (coorType) {
                        "bd09ll", "bd09mc", "bd09" -> {
                            targetLat = config.optDouble("bd09_lat", 0.0)
                            targetLng = config.optDouble("bd09_lng", 0.0)
                        }
                        "wgs84" -> {
                            targetLat = config.optDouble("wgs84_lat", 0.0)
                            targetLng = config.optDouble("wgs84_lng", 0.0)
                        }
                        else -> { // gcj02 或默认(中国标准坐标系)
                            targetLat = config.optDouble("lat", 0.0)
                            targetLng = config.optDouble("lng", 0.0)
                        }
                    }

                    val jittered = getJitteredLocation(targetLat, targetLng)
                    when (param.method!!.name) {
                        "getLatitude" -> param.result = jittered.first
                        "getLongitude" -> param.result = jittered.second
                    }
                }
            }
        }

        try {
            // 使用hookAllMethods: BDLocation在不同版本中可能有多个getLatitude重载
            XposedBridge.hookAllMethods(baiduClazz, "getLatitude", baiduCoordHook)
            XposedBridge.hookAllMethods(baiduClazz, "getLongitude", baiduCoordHook)

            // getLocType -> 动态保留网络定位类型，适配百度SDK的161和601类型，否则强制返回GPS定位(61)
            XposedBridge.hookAllMethods(baiduClazz, "getLocType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val originalLocationType = param.result as? Int ?: 61
                        // 百度地图SDK中：61代表GPS定位结果，161代表网络定位结果，601代表某些特殊或离线网络定位结果
                        // 为了避免在室内环境（如没有GPS信号）强行返回61导致应用侧判定为作弊，
                        // 我们直接放行原有的网络定位类型，由于经纬度已经被修改，这样显得更加真实自然。
                        if (originalLocationType == 161 || originalLocationType == 601) {
                            param.result = originalLocationType
                        } else {
                            param.result = 61 // 默认强制修改为GPS定位（61）
                        }
                    }
                }
            })

            // getRadius(精度) -> 与全局抖动精度同步
            try {
                XposedBridge.hookAllMethods(baiduClazz, "getRadius", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = getJitteredAccuracy()
                        }
                    }
                })
            } catch (e: Throwable) { /* 忽略 */ }

            // getMockGps -> 0(非模拟)
            try {
                XposedBridge.hookAllMethods(baiduClazz, "getMockGps", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = 0
                        }
                    }
                })
            } catch (e: Throwable) { /* 忽略 */ }

            // getSatelliteNumber -> 12-18颗
            try {
                XposedBridge.hookAllMethods(baiduClazz, "getSatelliteNumber", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = 12 + rng.nextInt(7)
                        }
                    }
                })
            } catch (e: Throwable) { /* 忽略 */ }

            XposedBridge.log("[LocationSpoofer] BDLocation method hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] BDLocation method hook failed: $e")
        }

        // ── 方案2(补充): Hook百度定位回调,在App接收BDLocation前修改其内部字段 ──
        // BDAbstractLocationListener是百度SDK 7.0+推荐的回调基类
        val listenerCandidates = listOf(
            "com.baidu.location.BDAbstractLocationListener",
            "com.baidu.location.BDLocationListener"
        )
        for (listenerClassName in listenerCandidates) {
            val listenerClazz = XposedHelpers.findClassIfExists(listenerClassName, classLoader) ?: continue
            try {
                XposedBridge.hookAllMethods(
                    listenerClazz, "onReceiveLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig() ?: return
                            if (!config.optBoolean("active", false)) return
                            if (param.args.isEmpty()) return

                            val bdLoc = param.args[0] ?: return
                            
                            val coorType = try {
                                XposedHelpers.callMethod(bdLoc, "getCoorType") as? String
                            } catch (e: Throwable) { null }

                            val targetLat: Double
                            val targetLng: Double
                            when (coorType) {
                                "bd09ll", "bd09mc", "bd09" -> {
                                    targetLat = config.optDouble("bd09_lat", 0.0)
                                    targetLng = config.optDouble("bd09_lng", 0.0)
                                }
                                "wgs84" -> {
                                    targetLat = config.optDouble("wgs84_lat", 0.0)
                                    targetLng = config.optDouble("wgs84_lng", 0.0)
                                }
                                else -> { // gcj02 或默认(中国标准坐标系)
                                    targetLat = config.optDouble("lat", 0.0)
                                    targetLng = config.optDouble("lng", 0.0)
                                }
                            }
                            
                            val jittered = getJitteredLocation(targetLat, targetLng)

                            // 通过反射直接写入BDLocation实例的经纬度
                            try { XposedHelpers.callMethod(bdLoc, "setLatitude", jittered.first) } catch (e: Throwable) {
                                try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", jittered.first) } catch (e2: Throwable) {}
                            }
                            try { XposedHelpers.callMethod(bdLoc, "setLongitude", jittered.second) } catch (e: Throwable) {
                                try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", jittered.second) } catch (e2: Throwable) {}
                            }
                            // 动态设置定位类型：保留网络定位类型（161和601），其余强制覆盖为GPS定位（61）
                            try {
                                val currentLocationType = XposedHelpers.callMethod(bdLoc, "getLocType") as? Int ?: 61
                                // 如果当前回调原本就是网络定位，那么我们不修改类型，只替换了上面的经纬度坐标
                                if (currentLocationType != 161 && currentLocationType != 601) {
                                    XposedHelpers.callMethod(bdLoc, "setLocType", 61)
                                }
                            } catch (e: Throwable) { /* 忽略反射调用可能出现的异常 */ }
                        }
                    }
                )
                XposedBridge.log("[LocationSpoofer] $listenerClassName callback hook installed")
            } catch (e: Throwable) { /* 忽略 */ }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Wi-Fi 环境伪造 — 覆盖 WifiInfo / WifiManager / NetworkInfo
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookWifiEnvironment(classLoader: ClassLoader) {

        // ── 1. WifiInfo getter Hook ──
        val wifiInfoHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return
                val mockWifi = config.optBoolean("mock_wifi", true)
                val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                val connectedWifi = if (isConnected) wifiObj?.optJSONObject("connectedWifi") else null
                
                when (param.method!!.name) {
                    "getBSSID" -> param.result =
                        connectedWifi?.optString("bssid") ?: "02:00:00:00:00:00"
                    "getMacAddress" -> param.result =
                        connectedWifi?.optString("macAddress") ?: "02:00:00:00:00:00"
                    "getSSID" -> {
                        val ssidVal = connectedWifi?.optString("ssid", "") ?: ""
                        val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") "" else ssidVal
                        param.result = if (finalSsid.isEmpty()) "<unknown ssid>" else "\"$finalSsid\""
                    }
                    "getNetworkId" -> param.result =
                        connectedWifi?.optInt("networkId", -1) ?: -1
                    "getRssi" -> param.result =
                        connectedWifi?.optInt("level", -127) ?: -127
                    "getLinkSpeed" -> param.result =
                        connectedWifi?.optInt("linkSpeed", -1) ?: -1
                    "getFrequency" -> param.result =
                        connectedWifi?.optInt("frequency", -1) ?: -1
                    "getIpAddress" -> param.result =
                        if (isConnected) 0x6401A8C0 else 0 // 192.168.1.100 小端序
                }
            }
        }

        try {
            val wifiInfoMethods = listOf(
                "getBSSID", "getMacAddress", "getSSID", "getNetworkId",
                "getRssi", "getLinkSpeed", "getFrequency", "getIpAddress"
            )
            for (method in wifiInfoMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        "android.net.wifi.WifiInfo", classLoader, method, wifiInfoHook
                    )
                } catch (e: Throwable) { /* 部分方法在低版本可能不存在 */ }
            }
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 1b. WifiInfo.getSupplicantState() ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo", classLoader, "getSupplicantState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                        try {
                            val enumClass = XposedHelpers.findClass(
                                "android.net.wifi.SupplicantState", classLoader
                            )
                            val stateStr = if (mockWifi && isConnected) "COMPLETED" else "DISCONNECTED"
                            param.result = enumClass.getField(stateStr).get(null)
                        } catch (e: Throwable) { /* 忽略 */ }
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 2. Wi-Fi 扫描结果伪造 (getScanResults) ──
        val realCapabilities = listOf(
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
            "[WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][ESS]",
            "[WPA2-PSK-CCMP][ESS][WPS]",
            "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]",
            "[RSN-PSK-CCMP][ESS]",
            "[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]",
            "[ESS]",
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]",
            "[WPA2-SAE-CCMP][RSN-SAE-CCMP][ESS]",
            "[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS]"
        )

        val wifiScanHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return
                val fakeList = java.util.ArrayList<Any>()
                val mockWifi = config.optBoolean("mock_wifi", true)
                val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                if (wifiObj != null) {
                    try {
                        val scanResultClass =
                            XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                        val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                        
                        fun addFakeScanResult(wifi: org.json.JSONObject) {
                            val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                            val ssidVal = wifi.optString("ssid", "")
                            val bssidVal = wifi.optString("bssid", "")
                            val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") {
                                "WIFI_${bssidVal.takeLast(5).replace(":", "")}"
                            } else {
                                ssidVal
                            }
                            XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                            XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                            val level = wifi.optInt("level", -65)
                            XposedHelpers.setIntField(fakeScanResult, "level", level)
                            XposedHelpers.setIntField(
                                fakeScanResult, "frequency",
                                wifi.optInt("frequency", 2412)
                            )
                            XposedHelpers.setObjectField(
                                fakeScanResult, "capabilities",
                                wifi.optString("capabilities", realCapabilities.random())
                            )
                            try {
                                val offsetNanos = (rng.nextInt(200_000) * 1000L)
                                XposedHelpers.setLongField(
                                    fakeScanResult, "timestamp",
                                    (baseTimestamp - offsetNanos) / 1000
                                )
                            } catch (e: Throwable) {}
                            fakeList.add(fakeScanResult)
                        }

                        val isConnected = wifiObj.optBoolean("isConnected", false)
                        val connectedWifi = if (isConnected) wifiObj.optJSONObject("connectedWifi") else null
                        if (connectedWifi != null) {
                            addFakeScanResult(connectedWifi)
                        }

                        val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                        if (nearbyArray != null) {
                            for (i in 0 until nearbyArray.length()) {
                                val wifi = nearbyArray.getJSONObject(i)
                                addFakeScanResult(wifi)
                            }
                        }
                    } catch (e: Throwable) { /* 忽略 */ }
                }
                param.result = fakeList
            }
        }

        // ── 3. WifiManager 整体 Hook ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getScanResults", wifiScanHook
            )

            // getWifiState()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getWifiState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = config.optJSONObject("wifi_json")
                        val hasWifiData = wifiObj != null && (wifiObj.has("connectedWifi") || wifiObj.optJSONArray("nearbyWifi")?.length() ?: 0 > 0)
                        if (mockWifi) {
                            param.result = if (hasWifiData) 3 else 1 // 3 is WIFI_STATE_ENABLED, 1 is disabled
                        }
                    }
                })

            // isWifiEnabled()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = config.optJSONObject("wifi_json")
                        val hasWifiData = wifiObj != null && (wifiObj.has("connectedWifi") || wifiObj.optJSONArray("nearbyWifi")?.length() ?: 0 > 0)
                        if (mockWifi) {
                            param.result = hasWifiData
                        }
                    }
                })

            // getConnectionInfo() — 返回伪造的 WifiInfo 对象
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                        val connectedWifi = if (isConnected) wifiObj?.optJSONObject("connectedWifi") else null
                        
                        if (isConnected && connectedWifi != null) {
                            try {
                                val fakeWifiInfo: Any
                                val ssidVal = connectedWifi.optString("ssid", "")
                                val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") "HOME_WIFI" else ssidVal
                                val bssidVal = connectedWifi.optString("bssid", "02:00:00:00:00:00")
                                val freqVal = connectedWifi.optInt("frequency", 2412)
                                val macAddressVal = connectedWifi.optString("macAddress", bssidVal)
                                val linkSpeedVal = connectedWifi.optInt("linkSpeed", 65)
                                val standardVal = connectedWifi.optInt("wifiStandard", 6)
                                val levelVal = connectedWifi.optInt("level", -65)
                                val networkIdVal = connectedWifi.optInt("networkId", 1)
                                
                                // Try Builder (Android 12+)
                                var builtWithBuilder = false
                                var builtInfo: Any? = null
                                try {
                                    val builderClass = XposedHelpers.findClass("android.net.wifi.WifiInfo\$Builder", classLoader)
                                    val builder = XposedHelpers.newInstance(builderClass)
                                    XposedHelpers.callMethod(builder, "setSsid", finalSsid)
                                    XposedHelpers.callMethod(builder, "setBssid", bssidVal)
                                    XposedHelpers.callMethod(builder, "setRssi", levelVal)
                                    XposedHelpers.callMethod(builder, "setFrequency", freqVal)
                                    XposedHelpers.callMethod(builder, "setLinkSpeed", linkSpeedVal)
                                    builtInfo = XposedHelpers.callMethod(builder, "build")
                                    builtWithBuilder = true
                                } catch (e: Throwable) {}
                                
                                fakeWifiInfo = if (builtWithBuilder) {
                                    builtInfo!!
                                } else {
                                    val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                                    val info = XposedHelpers.newInstance(wifiInfoClass)
                                    try { XposedHelpers.setObjectField(info, "mSSID", finalSsid) } catch(e:Throwable){}
                                    try { XposedHelpers.setObjectField(info, "mBSSID", bssidVal) } catch(e:Throwable){}
                                    try { XposedHelpers.setIntField(info, "mRssi", levelVal) } catch(e:Throwable){}
                                    try { XposedHelpers.setIntField(info, "mFrequency", freqVal) } catch(e:Throwable){}
                                    try { XposedHelpers.setIntField(info, "mLinkSpeed", linkSpeedVal) } catch(e:Throwable){}
                                    try { XposedHelpers.setIntField(info, "mNetworkId", networkIdVal) } catch(e:Throwable){}
                                    info
                                }
                                
                                param.result = fakeWifiInfo
                            } catch (e: Throwable) { /* 忽略 */ }
                        } else {
                            try {
                                val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                                val fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                                try { XposedHelpers.setObjectField(fakeWifiInfo, "mBSSID", "02:00:00:00:00:00") } catch(e:Throwable){}
                                try { XposedHelpers.setObjectField(fakeWifiInfo, "mMacAddress", "02:00:00:00:00:00") } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", -1) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mRssi", -127) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mLinkSpeed", -1) } catch(e:Throwable){}
                                try { XposedHelpers.setIntField(fakeWifiInfo, "mFrequency", -1) } catch(e:Throwable){}
                                param.result = fakeWifiInfo
                            } catch (e: Throwable) {}
                        }
                    }
                })

            // getConfiguredNetworks()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConfiguredNetworks",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        param.result = java.util.ArrayList<Any>()
                    }
                })

            // getDhcpInfo()
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getDhcpInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        try {
                            val dhcpClass = XposedHelpers.findClass("android.net.DhcpInfo", classLoader)
                            val dhcp = XposedHelpers.newInstance(dhcpClass)
                            XposedHelpers.setIntField(dhcp, "ipAddress", 0x6401A8C0.toInt())
                            XposedHelpers.setIntField(dhcp, "gateway", 0x0101A8C0)     // 192.168.1.1
                            XposedHelpers.setIntField(dhcp, "netmask", 0x00FFFFFF)     // 255.255.255.0
                            XposedHelpers.setIntField(dhcp, "dns1", 0x0101A8C0)        // 192.168.1.1
                            XposedHelpers.setIntField(dhcp, "dns2", 0x08080808)        // 8.8.8.8
                            XposedHelpers.setIntField(dhcp, "serverAddress", 0x0101A8C0)
                            param.result = dhcp
                        } catch (e: Throwable) { /* 忽略 */ }
                    }
                })
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 4. NetworkInfo.getExtraInfo() ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", classLoader, "getExtraInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                        val connectedWifi = if (isConnected) wifiObj?.optJSONObject("connectedWifi") else null
                        if (connectedWifi != null) {
                            param.result = "\"${connectedWifi.optString("ssid", "HOME_WIFI")}\""
                        } else {
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 5. WifiScanner Hook ──
        try {
            val wifiScannerClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiScanner", classLoader)
            if (wifiScannerClass != null) {
                val scannerHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        
                        param.result = null
                        val listener = param.args.lastOrNull() ?: return
                        val mockWifi = config.optBoolean("mock_wifi", true)
                        val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                        if (wifiObj != null) {
                            try {
                                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                                val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                                val fakeList = java.util.ArrayList<Any>()
                                
                                fun addFakeScanResult(wifi: org.json.JSONObject) {
                                    val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                                    val ssidVal = wifi.optString("ssid", "")
                                    val bssidVal = wifi.optString("bssid", "")
                                    val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") {
                                        "WIFI_${bssidVal.takeLast(5).replace(":", "")}"
                                    } else {
                                        ssidVal
                                    }
                                    XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                                    XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                                    val level = wifi.optInt("level", -65)
                                    XposedHelpers.setIntField(fakeScanResult, "level", level)
                                    XposedHelpers.setIntField(fakeScanResult, "frequency", wifi.optInt("frequency", 2412))
                                    XposedHelpers.setObjectField(fakeScanResult, "capabilities", wifi.optString("capabilities", "[WPA2-PSK-CCMP][ESS]"))
                                    try {
                                        val offsetNanos = (rng.nextInt(200_000) * 1000L)
                                        XposedHelpers.setLongField(fakeScanResult, "timestamp", (baseTimestamp - offsetNanos) / 1000)
                                    } catch (e: Throwable) {}
                                    fakeList.add(fakeScanResult)
                                }

                                val isConnected = wifiObj.optBoolean("isConnected", false)
                                val connectedWifi = if (isConnected) wifiObj.optJSONObject("connectedWifi") else null
                                if (connectedWifi != null) {
                                    addFakeScanResult(connectedWifi)
                                }

                                val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                                if (nearbyArray != null) {
                                    for (i in 0 until nearbyArray.length()) {
                                        val wifi = nearbyArray.getJSONObject(i)
                                        addFakeScanResult(wifi)
                                    }
                                }

                                if (fakeList.isNotEmpty()) {
                                    val scanResultArray = java.lang.reflect.Array.newInstance(scanResultClass, fakeList.size)
                                    for (i in 0 until fakeList.size) {
                                        java.lang.reflect.Array.set(scanResultArray, i, fakeList[i])
                                    }
                                    
                                    // 构造 ScanData 对象（包含 ScanResult 数组）
                                    val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                    val fakeScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, scanResultArray)
                                    val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                    java.lang.reflect.Array.set(fakeScanDataArray, 0, fakeScanData)
                                    
                                    // 主动回调 Listener，把假数据塞回去
                                    XposedHelpers.callMethod(listener, "onResults", fakeScanDataArray)
                                } else {
                                    val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                    val emptyScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, java.lang.reflect.Array.newInstance(scanResultClass, 0))
                                    val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                    java.lang.reflect.Array.set(fakeScanDataArray, 0, emptyScanData)
                                    XposedHelpers.callMethod(listener, "onResults", fakeScanDataArray)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("[LocationSpoofer] WifiScanner 伪造失败: $e")
                            }
                        } else {
                            try {
                                val scanDataClass = XposedHelpers.findClass("android.net.wifi.WifiScanner\$ScanData", classLoader)
                                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                                val emptyScanData = XposedHelpers.newInstance(scanDataClass, 0, 0, java.lang.reflect.Array.newInstance(scanResultClass, 0))
                                val fakeScanDataArray = java.lang.reflect.Array.newInstance(scanDataClass, 1)
                                java.lang.reflect.Array.set(fakeScanDataArray, 0, emptyScanData)
                                XposedHelpers.callMethod(listener, "onResults", fakeScanDataArray)
                            } catch (e: Throwable) { /* 忽略 */ }
                        }
                    }
                }
                
                // startScan(ScanSettings, ScanListener) 和重载
                XposedBridge.hookAllMethods(wifiScannerClass, "startScan", scannerHook)
            }
        } catch (e: Throwable) { XposedBridge.log(e) }

        XposedBridge.log("[LocationSpoofer] Wi-Fi environment hooks installed")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 基站/蜂窝网络环境伪造 — 覆盖 TelephonyManager / PhoneStateListener
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookCellEnvironment(classLoader: ClassLoader) {
        XposedBridge.logOpenCellId("Installing cell hooks classLoader=$classLoader")

        // ── 1. 基站信息伪造（CellLocation / AllCellInfo / NeighboringCellInfo）──
        val cellHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method!!.name
                val config = readConfig()
                if (config == null) {
                    XposedBridge.logOpenCellIdEvery("$methodName:config-null", "$methodName skipped: config=null", 30_000L)
                    return
                }
                if (!config.optBoolean("active", false)) {
                    XposedBridge.logOpenCellIdEvery("$methodName:inactive", "$methodName skipped: active=false", 30_000L)
                    return
                }
                val lat = config.optDouble("lat", 0.0)
                val lng = config.optDouble("lng", 0.0)
                val mockCellForLog = config.optBoolean("mock_cell", true)
                val cellCountForLog = config.optJSONArray("cell_json")?.length() ?: 0
                XposedBridge.logOpenCellIdEvery(
                    "$methodName:called:$mockCellForLog:$cellCountForLog",
                    "$methodName called active=true mockCell=$mockCellForLog cellJsonCount=$cellCountForLog lat=$lat lng=$lng"
                )

                when (methodName) {
                    "getCellLocation" -> {
                        try {
                            val mockCell = config.optBoolean("mock_cell", true)
                            if (mockCell) {
                                val gsmCellLocationClass = XposedHelpers.findClass(
                                    "android.telephony.gsm.GsmCellLocation", classLoader
                                )
                                val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
                                val cellArray = config.optJSONArray("cell_json")
                                val lac: Int
                                val cid: Int
                                if (cellArray != null && cellArray.length() > 0) {
                                    val cell = cellArray.getJSONObject(0)
                                    lac = cellAreaCode(cell, fallbackAreaCode(lat, lng))
                                    cid = cellIdentityCode(cell, fallbackCellIdentity(lat, lng))
                                } else {
                                    lac = fallbackAreaCode(lat, lng)
                                    cid = fallbackCellIdentity(lat, lng)
                                }
                                XposedHelpers.callMethod(fakeLocation, "setLacAndCid", lac, cid)
                                XposedBridge.logOpenCellId("getCellLocation returning GsmCellLocation lac=$lac cid=$cid")
                                param.result = fakeLocation
                            } else {
                                XposedBridge.logOpenCellId("getCellLocation returning null because mock_cell=false")
                                param.result = null
                            }
                        } catch (e: Throwable) {
                            XposedBridge.logOpenCellId("getCellLocation failed: $e")
                            param.result = null
                        }
                    }

                    "getAllCellInfo" -> {
                        try {
                            if (config.optBoolean("mock_cell", true)) {
                                val fakeCells = buildFakeCellInfoList(classLoader, lat, lng, config)
                                XposedBridge.logOpenCellIdEvery(
                                    "getAllCellInfo:return:${fakeCells.size}",
                                    "getAllCellInfo returning fakeCells=${fakeCells.size}"
                                )
                                param.result = fakeCells
                            } else {
                                XposedBridge.logOpenCellId("getAllCellInfo returning empty because mock_cell=false")
                                param.result = java.util.ArrayList<Any>()
                            }
                        } catch (e: Throwable) {
                            XposedBridge.logOpenCellId("getAllCellInfo build failed: $e")
                            param.result = java.util.ArrayList<Any>()
                        }
                    }

                    "getNeighboringCellInfo" -> {
                        XposedBridge.logOpenCellId("getNeighboringCellInfo returning empty list")
                        param.result = java.util.ArrayList<Any>()
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getAllCellInfo", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getCellLocation", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getNeighboringCellInfo", cellHook)
            XposedBridge.logOpenCellId("Installed TelephonyManager getAllCellInfo/getCellLocation/getNeighboringCellInfo hooks")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install basic TelephonyManager cell hooks failed: $e")
        }

        // ── 2. TelephonyManager 元数据 Hook ──
        // 防止 MCC/MNC/运营商名称/网络类型泄漏真实地理位置
        // 高德用 getNetworkOperator() 验证基站数据是否与 GPS 位置地理一致
        val telephonyMetaHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val methodName = param.method!!.name
                val config = readConfig()
                if (config == null) {
                    XposedBridge.logOpenCellIdEvery("$methodName:config-null", "$methodName skipped: config=null", 30_000L)
                    return
                }
                if (!config.optBoolean("active", false)) {
                    XposedBridge.logOpenCellIdEvery("$methodName:inactive", "$methodName skipped: active=false", 30_000L)
                    return
                }
                val mockCell = config.optBoolean("mock_cell", true)
                val cellArray = if (mockCell) config.optJSONArray("cell_json") else null
                XposedBridge.logOpenCellIdEvery(
                    "$methodName:called:$mockCell:${cellArray?.length() ?: 0}",
                    "$methodName called mockCell=$mockCell cellJsonCount=${cellArray?.length() ?: 0}"
                )
                when (methodName) {
                    "getNetworkOperator" -> {
                        if (cellArray != null && cellArray.length() > 0) {
                            val cell = cellArray.getJSONObject(0)
                            val mcc = positiveJsonInt(cell, "mcc", default = 460)
                            val mnc = positiveJsonInt(cell, "mnc", "net", default = 0)
                            val operator = String.format(java.util.Locale.US, "%d%02d", mcc, mnc)
                            XposedBridge.logOpenCellIdEvery(
                                "getNetworkOperator:return:$operator",
                                "getNetworkOperator returning $operator"
                            )
                            param.result = operator
                        } else if (!mockCell) {
                            param.result = ""
                        }
                    }
                    "getNetworkOperatorName" -> {
                        if (cellArray != null && cellArray.length() > 0) {
                            val mnc = positiveJsonInt(cellArray.getJSONObject(0), "mnc", "net", default = 0)
                            param.result = when (mnc) {
                                0, 2, 7 -> "中国移动"
                                1, 6, 9 -> "中国联通"
                                3, 5, 11 -> "中国电信"
                                else -> "中国移动"
                            }
                            XposedBridge.logOpenCellIdEvery(
                                "getNetworkOperatorName:return:${param.result}",
                                "getNetworkOperatorName returning ${param.result}"
                            )
                        } else if (!mockCell) {
                            param.result = ""
                        }
                    }
                    "getSimOperator" -> { /* 保留真实值 */ }
                    "getSimOperatorName" -> { /* 保留真实值 */ }
                    "getNetworkType" -> param.result = if (mockCell) 13 else 0
                    "getDataNetworkType" -> param.result = if (mockCell) 13 else 0
                    "getPhoneType" -> param.result = 1      // PHONE_TYPE_GSM
                    "getServiceState", "getServiceStateForSlot" -> {
                        if (mockCell) buildFakeServiceState(classLoader, cellArray)?.let {
                            XposedBridge.logOpenCellIdEvery(
                                "$methodName:return-service-state",
                                "$methodName returning fake ServiceState"
                            )
                            param.result = it
                        }
                    }
                    "getSignalStrength" -> {
                        if (mockCell) buildFakeSignalStrength(classLoader, config)?.let {
                            XposedBridge.logOpenCellIdEvery(
                                "getSignalStrength:return-signal-strength",
                                "getSignalStrength returning fake SignalStrength"
                            )
                            param.result = it
                        }
                    }
                }
            }
        }

        val telephonyMetaMethods = listOf(
            "getNetworkOperator", "getNetworkOperatorName",
            "getNetworkType", "getDataNetworkType", "getPhoneType",
            "getServiceState", "getServiceStateForSlot", "getSignalStrength"
        )
        for (method in telephonyMetaMethods) {
            try {
                XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                    method,
                    telephonyMetaHook
                )
                XposedBridge.logOpenCellId("Installed TelephonyManager.$method hook")
            } catch (e: Throwable) {
                XposedBridge.logOpenCellId("Install TelephonyManager.$method hook failed: $e")
            }
        }

        // ── 3. PhoneStateListener 回调拦截 ──
        // 防止应用通过 TelephonyManager.listen() 的 LISTEN_CELL_INFO 回调
        // 绕过 getAllCellInfo() 的 Hook 获取真实基站数据
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", classLoader, "listen",
                "android.telephony.PhoneStateListener",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config == null) {
                            XposedBridge.logOpenCellIdEvery("listen:config-null", "TelephonyManager.listen skipped: config=null", 30_000L)
                            return
                        }
                        if (!config.optBoolean("active", false)) {
                            XposedBridge.logOpenCellIdEvery("listen:inactive", "TelephonyManager.listen skipped: active=false", 30_000L)
                            return
                        }
                        val listener = param.args[0] ?: return
                        val originalEvents = param.args[1] as Int
                        val lat = config.optDouble("lat", 0.0)
                        val lng = config.optDouble("lng", 0.0)
                        val mockCell = config.optBoolean("mock_cell", true)
                        val cellJsonCount = config.optJSONArray("cell_json")?.length() ?: 0
                        val needsCellInfo = (originalEvents and 0x400) != 0
                        val needsCellLocation = (originalEvents and 0x10) != 0
                        val needsServiceState = (originalEvents and 0x1) != 0
                        val needsSignalStrength = (originalEvents and 0x100) != 0
                        if (!needsCellInfo && !needsCellLocation && !needsServiceState && !needsSignalStrength) {
                            return
                        }
                        XposedBridge.logOpenCellIdEvery(
                            "listen:called:${listener.javaClass.name}:$originalEvents:$mockCell:$cellJsonCount",
                            "TelephonyManager.listen called listener=${listener.javaClass.name} events=0x${originalEvents.toString(16)} mockCell=$mockCell cellJsonCount=$cellJsonCount"
                        )
                        val fakeCells by lazy {
                            if (mockCell) {
                                buildFakeCellInfoList(classLoader, lat, lng, config)
                            } else {
                                java.util.ArrayList<Any>()
                            }
                        }
                        if ((originalEvents and 0x10) != 0) {
                            try {
                                if (mockCell) {
                                    val gsmCellLocationClass = XposedHelpers.findClass(
                                        "android.telephony.gsm.GsmCellLocation", classLoader
                                    )
                                    val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
                                    val cellArray = config.optJSONArray("cell_json")
                                    val lac: Int
                                    val cid: Int
                                    if (cellArray != null && cellArray.length() > 0) {
                                        val cell = cellArray.getJSONObject(0)
                                        lac = cellAreaCode(cell, fallbackAreaCode(lat, lng))
                                        cid = cellIdentityCode(cell, fallbackCellIdentity(lat, lng))
                                    } else {
                                        lac = fallbackAreaCode(lat, lng)
                                        cid = fallbackCellIdentity(lat, lng)
                                    }
                                    XposedHelpers.callMethod(
                                        fakeLocation,
                                        "setLacAndCid",
                                        lac,
                                        cid
                                    )
                                    XposedHelpers.callMethod(listener, "onCellLocationChanged", fakeLocation)
                                    XposedBridge.logOpenCellIdEvery(
                                        "listen:onCellLocationChanged:$lac:$cid",
                                        "listen dispatched onCellLocationChanged lac=$lac cid=$cid"
                                    )
                                }
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellId("listen onCellLocationChanged failed: $e")
                            }
                        }
                        if ((originalEvents and 0x400) != 0) {
                            try {
                                XposedHelpers.callMethod(listener, "onCellInfoChanged", fakeCells)
                                XposedBridge.logOpenCellIdEvery(
                                    "listen:onCellInfoChanged:${fakeCells.size}",
                                    "listen dispatched onCellInfoChanged fakeCells=${fakeCells.size}"
                                )
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellId("listen onCellInfoChanged failed: $e")
                            }
                        }
                        if ((originalEvents and 0x1) != 0) {
                            try {
                                buildFakeServiceState(classLoader, config.optJSONArray("cell_json"))
                                    ?.let { XposedHelpers.callMethod(listener, "onServiceStateChanged", it) }
                                XposedBridge.logOpenCellIdEvery("listen:onServiceStateChanged", "listen dispatched onServiceStateChanged")
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellId("listen onServiceStateChanged failed: $e")
                            }
                        }
                        if ((originalEvents and 0x100) != 0) {
                            try {
                                buildFakeSignalStrength(classLoader, config)
                                    ?.let { XposedHelpers.callMethod(listener, "onSignalStrengthsChanged", it) }
                                XposedBridge.logOpenCellIdEvery("listen:onSignalStrengthsChanged", "listen dispatched onSignalStrengthsChanged")
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellId("listen onSignalStrengthsChanged failed: $e")
                            }
                        }
                        var events = originalEvents
                        // 移除会泄漏真实蜂窝环境的标志位
                        // 这样系统就不会将真实的基站变更回调给应用
                        events = events and 0x1.inv()    // LISTEN_SERVICE_STATE
                        events = events and 0x10.inv()   // LISTEN_CELL_LOCATION
                        events = events and 0x100.inv()  // LISTEN_SIGNAL_STRENGTHS
                        events = events and 0x400.inv()  // LISTEN_CELL_INFO
                        param.args[1] = events
                        XposedBridge.logOpenCellIdEvery(
                            "listen:sanitized:$originalEvents:$events",
                            "TelephonyManager.listen sanitized events=0x${events.toString(16)}"
                        )
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.listen hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.listen hook failed: $e")
        }

// ── 4. TelephonyManager.requestCellInfoUpdate 异步刷新拦截 (Android 10+) ──
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                "requestCellInfoUpdate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config == null) {
                            XposedBridge.logOpenCellIdEvery("requestCellInfoUpdate:config-null", "requestCellInfoUpdate skipped: config=null", 30_000L)
                            return
                        }
                        if (!config.optBoolean("active", false)) {
                            XposedBridge.logOpenCellIdEvery("requestCellInfoUpdate:inactive", "requestCellInfoUpdate skipped: active=false", 30_000L)
                            return
                        }
                        
                        // 阻断真实请求
                        param.result = null
                        
                        val executor = param.args[0] as? java.util.concurrent.Executor ?: return
                        val callback = param.args[1] ?: return
                        XposedBridge.logOpenCellIdEvery(
                            "requestCellInfoUpdate:called:${callback.javaClass.name}",
                            "requestCellInfoUpdate called callback=${callback.javaClass.name} args=${param.args.size}"
                        )
                        
                        val mockCell = config.optBoolean("mock_cell", true)
                        val lat = config.optDouble("lat", 0.0)
                        val lng = config.optDouble("lng", 0.0)
                        
                        val fakeCells = if (mockCell) {
                            buildFakeCellInfoList(classLoader, lat, lng, config)
                        } else {
                            java.util.ArrayList<Any>()
                        }
                        
                        // 异步回调
                        executor.execute {
                            try {
                                XposedHelpers.callMethod(callback, "onCellInfo", fakeCells)
                                XposedBridge.logOpenCellId("requestCellInfoUpdate dispatched onCellInfo fakeCells=${fakeCells.size}")
                            } catch (e: Throwable) {
                                XposedBridge.logOpenCellId("requestCellInfoUpdate onCellInfo failed: $e")
                            }
                        }
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.requestCellInfoUpdate hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.requestCellInfoUpdate hook failed: $e")
        }

        // ── 5. TelephonyCallback 拦截 (Android 12+ / API 31+) ──
        // registerTelephonyCallback 替代了旧版 listen()，
        // 通过 TelephonyCallback.CellInfoListener 接收基站变化。
        // 需要 hook 注册过程，对每个 callback 实例的 onCellInfoChanged 进行拦截。
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                "registerTelephonyCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config == null) {
                            XposedBridge.logOpenCellIdEvery("registerTelephonyCallback:config-null", "registerTelephonyCallback skipped: config=null", 30_000L)
                            return
                        }
                        if (!config.optBoolean("active", false)) {
                            XposedBridge.logOpenCellIdEvery("registerTelephonyCallback:inactive", "registerTelephonyCallback skipped: active=false", 30_000L)
                            return
                        }

                        // 找到 TelephonyCallback 实例参数
                        val callback = param.args.firstOrNull { arg ->
                            arg != null && arg.javaClass.interfaces.any { iface ->
                                iface.name.contains("TelephonyCallback")
                            } || (arg != null && runCatching {
                                val base = XposedHelpers.findClass(
                                    "android.telephony.TelephonyCallback", classLoader
                                )
                                base.isInstance(arg)
                            }.getOrElse { false })
                        }
                        if (callback == null) {
                            XposedBridge.logOpenCellId("registerTelephonyCallback called but callback not found args=${param.args.map { it?.javaClass?.name }}")
                            return
                        }

                        val callbackClass = callback.javaClass
                        XposedBridge.logOpenCellIdEvery(
                            "registerTelephonyCallback:called:${callbackClass.name}",
                            "registerTelephonyCallback called callback=${callbackClass.name} interfaces=${callbackClass.interfaces.joinToString { it.name }}"
                        )

                        if (isTelephonyCallbackListener(classLoader, callback, "CellInfoListener")) {
                            XposedBridge.logOpenCellIdEvery(
                                "registerTelephonyCallback:CellInfoListener:${callbackClass.name}",
                                "registerTelephonyCallback installing CellInfoListener hook on ${callbackClass.name}",
                                60_000L
                            )
                            XposedBridge.hookAllMethods(
                                callbackClass,
                                "onCellInfoChanged",
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val freshConfig = readConfig() ?: return
                                        if (!freshConfig.optBoolean("active", false)) return
                                        val lat = freshConfig.optDouble("lat", 0.0)
                                        val lng = freshConfig.optDouble("lng", 0.0)
                                        val fakeCells = buildFakeCellInfoList(classLoader, lat, lng, freshConfig)
                                        param.args[0] = fakeCells
                                        XposedBridge.logOpenCellId("TelephonyCallback.onCellInfoChanged injected fakeCells=${fakeCells.size}")
                                    }
                                }
                            )
                        }

                        if (isTelephonyCallbackListener(classLoader, callback, "ServiceStateListener")) {
                            XposedBridge.logOpenCellIdEvery(
                                "registerTelephonyCallback:ServiceStateListener:${callbackClass.name}",
                                "registerTelephonyCallback installing ServiceStateListener hook on ${callbackClass.name}",
                                60_000L
                            )
                            XposedBridge.hookAllMethods(
                                callbackClass,
                                "onServiceStateChanged",
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val freshConfig = readConfig() ?: return
                                        if (!freshConfig.optBoolean("active", false)) return
                                        buildFakeServiceState(classLoader, freshConfig.optJSONArray("cell_json"))
                                            ?.let {
                                                param.args[0] = it
                                                XposedBridge.logOpenCellId("TelephonyCallback.onServiceStateChanged injected fake ServiceState")
                                            }
                                    }
                                }
                            )
                        }

                        if (isTelephonyCallbackListener(classLoader, callback, "SignalStrengthsListener")) {
                            XposedBridge.logOpenCellIdEvery(
                                "registerTelephonyCallback:SignalStrengthsListener:${callbackClass.name}",
                                "registerTelephonyCallback installing SignalStrengthsListener hook on ${callbackClass.name}",
                                60_000L
                            )
                            XposedBridge.hookAllMethods(
                                callbackClass,
                                "onSignalStrengthsChanged",
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val freshConfig = readConfig() ?: return
                                        if (!freshConfig.optBoolean("active", false)) return
                                        buildFakeSignalStrength(classLoader, freshConfig)
                                            ?.let {
                                                param.args[0] = it
                                                XposedBridge.logOpenCellId("TelephonyCallback.onSignalStrengthsChanged injected fake SignalStrength")
                                            }
                                    }
                                }
                            )
                        }
                    }
                }
            )
            XposedBridge.logOpenCellId("Installed TelephonyManager.registerTelephonyCallback hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.registerTelephonyCallback hook failed: $e")
        }

        XposedBridge.logOpenCellId("Cell environment hooks installed")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 网络连接层伪造 — 覆盖 ConnectivityManager / NetworkCapabilities / NetworkInterface
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookConnectivityLayer(classLoader: ClassLoader) {
        val buildFakeNetworkInfo = fun(): Any? {
            try {
                val networkInfoClass = XposedHelpers.findClass("android.net.NetworkInfo", classLoader)
                val fakeNetworkInfo = XposedHelpers.newInstance(networkInfoClass, 1, 0, "WIFI", "")
                XposedHelpers.callMethod(fakeNetworkInfo, "setIsAvailable", true)
                try {
                    val stateEnum = XposedHelpers.findClass("android.net.NetworkInfo\$State", classLoader)
                    XposedHelpers.setObjectField(fakeNetworkInfo, "mState", stateEnum.getField("CONNECTED").get(null))
                } catch (e: Throwable) { /* 忽略 */ }
                try {
                    val detailedStateEnum = XposedHelpers.findClass("android.net.NetworkInfo\$DetailedState", classLoader)
                    XposedHelpers.setObjectField(fakeNetworkInfo, "mDetailedState", detailedStateEnum.getField("CONNECTED").get(null))
                } catch (e: Throwable) { /* 忽略 */ }
                return fakeNetworkInfo
            } catch (e: Throwable) { return null }
        }

        // ── 1. 强制让系统以为连着 Wi-Fi ──
        val networkInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return
                if (config.optBoolean("mock_wifi", true)) {
                    val wifiObj = config.optJSONObject("wifi_json")
                    val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                    val hasWifiData = isConnected && wifiObj?.optJSONObject("connectedWifi") != null
                    if (hasWifiData) {
                        val fakeInfo = buildFakeNetworkInfo()
                        if (fakeInfo != null) {
                            param.result = fakeInfo
                        }
                    } else {
                        // 如果用户要求模拟 Wi-Fi，但实际上数据库里没有 Wi-Fi 数据
                        // 我们需要向系统返回 Wi-Fi 断开的状态
                        val currentInfo = param.result
                        if (currentInfo != null) {
                            try {
                                val type = XposedHelpers.callMethod(currentInfo, "getType") as Int
                                if (type == 1) { // TYPE_WIFI
                                    val stateEnum = XposedHelpers.findClass("android.net.NetworkInfo\$State", classLoader)
                                    XposedHelpers.setObjectField(currentInfo, "mState", stateEnum.getField("DISCONNECTED").get(null))
                                    XposedHelpers.callMethod(currentInfo, "setIsAvailable", false)
                                    param.result = currentInfo
                                }
                            } catch (e: Throwable) {}
                        }
                    }
                }
            }
        }

        try { XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", classLoader, "getActiveNetworkInfo", networkInfoHook) } catch (e: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", classLoader, "getNetworkInfo", Int::class.javaPrimitiveType, networkInfoHook) } catch (e: Throwable) {}

        // ── 2. NetworkCapabilities 包含 WifiInfo ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.ConnectivityManager", classLoader,
                "getNetworkCapabilities",
                "android.net.Network",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        if (!config.optBoolean("mock_wifi", true)) return
                        
                        val nc = param.result ?: return
                        try {
                            val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                            val fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                            val wifiObj = config.optJSONObject("wifi_json")
                            val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                            val firstWifi = if (isConnected) wifiObj?.optJSONObject("connectedWifi") else null
                            if (firstWifi != null) {
                                val fakeWifiInfo: Any
                                val ssidVal = firstWifi.optString("ssid", "")
                                val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") "HOME_WIFI" else ssidVal
                                val bssidVal = firstWifi.optString("bssid", "02:00:00:00:00:00")
                                val freqVal = firstWifi.optInt("frequency", 2412)
                                val macAddressVal = firstWifi.optString("macAddress", bssidVal)
                                val linkSpeedVal = firstWifi.optInt("linkSpeed", 65)
                                val standardVal = firstWifi.optInt("wifiStandard", 6)
                                
                                var builtWithBuilder = false
                                var builtInfo: Any? = null
                                try {
                                    val builderClass = XposedHelpers.findClass("android.net.wifi.WifiInfo\$Builder", classLoader)
                                    val builder = XposedHelpers.newInstance(builderClass)
                                    XposedHelpers.callMethod(builder, "setBssid", bssidVal)
                                    try { XposedHelpers.callMethod(builder, "setMacAddress", macAddressVal) } catch(e:Throwable){}
                                    try { XposedHelpers.callMethod(builder, "setSsid", finalSsid.toByteArray(Charsets.UTF_8)) } catch(e:Throwable){}
                                    try { XposedHelpers.callMethod(builder, "setNetworkId", 1) } catch(e:Throwable){}
                                    builtInfo = XposedHelpers.callMethod(builder, "build")
                                    
                                    builtInfo?.let { info ->
                                        try { XposedHelpers.setIntField(info, "mFrequency", freqVal) } catch(e:Throwable){}
                                        try { XposedHelpers.setIntField(info, "mLinkSpeed", linkSpeedVal) } catch(e:Throwable){}
                                        try { XposedHelpers.setObjectField(info, "mMacAddress", macAddressVal) } catch(e:Throwable){}
                                        try { XposedHelpers.setIntField(info, "mWifiStandard", standardVal) } catch(e:Throwable){}
                                    }
                                    
                                    builtWithBuilder = true
                                } catch (e: Throwable) {}

                                if (builtWithBuilder && builtInfo != null) {
                                    fakeWifiInfo = builtInfo
                                } else {
                                    val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                                    fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                                    try { XposedHelpers.callMethod(fakeWifiInfo, "setBSSID", bssidVal) } catch (e: Throwable) {
                                        try { XposedHelpers.setObjectField(fakeWifiInfo, "mBSSID", bssidVal) } catch (e2: Throwable) {}
                                        try { XposedHelpers.setObjectField(fakeWifiInfo, "mBssid", bssidVal) } catch (e2: Throwable) {}
                                    }
                                    try { XposedHelpers.callMethod(fakeWifiInfo, "setMacAddress", macAddressVal) } catch (e: Throwable) {
                                        try { XposedHelpers.setObjectField(fakeWifiInfo, "mMacAddress", macAddressVal) } catch (e2: Throwable) {}
                                    }
                                    try {
                                        val wifiSsidClass = XposedHelpers.findClass("android.net.wifi.WifiSsid", classLoader)
                                        val createMethod = XposedHelpers.findMethodExact(wifiSsidClass, "createFromAsciiEncoded", String::class.java)
                                        val wifiSsid = createMethod.invoke(null, finalSsid)
                                        XposedHelpers.setObjectField(fakeWifiInfo, "mWifiSsid", wifiSsid)
                                    } catch (e: Throwable) {
                                        try { XposedHelpers.setObjectField(fakeWifiInfo, "mSSID", "\"$finalSsid\"") } catch (e2: Throwable) {}
                                    }
                                    try { XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", 1) } catch (e: Throwable) {}
                                    try { XposedHelpers.setIntField(fakeWifiInfo, "mFrequency", freqVal) } catch (e: Throwable) {}
                                    try { XposedHelpers.setIntField(fakeWifiInfo, "mLinkSpeed", linkSpeedVal) } catch (e: Throwable) {}
                                    try { XposedHelpers.setIntField(fakeWifiInfo, "mWifiStandard", standardVal) } catch (e: Throwable) {}
                                }
                                
                                // Inject TRANSPORT_WIFI (1) into NetworkCapabilities so DevCheck sees it as Wi-Fi
                                try {
                                    val field = nc.javaClass.getDeclaredField("mTransportTypes")
                                    field.isAccessible = true
                                    val currentTypes = field.getLong(nc)
                                    field.setLong(nc, currentTypes or (1L shl 1))
                                } catch (e: Throwable) {
                                    try {
                                        XposedHelpers.callMethod(nc, "addTransportType", 1)
                                    } catch (e2: Throwable) {}
                                }
                                
                                XposedBridge.log("[LocationSpoofer] fakeWifiInfo build result: " + fakeWifiInfo.toString())
                                XposedHelpers.setObjectField(nc, "mTransportInfo", fakeWifiInfo)
                            } else {
                                // 库中无 Wi-Fi 数据，移除 TransportInfo 以伪造非 Wi-Fi 环境
                                try { XposedHelpers.setObjectField(nc, "mTransportInfo", null) } catch (e: Throwable) {}
                                XposedBridge.log("[LocationSpoofer] fakeWifiInfo: No wifi data, removed TransportInfo")
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("[LocationSpoofer] fakeWifiInfo error: " + e.message)
                        }
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 3. NetworkInterface.getNetworkInterfaces() ──
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface", classLoader, "getNetworkInterfaces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        val result = param.result as? java.util.Enumeration<*> ?: return
                        val filtered = java.util.Collections.list(result).filter { iface ->
                            val name = try {
                                (iface as java.net.NetworkInterface).name
                            } catch (e: Throwable) { "" }
                            !name.startsWith("wlan") && !name.startsWith("p2p") && !name.startsWith("swlan")
                        }
                        param.result = java.util.Collections.enumeration(filtered)
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        XposedBridge.log("[LocationSpoofer] Connectivity layer hooks installed")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 蓝牙 BLE 扫描拦截 — 防止通过 iBeacon / Eddystone 信标定位
    // ══════════════════════════════════════════════════════════════════════════
    private fun hookBluetoothLE(classLoader: ClassLoader) {

        // ── BLE 扫描结果伪造的核心逻辑（复用于不同 startScan 重载）──
        val buildAndDeliverBleResults = fun(config: JSONObject, callbackObj: Any, cl: ClassLoader) {
            if (!config.optBoolean("mock_bluetooth", true)) return
            try {
                val bluetoothArray = config.optJSONArray("bluetooth_json")
                if (bluetoothArray != null && bluetoothArray.length() > 0) {
                    val results = java.util.ArrayList<Any>()
                    val scanResultClass = XposedHelpers.findClass("android.bluetooth.le.ScanResult", cl)
                    val bluetoothDeviceClass = XposedHelpers.findClass("android.bluetooth.BluetoothDevice", cl)
                    val scanRecordClass = XposedHelpers.findClass("android.bluetooth.le.ScanRecord", cl)

                    for (i in 0 until bluetoothArray.length()) {
                        try {
                            val obj = bluetoothArray.getJSONObject(i)
                            val address = obj.optString("address", "00:11:22:33:44:55")
                            val rssi = obj.optInt("rssi", -60)
                            val hexRecord = obj.optString("scanRecordHex", "")

                            // 1. 构造 BluetoothDevice
                            val device = XposedHelpers.newInstance(bluetoothDeviceClass, address)

                            // 2. 构造 ScanRecord
                            var scanRecord: Any? = null
                            if (hexRecord.isNotEmpty()) {
                                try {
                                    val bytes = hexStringToByteArray(hexRecord)
                                    scanRecord = XposedHelpers.callStaticMethod(scanRecordClass, "parseFromBytes", bytes)
                                } catch (e: Throwable) { /* 忽略 */ }
                            }

                            // 3. 构造 ScanResult（兼容新旧构造器）
                            val timestampNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            var scanResultObj: Any? = null
                            try {
                                // Android 8.0+ 构造器
                                scanResultObj = XposedHelpers.newInstance(
                                    scanResultClass, device,
                                    0x001B, 1, 0, 255, 127, rssi, 0, scanRecord, timestampNanos
                                )
                            } catch (e: Throwable) {
                                try {
                                    // 旧版本构造器
                                    scanResultObj = XposedHelpers.newInstance(
                                        scanResultClass, device, scanRecord, rssi, timestampNanos
                                    )
                                } catch (e2: Throwable) { /* 忽略 */ }
                            }

                            if (scanResultObj != null) {
                                results.add(scanResultObj)
                                try { XposedHelpers.callMethod(callbackObj, "onScanResult", 1, scanResultObj) } catch (e: Throwable) {}
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("[LocationSpoofer] 构建虚拟BLE失败: $e")
                        }
                    }

                    // 批量触发回调
                    if (results.isNotEmpty()) {
                        try { XposedHelpers.callMethod(callbackObj, "onBatchScanResults", results) } catch (e: Throwable) {}
                    }
                }
            } catch (e: Throwable) { XposedBridge.log(e) }
            Unit
        }

        // ── 1. startScan(List<ScanFilter>, ScanSettings, ScanCallback) — 3参数重载 ──
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.le.BluetoothLeScanner", classLoader, "startScan",
                java.util.List::class.java,
                android.bluetooth.le.ScanSettings::class.java,
                android.bluetooth.le.ScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        param.result = null // 短路原始扫描
                        val callback = param.args[2] ?: return
                        buildAndDeliverBleResults(config, callback, classLoader)
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log(e) }

        // ── 2. startScan(ScanCallback) — 1参数重载 ──
        // 部分 App（如微信）使用无 filter 的简化版 startScan
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.le.BluetoothLeScanner", classLoader, "startScan",
                android.bluetooth.le.ScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        param.result = null
                        val callback = param.args[0] ?: return
                        buildAndDeliverBleResults(config, callback, classLoader)
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log(e) }


        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter", classLoader, "isEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        if (!config.optBoolean("mock_bluetooth", true)) {
                            param.result = false
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter", classLoader, "getState",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        if (!config.optBoolean("mock_bluetooth", true)) {
                            param.result = 10 // STATE_OFF
                        }
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 3. BluetoothAdapter.getBondedDevices() → 空集合 ──
        // 防止通过已配对蓝牙设备列表进行指纹识别
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter", classLoader, "getBondedDevices",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        param.result = java.util.HashSet<Any>()
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 4. BluetoothAdapter.startDiscovery() → false ──
        // 阻止经典蓝牙扫描发现周围真实设备
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter", classLoader, "startDiscovery",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        param.result = false
                    }
                }
            )
        } catch (e: Throwable) { /* 忽略 */ }

        // ── 5. 老接口 BluetoothAdapter.startLeScan（Android 4.x）──
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter", classLoader, "startLeScan",
                android.bluetooth.BluetoothAdapter.LeScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        // 老接口不具备很好的伪造性，直接返回启动失败
                        param.result = false
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log(e) }

        XposedBridge.log("[LocationSpoofer] Bluetooth LE hooks installed")
    }
    
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * 通过反射+Parcel机制构造CellInfoLte对象列表
     *
     * CellInfoLte/CellIdentityLte等类的构造器在Android各版本中签名不同,
     * 直接new会因API版本差异崩溃。通过反射调用内部构造器并设置字段值,
     * 兼容Android 7.0~14。
     *
     * 参数生成策略:
     * - MCC=460(中国), MNC=01(中国移动)或11(中国电信): 使用中国运营商真实前缀
     * - TAC(Tracking Area Code): 基于经纬度hash生成,范围1-65534
     * - CI(Cell Identity): 基于坐标生成,范围1-268435455(28bit)
     * - 生成2-3个基站: 第一个为服务小区(isRegistered=true),其余为邻区
     *
     * @param classLoader 目标App的ClassLoader
     * @param lat 目标纬度(GCJ-02)
     * @param lng 目标经度(GCJ-02)
     * @return 包含2-3个CellInfoLte对象的ArrayList
     */
    private fun buildFakeCellInfoList(
        classLoader: ClassLoader, lat: Double, lng: Double, config: org.json.JSONObject?
    ): java.util.ArrayList<Any> {
        val result = java.util.ArrayList<Any>()
        
        val cellArray = config?.optJSONArray("cell_json")
        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:called:${cellArray?.length() ?: 0}",
            "buildFakeCellInfoList called cellJsonCount=${cellArray?.length() ?: 0}"
        )
        if (cellArray != null && cellArray.length() > 0) {
            var hasLteOrNr = false
            var gsmCount = 0
            var wcdmaCount = 0
            var lteCount = 0
            var nrCount = 0
            var firstSummary: String? = null
            for (i in 0 until cellArray.length()) {
                try {
                    val obj = cellArray.getJSONObject(i)
                    val type = normalizeCellType(obj.optString("type", obj.optString("radio", "LTE")))
                    val isRegistered = obj.optBoolean("isRegistered", i == 0)
                    if (type == "LTE" || type == "NR") {
                        hasLteOrNr = true
                    }
                    when (type) {
                        "GSM" -> gsmCount++
                        "WCDMA", "UMTS" -> wcdmaCount++
                        "NR" -> nrCount++
                        else -> lteCount++
                    }
                    
                    val mcc = positiveJsonInt(obj, "mcc", default = 460)
                    val mnc = positiveJsonInt(obj, "mnc", "net", default = 0)
                    val tacOrLac = cellAreaCode(obj, 10000)
                    val ciOrCid = cellIdentityCode(obj, 100000)
                    val pci = positiveJsonInt(obj, "pci", "psc", default = (ciOrCid % 504).coerceIn(0, 503))
                    val dbm = signalDbm(obj, i)
                    if (firstSummary == null) {
                        firstSummary = "$type/$mcc-$mnc area=$tacOrLac identity=$ciOrCid dbm=$dbm registered=$isRegistered"
                    }
                    if (VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId(
                            "buildFakeCellInfoList source[$i] radio=${obj.optString("radio", "")} type=$type registered=$isRegistered mcc=$mcc mnc=$mnc area=$tacOrLac identity=$ciOrCid pci=$pci dbm=$dbm"
                        )
                    }
                    
                    // 1. 寻找并构造具体的 CellInfo 派生类
                    val cellInfoClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellInfoGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellInfoWcdma", classLoader)
                        "NR" -> try { XposedHelpers.findClass("android.telephony.CellInfoNr", classLoader) } catch (e: Throwable) { XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader) }
                        else -> XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader)
                    }
                    val cellInfo = XposedHelpers.newInstance(cellInfoClass)
                    
                    // 设置注册标志（Android 9 及以下用 mRegistered；Android 10+ 用 mCellConnectionStatus）
                    // CONNECTION_NONE=0, CONNECTION_PRIMARY_SERVING=1, CONNECTION_SECONDARY_SERVING=2
                    val connectionStatus = if (isRegistered) 1 else 0
                    try { XposedHelpers.setBooleanField(cellInfo, "mRegistered", isRegistered) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(cellInfo, "mCellConnectionStatus", connectionStatus) } catch (_: Throwable) {}
                    try { if (isRegistered) XposedHelpers.callMethod(cellInfo, "setRegistered", true) } catch (_: Throwable) {}

                    try { XposedHelpers.setLongField(cellInfo, "mTimeStamp", android.os.SystemClock.elapsedRealtimeNanos()) } catch (e: Throwable) {}

                    // 2. 构造 CellIdentity（尝试多种有参构造器，避免 final 字段反射问题）
                    val cellIdentityClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellIdentityWcdma", classLoader)
                        "NR" -> try { XposedHelpers.findClass("android.telephony.CellIdentityNr", classLoader) } catch (e: Throwable) { XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader) }
                        else -> XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
                    }
                    val mccStr = mcc.toString()
                    val mncStr = if (mnc < 10) "0$mnc" else mnc.toString()
                    val cellIdentity = constructCellIdentityByType(
                        type, cellIdentityClass, mcc, mccStr, mnc, mncStr, tacOrLac, ciOrCid, pci
                    )
                    if (VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId("Built $type identity: MCC=$mcc MNC=$mnc TAC/LAC=$tacOrLac CI/CID=$ciOrCid PCI=$pci -> ${cellIdentity.javaClass.simpleName}")
                    }

                    // 验证注入是否成功（如果 getCi()/getLac() 返回 Integer.MAX_VALUE 说明注入失败）
                    try {
                        val verifyMethod = when (type) {
                            "LTE"        -> "getCi"
                            "GSM"        -> "getLac"
                            "WCDMA", "UMTS" -> "getLac"
                            "NR"         -> "getPci"
                            else         -> "getCi"
                        }
                        val readBack = XposedHelpers.callMethod(cellIdentity, verifyMethod) as? Int
                        if (readBack == Int.MAX_VALUE || readBack == -1) {
                            XposedBridge.logOpenCellId("WARNING: $type.$verifyMethod()=$readBack, identity injection may have failed")
                        } else if (VERBOSE_CELL_BUILD_LOGS) {
                            XposedBridge.logOpenCellId("VERIFY OK: $type.$verifyMethod()=$readBack")
                        }
                    } catch (_: Throwable) {}

                    // 将 CellIdentity 存入 CellInfo (兼容新老版本字段名)
                    val identityField = when (type) {
                        "GSM" -> "mCellIdentityGsm"
                        "WCDMA", "UMTS" -> "mCellIdentityWcdma"
                        "NR" -> "mCellIdentityNr"
                        else -> "mCellIdentityLte"
                    }
                    try { XposedHelpers.setObjectField(cellInfo, identityField, cellIdentity) } catch (e: Throwable) {}
                    try { XposedHelpers.setObjectField(cellInfo, "mCellIdentity", cellIdentity) } catch (e: Throwable) {}

                    // 3. 构造并配置对应的 CellSignalStrength
                    val cssClass = when (type) {
                        "GSM" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", classLoader)
                        "WCDMA", "UMTS" -> XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", classLoader)
                        "NR" -> try { XposedHelpers.findClass("android.telephony.CellSignalStrengthNr", classLoader) } catch (e: Throwable) { XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader) }
                        else -> XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
                    }
                    val css = XposedHelpers.newInstance(cssClass)
                    
                    when (type) {
                        "GSM" -> {
                            val asu = ((dbm + 113) / 2).coerceIn(0, 31)
                            try { XposedHelpers.setIntField(css, "mRssi", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mGsmSignalStrength", asu) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", dbm) } catch (e: Throwable) {}
                        }
                        "WCDMA", "UMTS" -> {
                            val asu = (dbm + 116).coerceIn(0, 95)
                            try { XposedHelpers.setIntField(css, "mRscp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", dbm) } catch (e: Throwable) {}
                        }
                        "NR" -> {
                            try { XposedHelpers.setIntField(css, "mCsiRsrp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mCsiRsrq", -10) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mCsiSinr", 15) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSsRsrp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSsRsrq", -10) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSsSinr", 15) } catch (e: Throwable) {}
                        }
                        else -> { // LTE
                            try { XposedHelpers.setIntField(css, "mRsrp", dbm) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mRsrq", -10) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mRssnr", 300) } catch (e: Throwable) {}
                            try { XposedHelpers.setIntField(css, "mSignalStrength", dbm + 113) } catch (e: Throwable) {}
                        }
                    }

                    // 将 CellSignalStrength 存入 CellInfo (兼容新老版本字段名)
                    val cssField = when (type) {
                        "GSM" -> "mCellSignalStrengthGsm"
                        "WCDMA", "UMTS" -> "mCellSignalStrengthWcdma"
                        "NR" -> "mCellSignalStrengthNr"
                        else -> "mCellSignalStrengthLte"
                    }
                    try { XposedHelpers.setObjectField(cellInfo, cssField, css) } catch (e: Throwable) {}
                    try { XposedHelpers.setObjectField(cellInfo, "mCellSignalStrength", css) } catch (e: Throwable) {}

                    result.add(cellInfo)
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("buildFakeCellInfoList failed to parse/build cell_json[$i]", e)
                }
            }
            if (result.isNotEmpty() && !hasLteOrNr) {
                try {
                    val seed = org.json.JSONObject(cellArray.getJSONObject(0).toString()).apply {
                        put("type", "LTE")
                        put("radio", "LTE")
                        put("isRegistered", true)
                    }
                    val syntheticConfig = org.json.JSONObject().put(
                        "cell_json",
                        org.json.JSONArray().put(seed)
                    )
                    val syntheticLte = buildFakeCellInfoList(classLoader, lat, lng, syntheticConfig)
                    if (syntheticLte.isNotEmpty()) {
                        result.add(0, syntheticLte[0])
                        XposedBridge.logOpenCellIdEvery(
                            "buildFakeCellInfoList:synthetic-lte",
                            "OpenCellID data has no LTE/NR cells; prepended synthetic LTE primary cell for 4G-only readers",
                            60_000L
                        )
                    }
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("Failed to prepend synthetic LTE primary cell", e)
                }
            }
            XposedBridge.logOpenCellIdEvery(
                "buildFakeCellInfoList:return:$lteCount:$nrCount:$wcdmaCount:$gsmCount:${result.size}:$firstSummary",
                "buildFakeCellInfoList returning ${result.size} cells from cell_json types=LTE:$lteCount NR:$nrCount WCDMA:$wcdmaCount GSM:$gsmCount first=$firstSummary"
            )
            return result
        }

        val coordSeed = ((lat * 1e5).toLong() xor (lng * 1e5).toLong())
        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:fallback:$coordSeed",
            "buildFakeCellInfoList has no cell_json; generating deterministic LTE fallback cells seed=$coordSeed",
            60_000L
        )

        // 中国运营商MCC/MNC组合
        val operators = listOf(
            Pair(460, 0),  // 中国移动
            Pair(460, 1),  // 中国联通
            Pair(460, 11)  // 中国电信
        )

        // 生成2-3个基站(1个服务小区+1-2个邻区)
        val cellCount = 2 + (coordSeed and 1).toInt()
        for (i in 0 until cellCount) {
            try {
                val mcc = operators[i % operators.size].first
                val mnc = operators[i % operators.size].second
                // 每个基站的TAC/CI基于坐标+索引偏移,确保同一位置的多个基站参数不同但确定
                val tac = (10000 + ((coordSeed + i * 7919) and 0xFFFF).toInt() % 50000)
                    .coerceIn(1, 65534)
                val ci = (100000 + (((coordSeed shr 8) + i * 104729) and 0xFFFFFF).toInt() % 900000)
                    .coerceIn(1, 268435455)
                val pci = (coordSeed + i * 31).toInt() and 0x1FF // 物理小区ID, 0-503

                // 方案A: 通过反射CellIdentityLte构造器(Android 9+有多参数版本)
                val cellIdentityLteClass = XposedHelpers.findClass(
                    "android.telephony.CellIdentityLte", classLoader
                )
                val cellInfoLteClass = XposedHelpers.findClass(
                    "android.telephony.CellInfoLte", classLoader
                )

                val cellInfo = XposedHelpers.newInstance(cellInfoLteClass)

                // 设置isRegistered: 第一个为服务小区
                try {
                    XposedHelpers.setBooleanField(cellInfo, "mRegistered", i == 0)
                } catch (e: Throwable) {
                    try {
                        XposedHelpers.callMethod(cellInfo, "setRegistered", i == 0)
                    } catch (e2: Throwable) { /* 忽略 */ }
                }

                // 设置时间戳
                try {
                    XposedHelpers.setLongField(
                        cellInfo, "mTimeStamp",
                        android.os.SystemClock.elapsedRealtimeNanos()
                    )
                } catch (e: Throwable) { /* 忽略 */ }

                // 构造CellIdentityLte并注入字段
                val cellIdentity = try {
                    // Android 9+ 构造器: (int ci, int pci, int tac, int earfcn, ...mcc, mnc...)
                    XposedHelpers.newInstance(
                        cellIdentityLteClass,
                        mcc, mnc, ci, pci, tac
                    )
                } catch (e: Throwable) {
                    // 降级: 用空构造器+反射写字段
                    val identity = XposedHelpers.newInstance(cellIdentityLteClass)
                    try { XposedHelpers.setIntField(identity, "mMcc", mcc) } catch (e2: Throwable) {}
                    try { XposedHelpers.setIntField(identity, "mMnc", mnc) } catch (e2: Throwable) {}
                    try { XposedHelpers.setObjectField(identity, "mMccStr", mcc.toString()) } catch (e2: Throwable) {}
                    try { XposedHelpers.setObjectField(identity, "mMncStr", if (mnc < 10) "0$mnc" else mnc.toString()) } catch (e2: Throwable) {}
                    try { XposedHelpers.setIntField(identity, "mCi", ci) } catch (e2: Throwable) {}
                    try { XposedHelpers.setIntField(identity, "mPci", pci) } catch (e2: Throwable) {}
                    try { XposedHelpers.setIntField(identity, "mTac", tac) } catch (e2: Throwable) {}
                    identity
                }

                // 将CellIdentityLte写入CellInfoLte
                try {
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityLte", cellIdentity)
                } catch (e: Throwable) { /* 忽略 */ }

                // 构造CellSignalStrengthLte
                try {
                    val cssClass = XposedHelpers.findClass(
                        "android.telephony.CellSignalStrengthLte", classLoader
                    )
                    val css = XposedHelpers.newInstance(cssClass)
                    // RSRP: -140~-44 dBm, 典型值-80~-100
                    val rsrp = -80 - rng.nextInt(20)
                    // RSRQ: -20~-3 dB
                    val rsrq = -10 - rng.nextInt(7)
                    // RSSI: -113~-51 dBm
                    val rssi = -70 - rng.nextInt(20)
                    try { XposedHelpers.setIntField(css, "mRsrp", rsrp) } catch (e2: Throwable) {}
                    try { XposedHelpers.setIntField(css, "mRsrq", rsrq) } catch (e2: Throwable) {}
                    try { XposedHelpers.setIntField(css, "mSignalStrength", rssi) } catch (e2: Throwable) {}
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthLte", css)
                } catch (e: Throwable) { /* 忽略 */ }

                result.add(cellInfo)
                if (VERBOSE_CELL_BUILD_LOGS) {
                    XposedBridge.logOpenCellId("Fallback LTE cell[$i] built mcc=$mcc mnc=$mnc tac=$tac ci=$ci pci=$pci registered=${i == 0}")
                }
            } catch (e: Throwable) {
                XposedBridge.logOpenCellId("Fallback LTE cell[$i] build failed", e)
            }
        }
        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:fallback-return:${result.size}",
            "buildFakeCellInfoList returning ${result.size} fallback LTE cells",
            60_000L
        )
        return result
    }

    private fun normalizeCellType(rawType: String): String {
        return when (rawType.uppercase(java.util.Locale.US)) {
            "GSM" -> "GSM"
            "UMTS", "WCDMA" -> "WCDMA"
            "NR", "NR5G", "5G" -> "NR"
            else -> "LTE"
        }
    }

    private fun cellAreaCode(cell: org.json.JSONObject, default: Int): Int =
        positiveJsonInt(cell, "tac", "lac", "area", default = default)

    private fun cellIdentityCode(cell: org.json.JSONObject, default: Int): Int =
        positiveJsonInt(cell, "ci", "cid", "cellid", "cell", default = default)

    private fun fallbackAreaCode(lat: Double, lng: Double): Int {
        val coordSeed = ((lat * 1e5).toLong() xor (lng * 1e5).toLong())
        return (10000 + (coordSeed and 0xFFFF).toInt() % 50000).coerceIn(1, 65534)
    }

    private fun fallbackCellIdentity(lat: Double, lng: Double): Int {
        val coordSeed = ((lat * 1e5).toLong() xor (lng * 1e5).toLong())
        return (100000 + ((coordSeed shr 8) and 0xFFFFFF).toInt() % 900000)
            .coerceIn(1, 268435455)
    }

    private fun positiveJsonInt(cell: org.json.JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = cell.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = cell.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    private fun signalDbm(cell: org.json.JSONObject, index: Int): Int {
        val direct = cell.optInt("dbm", Int.MIN_VALUE)
        if (direct in -140..-40) return direct

        val average = cell.optInt("averageSignalStrength", Int.MIN_VALUE)
        if (average in -140..-40) return average

        val signal = cell.optInt("signal", Int.MIN_VALUE)
        if (signal in -140..-40) return signal

        return (-70 - index * 3).coerceAtLeast(-110)
    }

    private fun firstCell(config: org.json.JSONObject?): org.json.JSONObject? {
        val cells = config?.optJSONArray("cell_json") ?: return null
        return if (cells.length() > 0) cells.optJSONObject(0) else null
    }

    private fun isTelephonyCallbackListener(classLoader: ClassLoader, callback: Any, listenerName: String): Boolean {
        return runCatching {
            val listenerIface = XposedHelpers.findClass(
                "android.telephony.TelephonyCallback\$$listenerName", classLoader
            )
            listenerIface.isInstance(callback)
        }.getOrElse { false }
    }

    private fun buildFakeServiceState(classLoader: ClassLoader, cellArray: org.json.JSONArray?): Any? {
        XposedBridge.logOpenCellIdEvery(
            "buildFakeServiceState:called:${cellArray?.length() ?: 0}",
            "buildFakeServiceState called cellJsonCount=${cellArray?.length() ?: 0}"
        )
        return try {
            val clazz = XposedHelpers.findClass("android.telephony.ServiceState", classLoader)
            val state = XposedHelpers.newInstance(clazz)
            val cell = if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
            val operator = if (cell != null) {
                val mcc = positiveJsonInt(cell, "mcc", default = 460)
                val mnc = positiveJsonInt(cell, "mnc", "net", default = 0)
                String.format(java.util.Locale.US, "%d%02d", mcc, mnc)
            } else {
                "46000"
            }
            val operatorName = when (operator.takeLast(2).toIntOrNull() ?: 0) {
                1, 6, 9 -> "中国联通"
                3, 5, 11 -> "中国电信"
                else -> "中国移动"
            }

            try { XposedHelpers.callMethod(state, "setState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setVoiceRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setDataRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.callMethod(state, "setOperatorName", operatorName, operatorName, operator) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(state, "mVoiceRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(state, "mDataRegState", 0) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaLong", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaShort", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mVoiceOperatorNumeric", operator) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorAlphaLong", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorAlphaShort", operatorName) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(state, "mDataOperatorNumeric", operator) } catch (_: Throwable) {}
            XposedBridge.logOpenCellIdEvery(
                "buildFakeServiceState:success:$operator:$operatorName",
                "buildFakeServiceState success operator=$operator operatorName=$operatorName"
            )
            state
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("buildFakeServiceState failed", e)
            null
        }
    }

    private fun buildFakeSignalStrength(classLoader: ClassLoader, config: org.json.JSONObject?): Any? {
        val cellCount = config?.optJSONArray("cell_json")?.length() ?: 0
        XposedBridge.logOpenCellIdEvery(
            "buildFakeSignalStrength:called:$cellCount",
            "buildFakeSignalStrength called cellJsonCount=$cellCount"
        )
        return try {
            val clazz = XposedHelpers.findClass("android.telephony.SignalStrength", classLoader)
            val signalStrength = XposedHelpers.newInstance(clazz)
            val dbm = signalDbm(firstCell(config) ?: org.json.JSONObject(), 0)
            val lteSignalClass = XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
            val lteSignal = XposedHelpers.newInstance(lteSignalClass)
            try { XposedHelpers.setIntField(lteSignal, "mRsrp", dbm) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(lteSignal, "mRsrq", -10) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(lteSignal, "mRssnr", 300) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(lteSignal, "mSignalStrength", dbm + 113) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(signalStrength, "mLte", lteSignal) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(signalStrength, "mCellSignalStrengths", listOf(lteSignal)) } catch (_: Throwable) {}
            try { XposedHelpers.setBooleanField(signalStrength, "mLteAsPrimaryInNrNsa", true) } catch (_: Throwable) {}
            XposedBridge.logOpenCellIdEvery(
                "buildFakeSignalStrength:success:$dbm",
                "buildFakeSignalStrength success dbm=$dbm"
            )
            signalStrength
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("buildFakeSignalStrength failed", e)
            null
        }
    }

    /**
     * 构造 CellIdentity 派生类，完整兼容 Android 9 ~ Android 14+。
     * 按以下优先级尝试：
     *   1. 各 Android 版本已知的有参构造器（最优，字段由构造器写入）
     *   2. sun.misc.Unsafe.allocateInstance + 反射写字段
     *      （字段初始值为 0 而非 MAX_VALUE，避免 JIT 内联问题）
     *   3. 最小参数构造器 + 默认值填充（最后手段）
     */
    private fun constructCellIdentityByType(
        type: String,
        clazz: Class<*>,
        mcc: Int, mccStr: String,
        mnc: Int, mncStr: String,
        tacOrLac: Int, ciOrCid: Int, pci: Int
    ): Any {
        val ctors = clazz.declaredConstructors.onEach { it.isAccessible = true }

        // 按参数个数匹配构造器，调用失败则返回 null
        fun tryNewInstance(vararg args: Any?): Any? = ctors
            .firstOrNull { it.parameterCount == args.size }
            ?.runCatching { newInstance(*args) }
            ?.getOrNull()

        // ── 阶段一：尝试各版本有参构造器 ──
        val identity: Any? = when (type) {
            "LTE" -> {
                // Android 9 / API 28: (int mcc, int mnc, int ci, int pci, int tac) — 5 参数
                tryNewInstance(mcc, mnc, ciOrCid, pci, tacOrLac)
                // Android 10 / API 29: (int ci, int pci, int tac, int earfcn, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort) — 9 参数
                    ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: (int ci, int pci, int tac, int earfcn, int[] bands, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort, Collection, ClosedSubscriberGroupInfo) — 12 参数
                    ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, IntArray(0), 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "GSM" -> {
                // Android 9 / API 28: (int mcc, int mnc, int lac, int cid) — 4 参数
                tryNewInstance(mcc, mnc, tacOrLac, ciOrCid)
                // Android 9 / API 28: (int mcc, int mnc, int lac, int cid, int arfcn, int bsic) — 6 参数
                    ?: tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, 0, 0)
                // Android 10 / API 29: (int lac, int cid, int arfcn, int bsic, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: 10 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "WCDMA", "UMTS" -> {
                // Android 9: (int mcc, int mnc, int lac, int cid, int psc, int uarfcn) — 6 参数
                tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, pci, 0)
                // Android 10: (int lac, int cid, int psc, int uarfcn, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "")
                // Android 11+: 10 参数
                    ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            "NR" -> {
                // Android 11+: (int pci, int tac, long nci, int[] bands, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "")
                // Android 12+: 10 参数
                    ?: tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "", emptyList<Any>(), null)
            }
            else -> null
        }

        if (identity != null) return identity

        // ── 阶段二：Unsafe.allocateInstance + 反射写字段 ──
        // 字段初始值为 0（非 MAX_VALUE），避免 JIT 内联问题
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val obj = allocate.invoke(unsafe, clazz) as Any

            // 设置类型标识（CellIdentity.mType）
            val typeInt = when (type) { "GSM" -> 1; "LTE" -> 3; "WCDMA", "UMTS" -> 4; "NR" -> 6; else -> 3 }
            try { XposedHelpers.setIntField(obj, "mType", typeInt) } catch (_: Throwable) {}
            // MCC/MNC（Int 版 Android 9，String 版 Android 10+）
            try { XposedHelpers.setIntField(obj, "mMcc", mcc) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(obj, "mMnc", mnc) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mMccStr", mccStr) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mMncStr", mncStr) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mAlphaLong", "") } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(obj, "mAlphaShort", "") } catch (_: Throwable) {}

            when (type) {
                "LTE" -> {
                    try { XposedHelpers.setIntField(obj, "mCi", ciOrCid) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mTac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPci", pci) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(obj, "mBands", IntArray(0)) } catch (_: Throwable) {}
                }
                "GSM" -> {
                    try { XposedHelpers.setIntField(obj, "mLac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mCid", ciOrCid) } catch (_: Throwable) {}
                }
                "WCDMA", "UMTS" -> {
                    try { XposedHelpers.setIntField(obj, "mLac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mCid", ciOrCid) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPsc", pci) } catch (_: Throwable) {}
                }
                "NR" -> {
                    try { XposedHelpers.setIntField(obj, "mTac", tacOrLac) } catch (_: Throwable) {}
                    try { XposedHelpers.setLongField(obj, "mNci", ciOrCid.toLong()) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(obj, "mPci", pci) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(obj, "mBands", IntArray(0)) } catch (_: Throwable) {}
                }
            }
            XposedBridge.log("[LocationSpoofer][CellMock] Unsafe.allocateInstance succeeded for $type: CI=$ciOrCid, TAC=$tacOrLac")
            return obj
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer][CellMock] Unsafe failed for $type: $e")
        }

        // ── 阶段三：最小参数构造器 + 安全默认值填充（绝对保底）──
        val minCtor = ctors.minByOrNull { it.parameterCount }
            ?: throw IllegalStateException("No constructors for ${clazz.name}")
        val safeArgs = minCtor.parameterTypes.map { t ->
            when {
                t == Int::class.javaPrimitiveType    -> 0
                t == Long::class.javaPrimitiveType   -> 0L
                t == Boolean::class.javaPrimitiveType -> false
                t == Float::class.javaPrimitiveType  -> 0f
                t == Double::class.javaPrimitiveType -> 0.0
                t == IntArray::class.java            -> IntArray(0)
                t == java.util.Collection::class.java || t.isAssignableFrom(java.util.ArrayList::class.java) -> emptyList<Any>()
                else -> null
            }
        }.toTypedArray()
        val fallbackObj = try {
            minCtor.newInstance(*safeArgs)
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot construct ${clazz.name}: $e")
        }
        // 写字段
        try { XposedHelpers.setIntField(fallbackObj, "mMcc", mcc) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(fallbackObj, "mMnc", mnc) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(fallbackObj, "mMccStr", mccStr) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(fallbackObj, "mMncStr", mncStr) } catch (_: Throwable) {}
        when (type) {
            "LTE" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mCi", ciOrCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPci", pci) } catch (_: Throwable) {}
            }
            "GSM" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid) } catch (_: Throwable) {}
            }
            "WCDMA", "UMTS" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPsc", pci) } catch (_: Throwable) {}
            }
            "NR" -> {
                try { XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac) } catch (_: Throwable) {}
                try { XposedHelpers.setLongField(fallbackObj, "mNci", ciOrCid.toLong()) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(fallbackObj, "mPci", pci) } catch (_: Throwable) {}
            }
        }
        XposedBridge.log("[LocationSpoofer][CellMock] MinCtor fallback used for $type identity")
        return fallbackObj
    }

    data class SatelliteData(
        val svid: Int,
        val type: Int, // 1=GPS, 3=GLONASS, 5=BDS
        val elevation: Float,
        val azimuth: Float,
        val cn0: Float,
        val usedInFix: Boolean
    )

    private var cachedSatellites: Array<SatelliteData>? = null
    private var lastSatelliteUpdate: Long = 0L
    private var isSpoofingActiveCache: Boolean = false
    private var spoofingCountCache: Int = 0

    private fun updateSatelliteCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (cachedSatellites == null || now - lastSatelliteUpdate > 1000) {
            val config = readConfig()
            isSpoofingActiveCache = config?.optBoolean("active", false) ?: false
            if (isSpoofingActiveCache) {
                val count = config?.optInt("satellite_count", 10) ?: 10
                spoofingCountCache = count
                val startTime = config?.optLong("start_timestamp", now) ?: now
                val deltaTimeMin = (now - startTime) / 60000.0
                val timeSec = now / 1000.0
                val enableJitter = config?.optBoolean("enable_jitter", true) ?: true
                
                val newCache = Array(count) { i ->
                    generateSatelliteData(i, deltaTimeMin, enableJitter, timeSec)
                }
                cachedSatellites = newCache
            }
            lastSatelliteUpdate = now
        }
    }

    private fun getCachedSatellite(satIndex: Int): SatelliteData? {
        if (!isSpoofingActiveCache || cachedSatellites == null || cachedSatellites!!.isEmpty()) return null
        val safeIndex = satIndex % cachedSatellites!!.size
        return cachedSatellites!![safeIndex]
    }

    private fun generateSatelliteData(satIndex: Int, deltaTimeMin: Double, enableJitter: Boolean, timeSec: Double): SatelliteData {
        // 增量刷新优化 (Incremental Update Optimization):
        // 强制每个卫星的数据每 4 秒才变化一次，并使用 satIndex 进行错开 (Staggering)。
        // 这意味着在任何一秒钟，只有 25% 的卫星数据发生变化。
        // 目标 App 的 DiffUtil 会发现 75% 的卫星数据完全没变，从而跳过大部分 UI 重绘，彻底解决滑动卡顿！
        val updateIntervalSec = 4.0
        val steppedTimeSec = Math.floor((timeSec + satIndex) / updateIntervalSec) * updateIntervalSec - satIndex
        val steppedDeltaTimeMin = steppedTimeSec / 60.0

        val rng = java.util.Random(satIndex.toLong() + 1000L)
        val initialPhase = rng.nextDouble() * Math.PI * 2
        val amplitude = 20.0 + rng.nextDouble() * 20.0
        val baseElevation = 30.0 + rng.nextDouble() * 20.0
        val elevation = (baseElevation + amplitude * Math.sin(steppedDeltaTimeMin * 0.05 + initialPhase)).toFloat().coerceIn(0f, 90f)

        val rngAz = java.util.Random(satIndex.toLong() + 4000L)
        val initialAzimuth = rngAz.nextDouble() * 360.0
        val currentAzimuth = ((initialAzimuth + steppedDeltaTimeMin * 0.5) % 360.0).toFloat()

        val baseCn0 = 20.0 + (elevation / 90.0) * 20.0
        val noise = if (enableJitter) {
            val dynamicRng = java.util.Random((steppedTimeSec / 3.0).toLong() + satIndex)
            (dynamicRng.nextDouble() - 0.5) * 4.0 // +/- 2 dB
        } else 0.0
        val cn0 = (baseCn0 + noise).coerceIn(10.0, 45.0).toFloat()

        val rngType = java.util.Random(satIndex.toLong() + 3000L)
        val rand = rngType.nextDouble()
        val type = when {
            rand < 0.5 -> 1
            rand < 0.7 -> 3
            else -> 5
        }
        val svid = when (type) {
            1 -> 1 + (satIndex * 7) % 32 // GPS
            3 -> 1 + (satIndex * 3) % 24 // GLONASS (GnssStatus standard is 1-24)
            else -> 1 + (satIndex * 5) % 63 // BDS
        }
        
        // Log satellite generated occasionally or if debugging
        // XposedBridge.log("[GPS_Spoofer] Generated Sat: type=$type, svid=$svid, cn0=$cn0, elev=$elevation, az=$currentAzimuth")

        val rngFix = java.util.Random(satIndex.toLong() + 2000L)
        val usedInFix = rngFix.nextDouble() < 0.75

        return SatelliteData(svid, type, elevation, currentAzimuth, cn0, usedInFix)
    }

    /**
     * 拦截GnssStatus回调,注入伪造的卫星星座数据
     *
     * 反作弊SDK通过registerGnssStatusCallback获取卫星可见数和信噪比(C/N0),
     * 若Location坐标正常但卫星数为0或信噪比全为0,则判定为模拟位置。
     *
     * 伪造策略:
     * - 可见卫星数: 12-18颗(真实室外环境的典型值)
     * - 信噪比(C/N0): 15-40 dB-Hz(真实GPS信号的典型范围)
     * - 卫星类型: GPS(1) + GLONASS(3) + BDS(5)混合星座
     */
    private fun hookGnssStatus(classLoader: ClassLoader) {
        try {
            val locationManagerClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
            
            // Hook addGpsStatusListener
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "addGpsStatusListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0]
                        if (listener != null) {
                            val clazz = listener.javaClass
                            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                                try {
                                    XposedBridge.hookAllMethods(clazz, "onGpsStatusChanged", object : XC_MethodHook() {
                                        private var lastCallTime = 0L
                                        override fun beforeHookedMethod(innerParam: MethodHookParam) {
                                            val event = innerParam.args[0] as? Int
                                            if (event == 4) { // GPS_EVENT_SATELLITE_STATUS
                                                val now = System.currentTimeMillis()
                                                if (now - lastCallTime < 1000) {
                                                    innerParam.result = null // Throttle
                                                } else {
                                                    lastCallTime = now
                                                }
                                            }
                                        }
                                    })
                                } catch (e: Throwable) {}
                            }
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }

            // Hook removeGpsStatusListener
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "removeGpsStatusListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // No longer needed
                    }
                })
            } catch (e: Throwable) {}

            // Hook registerGnssStatusCallback
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "registerGnssStatusCallback", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        var callbackObj: Any? = null
                        val cbClass = try {
                            classLoader.loadClass("android.location.GnssStatus\$Callback")
                        } catch (e: Exception) { null }

                        for (arg in param.args) {
                            if (arg != null && cbClass != null && cbClass.isInstance(arg)) {
                                callbackObj = arg
                                break
                            }
                        }
                        if (callbackObj != null) {
                            val clazz = callbackObj.javaClass
                            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                                try {
                                    XposedBridge.hookAllMethods(clazz, "onSatelliteStatusChanged", object : XC_MethodHook() {
                                        private var lastCallTime = 0L
                                        private var cachedGnssStatus: Any? = null
                                        private var lastGnssStatusUpdate = 0L

                                        override fun beforeHookedMethod(innerParam: MethodHookParam) {
                                            val now = System.currentTimeMillis()
                                            if (now - lastCallTime < 1000) {
                                                innerParam.result = null // Throttle
                                                return
                                            }
                                            lastCallTime = now

                                            val statusObj = innerParam.args[0] ?: return
                                            updateSatelliteCacheIfNeeded()

                                            if (isSpoofingActiveCache && cachedSatellites != null) {
                                                val count = spoofingCountCache
                                                val sats = cachedSatellites!!

                                                // 1. Android 11+ (API 30+) 优先使用原生 Builder
                                                // 解决了 Android 11+ mSvidWithFlags 的位移变化 (8 -> 12) 导致的 SVID 全部变 0 的致命 Bug。
                                                try {
                                                    val builderClass = XposedHelpers.findClassIfExists("android.location.GnssStatus\$Builder", clazz.classLoader)
                                                    if (builderClass != null) {
                                                        if (cachedGnssStatus == null || now - lastGnssStatusUpdate > 1000) {
                                                            val builder = builderClass.getDeclaredConstructor().newInstance()
                                                            val addMethod = builderClass.getDeclaredMethod(
                                                                "addSatellite",
                                                                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                                                                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                                                                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                                                                Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType
                                                            )
                                                            addMethod.isAccessible = true
                                                            for (i in 0 until count) {
                                                                val sat = sats[i]
                                                                addMethod.invoke(
                                                                    builder, sat.type, sat.svid, sat.cn0, sat.elevation, sat.azimuth,
                                                                    true, true, sat.usedInFix, false, 0f, false, 0f
                                                                )
                                                            }
                                                            val buildMethod = builderClass.getDeclaredMethod("build")
                                                            buildMethod.isAccessible = true
                                                            cachedGnssStatus = buildMethod.invoke(builder)
                                                            lastGnssStatusUpdate = now
                                                        }
                                                        innerParam.args[0] = cachedGnssStatus
                                                        return // 成功构建并替换，直接返回
                                                    }
                                                } catch (e: Throwable) {}

                                                // 2. Android 10 及以下 (API 24-29) 回退到反射直接篡改底层数组
                                                // 旧版系统的 SVID_SHIFT_WIDTH = 8，以下位运算完全兼容。
                                                try {
                                                    val countField = statusObj.javaClass.getDeclaredField("mSvCount")
                                                    countField.isAccessible = true
                                                    countField.setInt(statusObj, count)

                                                    val cn0DbHzs = FloatArray(count)
                                                    val elevations = FloatArray(count)
                                                    val azimuths = FloatArray(count)
                                                    val svidWithFlags = IntArray(count)

                                                    for (i in 0 until count) {
                                                        val sat = sats[i]
                                                        cn0DbHzs[i] = sat.cn0
                                                        elevations[i] = sat.elevation
                                                        azimuths[i] = sat.azimuth
                                                        var flags = 1 or 2
                                                        if (sat.usedInFix) flags = flags or 4
                                                        svidWithFlags[i] = (sat.svid shl 8) or (sat.type and 0xF) or (flags shl 4)
                                                    }

                                                    val setField = { name: String, value: Any ->
                                                        try {
                                                            val f = statusObj.javaClass.getDeclaredField(name)
                                                            f.isAccessible = true
                                                            f.set(statusObj, value)
                                                        } catch (e: Exception) {}
                                                    }

                                                    setField("mSvidWithFlags", svidWithFlags)
                                                    setField("mCn0DbHz", cn0DbHzs)
                                                    setField("mElevations", elevations)
                                                    setField("mAzimuths", azimuths)
                                                    setField("mCarrierFrequencies", FloatArray(count))
                                                    setField("mBasebandCn0DbHzs", FloatArray(count))
                                                } catch (e: Exception) {}
                                            }
                                        }
                                    })
                                } catch (e: Throwable) {}
                            }
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }

            // Hook unregisterGnssStatusCallback
            try {
                XposedBridge.hookAllMethods(locationManagerClazz, "unregisterGnssStatusCallback", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // No longer needed
                    }
                })
            } catch (e: Throwable) {}

            // Hook GpsStatus.getSatellites() for legacy Apps like DevCheck
            try {
                XposedHelpers.findAndHookMethod("android.location.GpsStatus", classLoader, "getSatellites", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = createSpoofedGpsSatellites(classLoader)
                        }
                    }
                })
            } catch (e: Throwable) { XposedBridge.log(e) }


            XposedBridge.log("[LocationSpoofer] GnssStatus hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] GnssStatus hook failed: $e")
        }
    }

    @Volatile
    private var lastConfig: JSONObject? = null
    @Volatile
    private var lastOpenCellConfigLogKey: String? = null
    @Volatile
    private var lastOpenCellConfigReadFailureLogTime = 0L
    @Volatile
    private var isConfigPollingStarted = false
    @Volatile
    private var configPollIntervalMs = 1_000L
    private val pollingLock = Any()
    private val localConfigPath = "/data/local/tmp/locationspoofer_config.json"
    private val systemConfigPath = "/data/system/locationspoofer_config.json"

    private fun logOpenCellConfigLoaded(source: String, config: JSONObject) {
        val cellArray = config.optJSONArray("cell_json")
        val cellCount = cellArray?.length() ?: 0
        val firstCell = if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
        val firstSummary = if (firstCell != null) {
            val type = normalizeCellType(firstCell.optString("type", firstCell.optString("radio", "LTE")))
            val mcc = positiveJsonInt(firstCell, "mcc", default = 460)
            val mnc = positiveJsonInt(firstCell, "mnc", "net", default = 0)
            val area = cellAreaCode(firstCell, 0)
            val identity = cellIdentityCode(firstCell, 0)
            "$type/$mcc-$mnc area=$area identity=$identity"
        } else {
            "none"
        }
        val logKey = "${config.optBoolean("active", false)}|${config.optBoolean("mock_cell", true)}|${config.optDouble("lat", 0.0)}|${config.optDouble("lng", 0.0)}|$cellCount|$firstSummary"
        if (logKey != lastOpenCellConfigLogKey) {
            lastOpenCellConfigLogKey = logKey
            XposedBridge.logOpenCellId(
                "readConfig[$source] active=${config.optBoolean("active", false)} mockCell=${config.optBoolean("mock_cell", true)} lat=${config.optDouble("lat", 0.0)} lng=${config.optDouble("lng", 0.0)} cellJsonCount=$cellCount firstCell=$firstSummary"
            )
        }
    }

    private fun configReadPaths(): Array<String> {
        return if (android.os.Process.myUid() == 1000) {
            arrayOf(systemConfigPath, localConfigPath)
        } else {
            arrayOf(localConfigPath, systemConfigPath)
        }
    }

    private fun normalizeConfig(config: JSONObject): JSONObject {
        if (!config.has("wifi_json")) config.put("wifi_json", org.json.JSONArray())
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        val wgs84 = gcj02ToWgs84(lat, lng)
        config.put("wgs84_lat", wgs84.first)
        config.put("wgs84_lng", wgs84.second)
        val bd09 = gcj02ToBd09(lat, lng)
        config.put("bd09_lat", bd09.first)
        config.put("bd09_lng", bd09.second)
        return config
    }

    private fun loadConfigFromDisk(source: String): JSONObject? {
        val errors = ArrayList<String>()
        for (path in configReadPaths()) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    errors.add("$path missing")
                    continue
                }
                val config = normalizeConfig(JSONObject(file.readText()))
                lastConfig = config
                configPollIntervalMs = 1_000L
                logOpenCellConfigLoaded("$source:$path", config)
                return config
            } catch (e: Throwable) {
                errors.add("$path ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        val now = System.currentTimeMillis()
        val isPermissionDenied = errors.any { it.contains("EACCES") || it.contains("Permission denied") }
        val shouldBackoff = currentPackageName == "com.android.phone" && isPermissionDenied
        val logIntervalMs = if (isPermissionDenied) 60_000L else 10_000L
        if (shouldBackoff) {
            configPollIntervalMs = 60_000L
        }
        if (now - lastOpenCellConfigReadFailureLogTime > logIntervalMs) {
            lastOpenCellConfigReadFailureLogTime = now
            XposedBridge.logOpenCellId("readConfig[$source] no readable config (${errors.joinToString(" | ")})")
        }
        return null
    }

    /**
     * 从本地文件读取模拟配置(纯文件方案,无ContentProvider跨进程调用)
     *
     * 架构优化:
     *    由于此方法会被各种 Hook 在主线程极其高频地调用（例如每秒数百次），
     *    任何在主线程进行的文件 IO（哪怕是偶尔一次）都会导致严重的丢帧卡顿（Stutter）。
     *    因此重构为：在首次调用时启动一个后台守护线程（Daemon Thread），
     *    每隔 1000ms 在后台异步读取文件并更新 Volatile 的 lastConfig。
     *    主线程的 readConfig() 永远只返回内存中的 lastConfig，实现真正的 0 IO 延迟。
     */
    private fun readConfig(): JSONObject? {
        if (!isConfigPollingStarted) {
            synchronized(pollingLock) {
                if (!isConfigPollingStarted) {
                    isConfigPollingStarted = true

                    // 首次调用时同步读取一次，确保立即有数据可用
                    loadConfigFromDisk("initial")

                    // 启动后台轮询守护线程
                    Thread {
                        while (true) {
                            Thread.sleep(configPollIntervalMs)
                            loadConfigFromDisk("poll")
                        }
                    }.apply {
                        isDaemon = true
                        name = "LocationSpoofer_ConfigPoller"
                        start()
                    }
                }
            }
        }
        return lastConfig
    }

    private var cachedGpsSatellitesList: Iterable<Any>? = null
    private var lastGpsSatellitesUpdate = 0L

    private fun createSpoofedGpsSatellites(classLoader: ClassLoader): Iterable<Any> {
        val now = System.currentTimeMillis()
        if (cachedGpsSatellitesList == null || now - lastGpsSatellitesUpdate > 1000) {
            val list = ArrayList<Any>()
            try {
                updateSatelliteCacheIfNeeded()
                if (!isSpoofingActiveCache || cachedSatellites == null) return list

                val satelliteClass = classLoader.loadClass("android.location.GpsSatellite")
                val constructor = satelliteClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                constructor.isAccessible = true

                for (i in 0 until spoofingCountCache) {
                    val data = getCachedSatellite(i) ?: continue
                    val prn = if (data.type == 3) data.svid + 64 else data.svid
                    val sat = constructor.newInstance(prn)
                    try { XposedHelpers.setBooleanField(sat, "mValid", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mHasEphemeris", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mHasAlmanac", true) } catch (e: Throwable) {}
                    try { XposedHelpers.setBooleanField(sat, "mUsedInFix", data.usedInFix) } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mSnr")
                        f.isAccessible = true
                        f.setFloat(sat, data.cn0)
                    } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mElevation")
                        f.isAccessible = true
                        f.setFloat(sat, data.elevation)
                    } catch (e: Throwable) {}
                    try { 
                        val f = satelliteClass.getDeclaredField("mAzimuth")
                        f.isAccessible = true
                        f.setFloat(sat, data.azimuth)
                    } catch (e: Throwable) {}
                    list.add(sat)
                }
                cachedGpsSatellitesList = list
                lastGpsSatellitesUpdate = now
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }
        }
        return cachedGpsSatellitesList ?: ArrayList()
    }

    private fun createOnNmeaMessageListenerProxy(original: Any, classLoader: ClassLoader): Any {
        val interfaceClass = classLoader.loadClass("android.location.OnNmeaMessageListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onNmeaMessage" && args != null && args.size >= 1) {
                        val originalMsg = args[0] as? String
                        if (originalMsg != null) {
                            val spoofedMsg = spoofNmeaMessage(originalMsg)
                            if (spoofedMsg == null) return null // Allow dropping messages
                            val newArgs = arrayOfNulls<Any>(args.size)
                            for (i in args.indices) {
                                newArgs[i] = if (i == 0) spoofedMsg else args[i]
                            }
                            return method.invoke(original, *newArgs)
                        }
                    }
                    val methodArgs = if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                    return method.invoke(original, *methodArgs)
                }
            }
        )
        // startNmeaGsvInjector(original, "onNmeaMessage", classLoader)
        return proxy
    }

    private fun createGpsStatusNmeaListenerProxy(original: Any, classLoader: ClassLoader): Any {
        val interfaceClass = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onNmeaReceived" && args != null && args.size >= 2) {
                        val originalMsg = args[1] as? String
                        if (originalMsg != null) {
                            val spoofedMsg = spoofNmeaMessage(originalMsg)
                            if (spoofedMsg == null) return null // Allow dropping messages
                            val newArgs = arrayOfNulls<Any>(args.size)
                            for (i in args.indices) {
                                newArgs[i] = if (i == 1) spoofedMsg else args[i]
                            }
                            return method.invoke(original, *newArgs)
                        }
                    }
                    val methodArgs = if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                    return method.invoke(original, *methodArgs)
                }
            }
        )
        // startNmeaGsvInjector(original, "onNmeaReceived", classLoader)
        return proxy
    }

    private fun spoofNmeaMessage(sentence: String): String? {
        try {
            val config = readConfig() ?: return sentence
            if (!config.optBoolean("active", false)) return sentence
            
            val targetLat = config.optDouble("wgs84_lat", 0.0)
            val targetLng = config.optDouble("wgs84_lng", 0.0)
            if (targetLat == 0.0 && targetLng == 0.0) return sentence
            
            val parts = sentence.split("*")
            val mainPart = parts[0]
            val fields = mainPart.split(",").toMutableList()
            if (fields.isEmpty()) return sentence
            
            val type = fields[0]
            var modified = false
            
            // Drop GSV sentences to hide real hardware satellites.
            // (We removed the fake GSV injector, so the app will simply not see GSV sentences,
            // which is safer than showing real hardware GSV sentences that conflict with our fake GnssStatus).
            if (type.endsWith("GSV")) {
                return null
            }
            
            if (type.endsWith("RMC") && fields.size >= 7) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[3] = latStr
                fields[4] = latDir
                fields[5] = lngStr
                fields[6] = lngDir
                modified = true
            } else if (type.endsWith("GGA") && fields.size >= 6) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[2] = latStr
                fields[3] = latDir
                fields[4] = lngStr
                fields[5] = lngDir
                modified = true
            } else if (type.endsWith("GLL") && fields.size >= 5) {
                val (latStr, latDir) = convertToNmeaLatitude(targetLat)
                val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
                fields[1] = latStr
                fields[2] = latDir
                fields[3] = lngStr
                fields[4] = lngDir
                modified = true
            }
            
            if (!modified) return sentence
            
            val newMainPart = fields.joinToString(",")
            val newChecksum = calculateNmeaChecksum(newMainPart)
            
            val tail = if (parts.size > 1) {
                val rawTail = parts[1]
                val lineEnding = rawTail.substring(Math.min(2, rawTail.length))
                "*$newChecksum$lineEnding"
            } else {
                "*$newChecksum"
            }
            return newMainPart + tail
        } catch (e: Exception) {
            XposedBridge.log(e)
            return sentence
        }
    }

    private fun convertToNmeaLatitude(lat: Double): Pair<String, String> {
        val absLat = Math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val latStr = String.format(java.util.Locale.US, "%02d%08.5f", degrees, minutes)
        val dir = if (lat >= 0) "N" else "S"
        return Pair(latStr, dir)
    }

    private fun convertToNmeaLongitude(lng: Double): Pair<String, String> {
        val absLng = Math.abs(lng)
        val degrees = absLng.toInt()
        val minutes = (absLng - degrees) * 60.0
        val lngStr = String.format(java.util.Locale.US, "%03d%08.5f", degrees, minutes)
        val dir = if (lng >= 0) "E" else "W"
        return Pair(lngStr, dir)
    }

    private fun calculateNmeaChecksum(sentence: String): String {
        var checksum = 0
        val startIndex = if (sentence.startsWith("$")) 1 else 0
        val endIndex = sentence.indexOf('*')
        val limit = if (endIndex != -1) endIndex else sentence.length
        for (i in startIndex until limit) {
            checksum = checksum xor sentence[i].code
        }
        return String.format(java.util.Locale.US, "%02X", checksum)
    }

}
