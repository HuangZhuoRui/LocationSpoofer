package com.suseoaa.locationspoofer.ui.components.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.suseoaa.locationspoofer.ui.components.MarkerType
import com.suseoaa.locationspoofer.utils.CoordinateUtils

// 坐标系转换工具
// config 中统一存储 GCJ-02；各地图 SDK 的原生坐标在此处统一转换
// 实际数学实现统一收敛到 com.suseoaa.locationspoofer.utils.CoordinateUtils（唯一 source of truth），
// 这里保留 Pair<Double, Double> 签名只是为了不改动本文件调用方（GoogleMapEngineAdapter 等）现有的写法。

fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.wgs84ToGcj02(wgsLat, wgsLng)
    return Pair(r.lat, r.lng)
}

/**
 * GCJ-02 → WGS-84（采用3轮反向逼近迭代算法，误差小于0.5毫米）
 */
fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.gcj02ToWgs84(gcjLat, gcjLng)
    return Pair(r.lat, r.lng)
}

fun bd09ToGcj02(bdLat: Double, bdLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.bd09ToGcj02(bdLat, bdLng)
    return Pair(r.lat, r.lng)
}

fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.gcj02ToBd09(gcjLat, gcjLng)
    return Pair(r.lat, r.lng)
}

object GaodeMarkerHelper {
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    fun getMarkerBitmap(
        context: Context,
        text: String,
        type: MarkerType
    ): Bitmap {
        val cacheKey = "${type.name}_$text"
        bitmapCache[cacheKey]?.let {
            if (!it.isRecycled) return it
        }

        val density = context.resources.displayMetrics.density
        val width = (32 * density).toInt().coerceAtLeast(1)
        val height = (44 * density).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val mainColor = when (type) {
            MarkerType.GREEN -> android.graphics.Color.parseColor("#00B578")  // 高德起点绿
            MarkerType.RED -> android.graphics.Color.parseColor("#FA5151")    // 高德终点红
            MarkerType.ORANGE -> android.graphics.Color.parseColor("#FF7A00") // 高德导航橙
            MarkerType.DEFAULT -> android.graphics.Color.parseColor("#388BFD")// 高德经典蓝
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mainColor
            style = Paint.Style.FILL
            isDither = true
        }

        // 绘制阴影
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#33000000")
            style = Paint.Style.FILL
        }
        canvas.drawOval(
            RectF(
                width * 0.2f,
                height * 0.88f,
                width * 0.8f,
                height * 0.98f
            ),
            shadowPaint
        )

        // 绘制外层图钉气滴路径
        val path = Path()
        val radius = width / 2f
        path.moveTo(radius, height * 0.88f)
        path.cubicTo(
            width * 0.95f, height * 0.55f,
            width.toFloat(), height * 0.35f,
            width.toFloat(), radius
        )
        path.arcTo(RectF(0f, 0f, width.toFloat(), width.toFloat()), 0f, -180f, false)
        path.cubicTo(
            0f, height * 0.35f,
            width * 0.05f, height * 0.55f,
            radius, height * 0.88f
        )
        path.close()
        canvas.drawPath(path, paint)

        // 绘制白色同心圆
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius * 0.65f, whitePaint)

        // 绘制居中文本/数字/徽标
        if (text.isNotBlank()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mainColor
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = if (text.length > 2) radius * 0.65f else radius * 0.85f
            }
            val fontMetrics = textPaint.fontMetrics
            val baseline = radius - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(text, radius, baseline, textPaint)
        }

        bitmapCache[cacheKey] = bitmap
        return bitmap
    }
}
