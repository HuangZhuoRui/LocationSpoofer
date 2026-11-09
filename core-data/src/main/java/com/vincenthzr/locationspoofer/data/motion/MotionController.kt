package com.vincenthzr.locationspoofer.data.motion

import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.MotionRealism
import com.vincenthzr.locationspoofer.utils.RoutePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 进程级的运动控制器：当前模拟位置、路线播放 / 暂停 / 继续、摇杆移动、速度档位、路线录制都由它统一持有。
 *
 * App 界面和悬浮窗摇杆都只是它的"遥控器"，所以悬浮窗在其他 App 之上操作时，状态依然和界面一致，
 * 也不依赖界面所在的 Activity / ViewModel 是否还活着（模拟期间 SpoofingService 是前台服务，进程不会被回收）。
 *
 * 配置写入只改运动相关字段（见 [ConfigMotionSink]），不会覆盖周边数据、开关等其他来源写入的字段。
 */
class MotionController(
    private val sink: MotionSink,
    /** (随机强度, 速度浮动百分比)；会话内取开始模拟时的快照，见 SpoofingState */
    private val realismParams: () -> Pair<Int, Int>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 测试时关闭内部节拍，由测试手动调用 [tick] 推进 */
    private val autoTick: Boolean = true
) {

    /** 控制器的副作用出口：写运动配置、保存录制的路线 */
    interface MotionSink {
        suspend fun writeManual(lat: Double, lng: Double, bearing: Float, speedMs: Double, timestamp: Long)
        suspend fun writeRoute(
            lat: Double,
            lng: Double,
            bearing: Float,
            preset: SpeedPreset,
            points: List<RoutePoint>,
            startTimestamp: Long,
            distanceOffset: Double,
            stopAtDestination: Boolean
        )
        suspend fun saveRoute(name: String, points: List<RoutePoint>)
    }

    enum class Mode {
        /** 未在模拟 */
        IDLE,
        /** 定点模拟，位置固定 */
        STATIC,
        /** 路线自动播放中 */
        ROUTE,
        /** 摇杆手动控制（定点模拟中推动摇杆、手动路线模式、或路线暂停后） */
        MANUAL
    }

    /** 速度档位；name 与界面的 SimMode 枚举名一致，方便双向同步 */
    data class SpeedPreset(val name: String, val speedMs: Double)

    data class State(
        val mode: Mode = Mode.IDLE,
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val bearing: Float = 0f,
        val speedMs: Double = 0.0,
        val hasRoute: Boolean = false,
        /** 路线已暂停，摇杆接管 */
        val paused: Boolean = false,
        /** 正在自动走回暂停点，到达后继续原路线 */
        val returning: Boolean = false,
        val preset: SpeedPreset = PRESETS.first(),
        val recording: Boolean = false,
        val recordedPointCount: Int = 0,
        val recordedDistanceM: Double = 0.0,
        /** 录制已结束、等待命名保存的路线 */
        val hasPendingRecording: Boolean = false
    ) {
        val active: Boolean get() = mode != Mode.IDLE
        /** 路线自动播放或走回暂停点期间，摇杆不响应 */
        val joystickEnabled: Boolean get() = active && mode != Mode.ROUTE && !returning
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    // ---- 路线会话
    private var path: RoutePath? = null
    private var stopAtDestination = false
    private var routeStartTs = 0L
    private var routeOffset = 0.0
    private var pausePoint: LatLng? = null
    private var pauseDistance = 0.0

    // ---- 摇杆输入
    @Volatile private var joyBearing = 0.0
    @Volatile private var joyIntensity = 0f
    private var lastTickTs = 0L

    // ---- 摇杆 / 走回暂停点期间的配置写入节流
    private var lastWriteTs = 0L
    private var lastWrittenBearing = 0f
    private var lastWrittenSpeed = 0.0

    // ---- 录制
    private val recorded = ArrayList<LatLng>()

    // ---- 合并写入：只保证执行"最新一次"请求的运动状态，避免积压的旧写入晚于新写入落盘
    @Volatile private var pendingWrite: (suspend () -> Unit)? = null
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in writeSignal) {
                val job = pendingWrite ?: continue
                pendingWrite = null
                try {
                    job()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ================================================================ 会话生命周期（由界面在开始 / 停止模拟时调用）

    fun onStaticStarted(lat: Double, lng: Double) {
        clearRoute()
        joyIntensity = 0f
        _state.update { it.copy(mode = Mode.STATIC, lat = lat, lng = lng, speedMs = 0.0, hasRoute = false, paused = false, returning = false) }
        ensureTicking()
    }

    /**
     * @param startPaused 手动模式：路线已加载但处于暂停状态、由摇杆控制，
     *   点"开始"时从当前位置走到路线起点再沿路线前进（暂停点即起点）
     */
    fun onRouteStarted(
        points: List<RoutePoint>,
        preset: SpeedPreset,
        stopAtDestination: Boolean,
        startTimestamp: Long,
        startPaused: Boolean = false
    ) {
        val routePath = RoutePath(points.map { LatLng(it.lat, it.lng) })
        if (!routePath.isValid) return
        path = routePath
        this.stopAtDestination = stopAtDestination
        routeStartTs = startTimestamp
        routeOffset = 0.0
        joyIntensity = 0f
        val start = routePath.points.first()
        if (startPaused) {
            pausePoint = start
            pauseDistance = 0.0
        } else {
            pausePoint = null
        }
        _state.update {
            it.copy(
                mode = if (startPaused) Mode.MANUAL else Mode.ROUTE,
                lat = start.lat,
                lng = start.lng,
                speedMs = 0.0,
                hasRoute = true,
                paused = startPaused,
                returning = false,
                preset = preset
            )
        }
        ensureTicking()
    }

    fun onStopped() {
        clearRoute()
        joyIntensity = 0f
        pendingWrite = null
        if (_state.value.recording) finishRecording()
        _state.update { it.copy(mode = Mode.IDLE, speedMs = 0.0, hasRoute = false, paused = false, returning = false) }
        tickJob?.cancel()
        tickJob = null
    }

    /** 进程重启后界面状态丢失但模拟仍在进行时，按 SpoofingState 恢复成定点模拟 */
    fun syncFromSpoofingStateIfIdle() {
        if (_state.value.mode == Mode.IDLE && SpoofingState.isActive) {
            onStaticStarted(SpoofingState.latitude, SpoofingState.longitude)
        }
    }

    // ================================================================ 控制

    fun joystick(bearing: Double, intensity: Float) {
        val s = _state.value
        if (!s.joystickEnabled) return
        if (intensity > 0f && s.mode == Mode.STATIC) {
            _state.update { it.copy(mode = Mode.MANUAL) }
        }
        val wasMoving = joyIntensity > 0f
        joyBearing = bearing
        joyIntensity = intensity.coerceIn(0f, 1f)
        if (wasMoving && joyIntensity == 0f) {
            val cur = _state.value
            _state.update { it.copy(speedMs = 0.0) }
            requestManualWrite(cur.lat, cur.lng, cur.bearing, 0.0, clock())
        }
    }

    fun pause() {
        val s = _state.value
        val routePath = path ?: return
        if (s.mode != Mode.ROUTE) return
        val now = clock()
        val traveled = traveledAt(now)
        val pos = routePath.positionAt(traveled, stopAtDestination)
        pauseDistance = traveled
        pausePoint = LatLng(pos.lat, pos.lng)
        joyIntensity = 0f
        _state.update { it.copy(mode = Mode.MANUAL, lat = pos.lat, lng = pos.lng, bearing = pos.bearing, speedMs = 0.0, paused = true) }
        requestManualWrite(pos.lat, pos.lng, pos.bearing, 0.0, now)
    }

    /** 继续原路线：离暂停点较远时先自动走回去，全程不瞬移 */
    fun resume() {
        val s = _state.value
        val target = pausePoint ?: return
        if (!s.paused) return
        joyIntensity = 0f
        if (GeoMath.distance(LatLng(s.lat, s.lng), target) < 1.0) {
            resumeRoute(clock())
        } else {
            _state.update { it.copy(returning = true) }
        }
    }

    fun setSpeedPreset(preset: SpeedPreset) {
        val s = _state.value
        if (s.mode == Mode.ROUTE && path != null) {
            // 换速度会改变"累计距离随时间的函数"，先把已走的距离固定为偏移，再按新速度从当前时刻继续
            val now = clock()
            routeOffset = traveledAt(now)
            routeStartTs = now
            _state.update { it.copy(preset = preset) }
            requestRouteWrite(now)
        } else {
            _state.update { it.copy(preset = preset) }
        }
    }

    fun startRecording() {
        val s = _state.value
        if (!s.active || s.recording) return
        recorded.clear()
        recorded += LatLng(s.lat, s.lng)
        _state.update { it.copy(recording = true, recordedPointCount = 1, recordedDistanceM = 0.0, hasPendingRecording = false) }
    }

    fun stopRecording() {
        if (_state.value.recording) finishRecording()
    }

    /** 保存录制的路线到"已保存路线"；成功返回 true */
    suspend fun saveRecording(name: String): Boolean {
        if (recorded.size < 2 || name.isBlank()) return false
        val points = recorded.map { RoutePoint(it.lat, it.lng) }
        sink.saveRoute(name.trim(), points)
        discardRecording()
        return true
    }

    fun discardRecording() {
        recorded.clear()
        _state.update { it.copy(recording = false, hasPendingRecording = false, recordedPointCount = 0, recordedDistanceM = 0.0) }
    }

    // ================================================================ 内部

    private fun clearRoute() {
        path = null
        routeOffset = 0.0
        pausePoint = null
        SpoofingState.routeDistanceOffset = 0.0
    }

    private fun finishRecording() {
        val s = _state.value
        appendRecordedPoint(LatLng(s.lat, s.lng), force = true)
        _state.update { it.copy(recording = false, hasPendingRecording = recorded.size >= 2) }
    }

    private fun realism(start: Long): MotionRealism.Session {
        val (level, pct) = realismParams()
        return MotionRealism.session(start, level, pct)
    }

    /** 与 Xposed 端 RouteEngine 同一公式：偏移 + 从本段开始时刻起按（带浮动的）速度行进的距离 */
    private fun traveledAt(now: Long): Double {
        val elapsed = (now - routeStartTs).coerceAtLeast(0L) / 1000.0
        return routeOffset + realism(routeStartTs).distance(_state.value.preset.speedMs, elapsed)
    }

    private fun ensureTicking() {
        if (!autoTick || tickJob?.isActive == true) return
        lastTickTs = clock()
        tickJob = scope.launch {
            while (isActive) {
                tick(clock())
                delay(TICK_MS)
            }
        }
    }

    internal fun tick(now: Long) {
        val dt = ((now - lastTickTs).coerceIn(0L, 1000L)) / 1000.0
        lastTickTs = now
        val s = _state.value
        when {
            s.mode == Mode.ROUTE -> {
                val routePath = path ?: return
                val elapsed = (now - routeStartTs).coerceAtLeast(0L) / 1000.0
                val pos = routePath.positionAt(traveledAt(now), stopAtDestination)
                val speed = if (pos.arrived) 0.0 else realism(routeStartTs).speed(s.preset.speedMs, elapsed)
                _state.update { it.copy(lat = pos.lat, lng = pos.lng, bearing = pos.bearing, speedMs = speed) }
            }

            s.returning -> {
                val target = pausePoint ?: return
                val here = LatLng(s.lat, s.lng)
                val remaining = GeoMath.distance(here, target)
                val step = s.preset.speedMs * dt
                if (remaining <= maxOf(step, 1.0)) {
                    _state.update { it.copy(lat = target.lat, lng = target.lng) }
                    resumeRoute(now)
                } else {
                    val bearing = GeoMath.bearing(here, target)
                    moveBy(here, bearing, step, s.preset.speedMs, now)
                }
            }

            s.mode == Mode.MANUAL && joyIntensity > 0f -> {
                val speed = s.preset.speedMs * joyIntensity
                moveBy(LatLng(s.lat, s.lng), joyBearing.toFloat(), speed * dt, speed, now)
            }
        }
        if (_state.value.recording) appendRecordedPoint(LatLng(_state.value.lat, _state.value.lng), force = false)
    }

    private fun moveBy(from: LatLng, bearing: Float, meters: Double, speed: Double, now: Long) {
        val next = GeoMath.destination(from, bearing.toDouble(), meters)
        _state.update { it.copy(lat = next.lat, lng = next.lng, bearing = bearing, speedMs = speed) }
        val bearingChanged = angleDiff(bearing, lastWrittenBearing) >= 20f
        val speedChanged = abs(speed - lastWrittenSpeed) > maxOf(0.3 * lastWrittenSpeed, 0.2)
        val interval = now - lastWriteTs
        if (interval >= WRITE_INTERVAL_MS || ((bearingChanged || speedChanged) && interval >= MIN_WRITE_INTERVAL_MS)) {
            requestManualWrite(next.lat, next.lng, bearing, speed, now)
        }
    }

    private fun resumeRoute(now: Long) {
        val target = pausePoint ?: return
        routeOffset = pauseDistance
        routeStartTs = now
        pausePoint = null
        _state.update { it.copy(mode = Mode.ROUTE, paused = false, returning = false, lat = target.lat, lng = target.lng) }
        requestRouteWrite(now)
    }

    /** 摇杆 / 走回暂停点：非路线模式，由 Xposed 端按方向和速度在两次写入之间推算位置 */
    private fun requestManualWrite(lat: Double, lng: Double, bearing: Float, speed: Double, now: Long) {
        lastWriteTs = now
        lastWrittenBearing = bearing
        lastWrittenSpeed = speed
        SpoofingState.latitude = lat
        SpoofingState.longitude = lng
        SpoofingState.simMode = "JOYSTICK"
        SpoofingState.simBearing = bearing
        SpoofingState.startTimestamp = now
        SpoofingState.isRouteMode = false
        enqueueWrite { sink.writeManual(lat, lng, bearing, speed, now) }
    }

    /** 路线模式：从 routeOffset 处、按当前档位速度、自 routeStartTs 起继续 */
    private fun requestRouteWrite(now: Long) {
        val routePath = path ?: return
        val s = _state.value
        val offset = routeOffset
        val start = routeStartTs
        SpoofingState.startTimestamp = start
        SpoofingState.routeDistanceOffset = offset
        SpoofingState.isRouteMode = true
        SpoofingState.simMode = s.preset.name
        val points = routePath.points.map { RoutePoint(it.lat, it.lng) }
        val stop = stopAtDestination
        enqueueWrite { sink.writeRoute(s.lat, s.lng, s.bearing, s.preset, points, start, offset, stop) }
    }

    private fun enqueueWrite(block: suspend () -> Unit) {
        pendingWrite = block
        writeSignal.trySend(Unit)
    }

    /** 录制采样：离上一个点 5 米以上，或 2 米以上且转向超过 25° 时记一个点，直线段不产生冗余点 */
    private fun appendRecordedPoint(p: LatLng, force: Boolean) {
        val last = recorded.lastOrNull()
        if (last == null) {
            recorded += p
        } else {
            val d = GeoMath.distance(last, p)
            val turned = recorded.size >= 2 &&
                angleDiff(GeoMath.bearing(recorded[recorded.size - 2], last), GeoMath.bearing(last, p)) >= 25f
            if (!(d >= 5.0 || (d >= 2.0 && turned) || (force && d >= 0.5))) return
            recorded += p
            _state.update { it.copy(recordedDistanceM = it.recordedDistanceM + d) }
        }
        _state.update { it.copy(recordedPointCount = recorded.size) }
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    companion object {
        private const val TICK_MS = 100L
        private const val WRITE_INTERVAL_MS = 1000L
        private const val MIN_WRITE_INTERVAL_MS = 250L

        /** 速度档位，与界面 SimMode 的速度保持一致 */
        val PRESETS = listOf(
            SpeedPreset("WALKING", 1.4),
            SpeedPreset("RUNNING", 3.0),
            SpeedPreset("CYCLING", 5.5),
            SpeedPreset("DRIVING", 15.0)
        )
    }
}
