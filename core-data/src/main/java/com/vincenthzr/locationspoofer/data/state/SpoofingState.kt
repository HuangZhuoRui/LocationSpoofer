package com.vincenthzr.locationspoofer.data.state

/**
 * 宿主进程内的模拟状态单例。
 * 曾经是一个 android:exported="true" 且无权限保护的 ContentProvider（SpooferProvider），
 * 但它的 query()/insert() 等跨进程接口从未被真正调用过——跨进程配置传递走的是
 * ConfigManager 写文件 + 目标进程轮询读取这条路径。ContentProvider 只是被当作进程内的
 * 全局可变状态在用，因此把它换成一个不导出的普通单例，消除设备上任意 App 都能无权限
 * 查询到实时坐标/周边无线环境数据的暴露面。
 */
object SpoofingState {
    var isActive = false
    var latitude = 0.0      // GCJ-02（高德坐标系，存入即为GCJ-02）
    var longitude = 0.0     // GCJ-02
    var wifiJson = "[]"
    var cellJson = "[]"
    var bluetoothJson = "[]"
    var simMode = "STILL"
    var simBearing = 0f
    var startTimestamp = 0L
    var routeJson = "[]"
    var isRouteMode = false
    var enableJitter = true

    // 开始模拟时对真实度参数做快照：速度浮动决定"累计距离随时间的函数"，模拟途中改设置会让路线位置突然跳一段，
    // 所以本次会话内固定不变，修改在下次开始模拟时生效。-1 表示没有快照（如进程重启后），直接读当前设置。
    var realismLevel = -1
    var speedFluctuationPct = -1

    /** 路线暂停后继续时，从暂停处的累计距离（米）接着走；新会话开始时清零 */
    var routeDistanceOffset = 0.0
}
