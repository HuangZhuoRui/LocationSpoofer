package com.vincenthzr.locationspoofer.ui.components

import android.graphics.Color.argb
import com.vincenthzr.locationspoofer.data.db.LocationRecord
import kotlin.math.cos

object MapCoverageHelper {
    /**
     * 高性能空间网格降采样覆盖圆圈绘制：
     * 1. 密集重叠区域降采样：对距离较近（< 30米）的密集重叠点位进行空间聚合合并，避免在底层地图 SDK 中创建成千上万个 Native Circle 对象造成 GPU 渲染管线雪崩。
     * 2. 硬性绘制上限截断：将单次绘制的原生圆圈数量控制在 180 个以内，确保地图即使在拥有几万条历史数据时依然保持 120 FPS 丝滑拖拽与缩放。
     */
    fun drawCoverage(
        controller: AppMapController,
        locations: List<LocationRecord>,
        centerLat: Double? = null,
        centerLng: Double? = null
    ) {
        if (locations.isEmpty()) return

        val fillColor = argb(50, 46, 204, 113) // 带透明度的 AccentGreen
        val strokeColor = argb(100, 46, 204, 113)

        val targetList = if (locations.size > 180) {
            downsampleLocations(
                locations = locations,
                centerLat = centerLat,
                centerLng = centerLng,
                maxCircles = 180,
                minDistanceMeters = 30.0
            )
        } else {
            locations
        }

        targetList.forEach { loc ->
            controller.addCircle(loc.lat, loc.lng, 50.0, fillColor, strokeColor, 2f)
        }
    }

    private fun downsampleLocations(
        locations: List<LocationRecord>,
        centerLat: Double?,
        centerLng: Double?,
        maxCircles: Int,
        minDistanceMeters: Double
    ): List<LocationRecord> {
        val sortedList = if (centerLat != null && centerLng != null) {
            locations.sortedBy { loc ->
                val dLat = loc.lat - centerLat
                val dLng = loc.lng - centerLng
                dLat * dLat + dLng * dLng
            }
        } else {
            locations.reversed()
        }

        val result = ArrayList<LocationRecord>(maxCircles)
        val minDistanceSq = (minDistanceMeters / 111320.0).let { it * it }

        for (loc in sortedList) {
            if (result.size >= maxCircles) break
            var isTooClose = false
            for (existing in result) {
                val dLat = loc.lat - existing.lat
                val dLng = (loc.lng - existing.lng) * cos(Math.toRadians(loc.lat))
                if (dLat * dLat + dLng * dLng < minDistanceSq) {
                    isTooClose = true
                    break
                }
            }
            if (!isTooClose) {
                result.add(loc)
            }
        }
        return result
    }
}
