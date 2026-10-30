package nodomain.freeyourgadget.gadgetbridge.workouts

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.HeartRateWidget
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The heart rate of one workout against time. The five heart rate zones are shaded bands behind the line, and the
 * line itself takes the color of the zone it is in. The line draws itself in from the left.
 */
class WorkoutHrChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.sora_regular)
    private val fontSemi: Typeface? = ResourcesCompat.getFont(context, R.font.sora_semibold)
    private val colorText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val colorDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.6f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()
    private val rect = RectF()

    private var points: List<HeartPoint> = emptyList()
    private var durationMinutes = 0f
    private var maxHeartRate = 180
    private var progress = 1f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    fun setData(points: List<HeartPoint>, durationMinutes: Int, maxHeartRate: Int) {
        this.points = points.sortedBy { it.minute }
        this.durationMinutes = max(durationMinutes.toFloat(), this.points.lastOrNull()?.minute ?: 0f).coerceAtLeast(1f)
        this.maxHeartRate = maxHeartRate
        if (isAttachedToWindow) animator.start() else progress = 1f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (points.isNotEmpty()) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val left = paddingLeft + dp(34f)
        val right = width - paddingRight - dp(8f)
        val top = paddingTop + dp(12f)
        val bottom = height - paddingBottom - dp(22f)
        val plotW = right - left
        val plotH = bottom - top
        if (plotW <= 0f || plotH <= 0f) return

        val floors = HeartRateWidget.zoneFloors(maxHeartRate)
        val lowest = points.minOf { it.bpm }
        val highest = points.maxOf { it.bpm }
        val yLow = (floor((min(lowest, floors[0]) - 6) / 10f) * 10f).coerceAtLeast(30f)
        val yHigh = ceil((max(highest, floors[2]) + 6) / 10f) * 10f
        fun yOf(bpm: Float): Float = bottom - (bpm - yLow) / (yHigh - yLow) * plotH
        fun xOf(minute: Float): Float = left + minute / durationMinutes * plotW

        // Zone bands: zone k runs from its floor to the next one, and the last zone up to the top
        for (zone in 1..5) {
            val from = floors[zone - 1].toFloat()
            val to = if (zone < 5) floors[zone].toFloat() else yHigh
            if (to <= yLow || from >= yHigh) continue
            fillPaint.shader = null
            fillPaint.color = ColorUtils.setAlphaComponent(WidgetColors.hrZone(zone), 30)
            rect.set(left, yOf(min(to, yHigh)), right, yOf(max(from, yLow)))
            canvas.drawRect(rect, fillPaint)
        }

        // Axis labels: the floors of the zones in view, and the minutes along the bottom
        textPaint.typeface = fontRegular
        textPaint.textSize = sp(9.5f)
        textPaint.color = colorDim
        textPaint.textAlign = Paint.Align.RIGHT
        for (zone in 1..5) {
            val value = floors[zone - 1]
            if (value < yLow || value > yHigh) continue
            canvas.drawText(value.toString(), left - dp(5f), yOf(value.toFloat()) + sp(3f), textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        val ticks = if (durationMinutes >= 90f) 4 else 3
        for (i in 0..ticks) {
            val minute = durationMinutes * i / ticks
            canvas.drawText(Math.round(minute).toString() + if (i == ticks) "′" else "", xOf(minute), height - paddingBottom - dp(5f), textPaint)
        }

        // The line, colored by the zone each height falls in
        path.reset()
        fillPath.reset()
        var previousX = 0f
        var previousY = 0f
        for ((i, p) in points.withIndex()) {
            val x = xOf(p.minute)
            val y = yOf(p.bpm.toFloat())
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                val mid = (previousX + x) / 2f
                path.cubicTo(mid, previousY, mid, y, x, y)
                fillPath.cubicTo(mid, previousY, mid, y, x, y)
            }
            previousX = x
            previousY = y
        }
        fillPath.lineTo(previousX, bottom)
        fillPath.close()

        val edges = floatArrayOf(0f) + FloatArray(5) { ((yOf(floors[4 - it].toFloat()) - top) / plotH).coerceIn(0f, 1f) } + 1f
        val positions = FloatArray(6) { (edges[it] + edges[it + 1]) / 2f }
        val colors = IntArray(6) { WidgetColors.hrZone(5 - it) }
        linePaint.shader = LinearGradient(0f, top, 0f, bottom, colors, positions, Shader.TileMode.CLAMP)

        canvas.save()
        canvas.clipRect(left - dp(2f), 0f, left + (plotW + dp(10f)) * progress, height.toFloat())
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom, ColorUtils.setAlphaComponent(WidgetColors.hrZone(3), 70), ColorUtils.setAlphaComponent(WidgetColors.hrZone(1), 0), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(path, linePaint)

        // The peak
        val peak = points.maxByOrNull { it.bpm }!!
        val px = xOf(peak.minute)
        val py = yOf(peak.bpm.toFloat())
        fillPaint.color = WidgetColors.hrZone(HeartRateWidget.zoneFor(peak.bpm, maxHeartRate))
        canvas.drawCircle(px, py, dp(4.5f), fillPaint)
        textPaint.typeface = fontSemi
        textPaint.color = colorText
        textPaint.textAlign = if (px > left + plotW * 0.85f) Paint.Align.RIGHT else Paint.Align.CENTER
        canvas.drawText(peak.bpm.toString(), px, py - dp(9f), textPaint)
        canvas.restore()
    }
}
