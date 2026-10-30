package nodomain.freeyourgadget.gadgetbridge.health

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One chart for the health report: a line, bars, or stacked bars, with one slot per day. It can show a shaded band, a
 * target line and a dashed baseline, and it draws itself in when it gets new data. Days without a value are left empty.
 */
class HealthChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Style { LINE, BARS, STACK }

    class Series(val values: FloatArray, val color: Int)

    class Spec(
        val style: Style,
        val series: List<Series>,
        /** Epoch second at which the first slot's day starts. */
        val firstDayStart: Long,
        /** How many of the last slots to show. */
        val visibleDays: Int,
        val bandLow: Float = Float.NaN,
        val bandHigh: Float = Float.NaN,
        val target: Float = Float.NaN,
        val baseline: Float = Float.NaN,
        val minimum: Float = Float.NaN,
        val maximum: Float = Float.NaN,
        val compact: Boolean = false,
        val decimals: Int = 0,
    )

    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.sora_regular)
    private val fontSemi: Typeface? = ResourcesCompat.getFont(context, R.font.sora_semibold)
    private val colorText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val colorDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val colorGrid = MaterialColors.getColor(this, R.attr.stat_tile_border, Color.DKGRAY)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()
    private val rect = RectF()

    private var spec: Spec? = null
    private var progress = 1f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 800
        interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    private var yLow = 0f
    private var yHigh = 1f

    fun setSpec(spec: Spec) {
        this.spec = spec
        computeRange(spec)
        if (isAttachedToWindow) animator.start() else progress = 1f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (spec != null) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun firstShown(spec: Spec): Int = max(0, (spec.series.firstOrNull()?.values?.size ?: 0) - spec.visibleDays)

    private fun total(spec: Spec, index: Int): Float {
        var sum = 0f
        var any = false
        for (s in spec.series) {
            val v = s.values.getOrNull(index) ?: continue
            if (!v.isNaN()) {
                sum += v
                any = true
            }
        }
        return if (any) sum else Float.NaN
    }

    private fun computeRange(spec: Spec) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val from = firstShown(spec)
        val size = spec.series.firstOrNull()?.values?.size ?: 0
        for (i in from until size) {
            if (spec.style == Style.STACK) {
                val t = total(spec, i)
                if (!t.isNaN()) {
                    lo = min(lo, t)
                    hi = max(hi, t)
                }
            } else {
                for (s in spec.series) {
                    val v = s.values[i]
                    if (!v.isNaN()) {
                        lo = min(lo, v)
                        hi = max(hi, v)
                    }
                }
            }
        }
        for (extra in listOf(spec.bandLow, spec.bandHigh, spec.target, spec.baseline)) {
            if (!extra.isNaN()) {
                lo = min(lo, extra)
                hi = max(hi, extra)
            }
        }
        if (lo > hi) {
            lo = 0f
            hi = 1f
        }
        if (spec.style != Style.LINE) lo = 0f
        val pad = max((hi - lo) * 0.12f, if (spec.style == Style.LINE) 1.5f else 0f)
        yLow = if (spec.style == Style.LINE) lo - pad else 0f
        yHigh = if (hi <= yLow) yLow + 1f else hi + pad
        if (!spec.minimum.isNaN()) yLow = spec.minimum
        if (!spec.maximum.isNaN()) yHigh = spec.maximum
    }

    private fun format(value: Float, decimals: Int): String =
        if (decimals <= 0) Math.round(value).toString() else String.format(Locale.getDefault(), "%.${decimals}f", value)

    override fun onDraw(canvas: Canvas) {
        val spec = spec ?: return
        val values = spec.series.firstOrNull()?.values ?: return
        val from = firstShown(spec)
        val count = values.size - from
        if (count <= 0) return

        val axisWidth = if (spec.compact) dp(6f) else dp(34f)
        val left = paddingLeft + axisWidth
        val right = width - paddingRight - dp(6f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - if (spec.compact) dp(4f) else dp(22f)
        val plotW = right - left
        val plotH = bottom - top
        if (plotW <= 0f || plotH <= 0f) return

        fun yOf(value: Float): Float = bottom - (value - yLow) / (yHigh - yLow) * plotH
        val slot = plotW / count
        fun xOf(i: Int): Float = left + slot * (i + 0.5f)

        // Band, grid and axis labels
        if (!spec.bandLow.isNaN() && !spec.bandHigh.isNaN()) {
            fillPaint.shader = null
            fillPaint.color = ColorUtils.setAlphaComponent(spec.series[0].color, 34)
            rect.set(left, yOf(min(spec.bandHigh, yHigh)), right, yOf(max(spec.bandLow, yLow)))
            canvas.drawRoundRect(rect, dp(6f), dp(6f), fillPaint)
        }
        gridPaint.color = ColorUtils.setAlphaComponent(colorGrid, 150)
        textPaint.typeface = fontRegular
        textPaint.textSize = sp(10f)
        textPaint.color = colorDim
        textPaint.textAlign = Paint.Align.RIGHT
        if (!spec.compact) {
            for (step in 0..2) {
                val value = yLow + (yHigh - yLow) * step / 2f
                val y = yOf(value)
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawText(format(value, spec.decimals), left - dp(6f), y + sp(3.5f), textPaint)
            }
        } else {
            canvas.drawLine(left, bottom, right, bottom, gridPaint)
        }

        // Target and baseline
        if (!spec.target.isNaN()) {
            dashPaint.color = ColorUtils.setAlphaComponent(colorText, 170)
            val y = yOf(spec.target)
            canvas.drawLine(left, y, right, y, dashPaint)
            if (!spec.compact) {
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = colorText
                canvas.drawText(format(spec.target, spec.decimals), right, y - dp(4f), textPaint)
            }
        }
        if (!spec.baseline.isNaN()) {
            dashPaint.color = ColorUtils.setAlphaComponent(spec.series[0].color, 190)
            val y = yOf(spec.baseline)
            canvas.drawLine(left, y, right, y, dashPaint)
        }

        // X labels
        if (!spec.compact) {
            textPaint.textAlign = Paint.Align.CENTER
            val zone = ZoneId.systemDefault()
            val weekly = count <= 14
            for (i in 0 until count) {
                val index = from + i
                val date = Instant.ofEpochSecond(spec.firstDayStart + index * 86_400L + 3600L).atZone(zone).toLocalDate()
                val label = when {
                    weekly -> date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    (count - 1 - i) % 7 == 0 -> "${date.dayOfMonth}/${date.monthValue}"
                    else -> null
                } ?: continue
                val last = i == count - 1
                textPaint.typeface = if (last) fontSemi else fontRegular
                textPaint.color = if (last) colorText else colorDim
                canvas.drawText(label, xOf(i), height - paddingBottom - dp(5f), textPaint)
            }
        }

        // Reveal from the left
        canvas.save()
        canvas.clipRect(left - dp(2f), 0f, left + (plotW + dp(4f)) * progress, height.toFloat())
        when (spec.style) {
            Style.LINE -> drawLine(canvas, spec, from, count, ::xOf, ::yOf, bottom)
            Style.BARS, Style.STACK -> drawBars(canvas, spec, from, count, slot, ::xOf, ::yOf, bottom)
        }
        canvas.restore()
    }

    private fun drawLine(canvas: Canvas, spec: Spec, from: Int, count: Int, xOf: (Int) -> Float, yOf: (Float) -> Float, bottom: Float) {
        val series = spec.series[0]
        val color = series.color
        path.reset()
        fillPath.reset()
        var runStart = -1
        var prevX = 0f
        var prevY = 0f
        var lastX = Float.NaN
        var lastY = Float.NaN
        fun closeRun(endX: Float) {
            if (runStart >= 0) {
                fillPath.lineTo(endX, bottom)
                fillPath.close()
            }
            runStart = -1
        }
        for (i in 0 until count) {
            val v = series.values[from + i]
            if (v.isNaN()) {
                closeRun(prevX)
                continue
            }
            val x = xOf(i)
            val y = yOf(v)
            if (runStart < 0) {
                runStart = i
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                val mid = (prevX + x) / 2f
                path.cubicTo(mid, prevY, mid, y, x, y)
                fillPath.cubicTo(mid, prevY, mid, y, x, y)
            }
            prevX = x
            prevY = y
            lastX = x
            lastY = y
        }
        closeRun(prevX)

        fillPaint.shader = LinearGradient(0f, 0f, 0f, bottom, ColorUtils.setAlphaComponent(color, 90), ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null

        linePaint.color = color
        linePaint.setShadowLayer(dp(6f), 0f, 0f, ColorUtils.setAlphaComponent(color, 140))
        canvas.drawPath(path, linePaint)
        linePaint.clearShadowLayer()

        // Dots on isolated days and on the last one
        fillPaint.color = color
        for (i in 0 until count) {
            val v = series.values[from + i]
            if (v.isNaN()) continue
            val alone = (i == 0 || series.values[from + i - 1].isNaN()) && (i == count - 1 || series.values[from + i + 1].isNaN())
            if (alone || i == count - 1) canvas.drawCircle(xOf(i), yOf(v), dp(if (i == count - 1) 4.5f else 3f), fillPaint)
        }
        if (!lastX.isNaN()) {
            fillPaint.color = ColorUtils.setAlphaComponent(color, 60)
            canvas.drawCircle(lastX, lastY, dp(9f), fillPaint)
        }
    }

    private fun drawBars(
        canvas: Canvas,
        spec: Spec,
        from: Int,
        count: Int,
        slot: Float,
        xOf: (Int) -> Float,
        yOf: (Float) -> Float,
        bottom: Float,
    ) {
        val barWidth = slot * if (count > 14) 0.66f else 0.58f
        val radius = min(barWidth / 2f, dp(5f))
        fillPaint.shader = null
        for (i in 0 until count) {
            val index = from + i
            val x = xOf(i)
            var base = 0f
            val stacked = spec.style == Style.STACK
            for ((k, s) in spec.series.withIndex()) {
                val v = s.values[index]
                if (v.isNaN() || v <= 0f) continue
                val topValue = if (stacked) base + v else v
                val yTop = yOf(min(topValue, yHigh))
                val yBottom = if (stacked) yOf(base) else bottom
                // Bars that reach the target are bright; the others are dimmer
                val reached = spec.target.isNaN() || stacked || v >= spec.target
                fillPaint.color = ColorUtils.setAlphaComponent(s.color, if (reached) 255 else 120)
                val isTop = !stacked || spec.series.drop(k + 1).none { !(it.values[index].isNaN() || it.values[index] <= 0f) }
                rect.set(x - barWidth / 2f, yTop, x + barWidth / 2f, yBottom)
                if (isTop) {
                    canvas.drawRoundRect(rect, radius, radius, fillPaint)
                    if (yBottom - yTop > radius) canvas.drawRect(x - barWidth / 2f, yTop + radius, x + barWidth / 2f, yBottom, fillPaint)
                } else {
                    canvas.drawRect(rect, fillPaint)
                }
                if (stacked) base += v
            }
        }
    }
}
