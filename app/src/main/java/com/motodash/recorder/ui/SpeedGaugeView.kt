package com.motodash.recorder.ui
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.roundToInt
/**
 * 速度仪表盘：环形进度 + 大号数字 + 单位。
 * 视觉：霓虹青绿渐变弧 + 暗色轨道。
 */
class SpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1AFFFFFF")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#33FFFFFF")
    }
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFFFFF")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val arcRect = RectF()
    private var displaySpeed = 0f
    private var animator: ValueAnimator? = null
    /** 最大刻度 */
    var maxSpeed: Float = 180f
        set(value) {
            field = value.coerceAtLeast(60f)
            invalidate()
        }
    /** 当前速度 km/h */
    var speed: Float = 0f
        set(value) {
            val target = value.coerceIn(0f, maxSpeed)
            animateTo(target)
        }
    /** 副标题（如方位） */
    var subtitle: String = ""
        set(value) {
            field = value
            invalidate()
        }
    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displaySpeed, target).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displaySpeed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - 24f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        // 1. 刻度（每 30 度一根）
        val startAngle = 135f
        val sweepTotal = 270f
        for (i in 0..9) {
            val angle = startAngle + sweepTotal * i / 9f
            val rad = Math.toRadians(angle.toDouble())
            val inner = radius - 26f
            val outer = radius - 14f
            val sx = cx + (inner * Math.cos(rad)).toFloat()
            val sy = cy + (inner * Math.sin(rad)).toFloat()
            val ex = cx + (outer * Math.cos(rad)).toFloat()
            val ey = cy + (outer * Math.sin(rad)).toFloat()
            canvas.drawLine(sx, sy, ex, ey, tickPaint)
        }
        // 2. 轨道
        canvas.drawArc(arcRect, startAngle, sweepTotal, false, trackPaint)
        // 3. 进度弧（渐变）
        val ratio = (displaySpeed / maxSpeed).coerceIn(0f, 1f)
        if (ratio > 0.001f) {
            progressPaint.shader = LinearGradient(
                cx - radius, cy - radius, cx + radius, cy + radius,
                intArrayOf(Color.parseColor("#FF22D3EE"), Color.parseColor("#FF00E5A0")),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawArc(arcRect, startAngle, sweepTotal * ratio, false, progressPaint)
        }
        // 4. 大号速度数字
        speedTextPaint.textSize = radius * 0.62f
        val textY = cy + speedTextPaint.textSize * 0.34f
        canvas.drawText(displaySpeed.roundToInt().toString(), cx, textY, speedTextPaint)
        // 5. 单位
        unitPaint.textSize = radius * 0.16f
        canvas.drawText("km/h", cx, textY + radius * 0.26f, unitPaint)
        // 6. 副标题（方位）
        if (subtitle.isNotBlank()) {
            labelPaint.textSize = radius * 0.15f
            canvas.drawText(subtitle, cx, cy - radius * 0.42f, labelPaint)
        }
    }
    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}