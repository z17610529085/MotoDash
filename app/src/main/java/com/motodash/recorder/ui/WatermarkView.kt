package com.motodash.recorder.ui
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
/**
 * 叠加在预览之上的水印：速度、坐标、时间、录制状态。
 * 注意：这是 UI 叠加层，不会烧录进视频文件（CameraX 不支持直接烧录）。
 * 如需烧录，需要 OpenGL 后处理，此处提供 UI 层水印 + 视频元数据写入。
 */
class WatermarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5A0")
        textSize = 34f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    var speedKmh: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var latitude: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var longitude: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var showGps: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    var recording: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var segmentLabel: String = ""
        set(value) {
            field = value
            invalidate()
        }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 36f
        var y = padding + paint.textSize
        // 速度
        val speedText = String.format(Locale.US, "%3d km/h", speedKmh.roundToInt())
        canvas.drawText(speedText, padding, y, accentPaint)
        y += paint.textSize + 14f
        if (showGps) {
            val coord = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
            canvas.drawText(coord, padding, y, paint)
            y += paint.textSize + 14f
        }
        canvas.drawText(timeFormat.format(Date()), padding, y, paint)
        if (recording) {
            y += paint.textSize + 18f
            canvas.drawCircle(padding + 8f, y - 10f, 8f, accentPaint)
            canvas.drawText("REC $segmentLabel", padding + 26f, y, paint)
        }
    }
}