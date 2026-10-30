package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.HeartRateWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.LiveHeartRateWidget
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The live heart rate widget: the big number, a line that scrolls as the band reports each beat rate, and below it the
 * analysis of the day. It asks the band for live measurements only while it is on screen, because they use its battery.
 */
class LiveHeartRateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Point(val timeMs: Long, val bpm: Int)

    private val fontBold: Typeface? = ResourcesCompat.getFont(context, R.font.sora_bold)
    private val fontSemi: Typeface? = ResourcesCompat.getFont(context, R.font.sora_semibold)
    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.sora_regular)

    private val colorText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val colorDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val colorGrid = MaterialColors.getColor(this, R.attr.stat_tile_border, Color.DKGRAY)
    private val heartIcon = ContextCompat.getDrawable(context, R.drawable.ic_heartrate)?.mutate()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val path = Path()
    private val fillPath = Path()
    private val barRect = RectF()
    private val barClip = Path()

    private var title = ""
    private var data: LiveHeartRateWidget.Data? = null
    private var device: GBDevice? = null
    private val points = ArrayDeque<Point>()

    private var attached = false
    private var receiverRegistered = false
    private var measuring = false
    private var lastEnableAttemptMs = 0L

    private var smoothBpm = 0f
    private var yMin = 50f
    private var yMax = 120f
    private var lineColor = WidgetColors.hrZone(0)

    private val xs = FloatArray(MAX_POINTS + 2)
    private val ys = FloatArray(MAX_POINTS + 2)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DeviceService.ACTION_REALTIME_SAMPLES) return
            @Suppress("DEPRECATION")
            val incoming = intent.getParcelableExtra<GBDevice>(GBDevice.EXTRA_DEVICE)
            if (incoming == null || incoming != device) return
            @Suppress("DEPRECATION")
            val bpm = when (val sample = intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE)) {
                is ActivitySample -> sample.heartRate
                is HeartRateSample -> sample.heartRate
                else -> 0
            }
            if (HeartRateUtils.getInstance().isValidHeartRateValue(bpm)) {
                addPoint(Point(System.currentTimeMillis(), bpm))
            }
        }
    }

    fun setData(data: LiveHeartRateWidget.Data, title: String) {
        this.title = title
        this.data = data
        device = GBApplication.app().deviceManager.devices.firstOrNull { it.address == data.deviceAddress }
        for (point in data.recent) {
            if (points.none { it.timeMs == point.timeMs }) addPoint(point)
        }
        if (attached) startLive()
        invalidate()
    }

    fun setLoading(title: String) {
        this.title = title
        invalidate()
    }

    private fun addPoint(point: Point) {
        if (points.isNotEmpty() && points.last().timeMs >= point.timeMs) return
        points.addLast(point)
        while (points.size > MAX_POINTS) points.removeFirst()
        contentDescription = context.getString(R.string.widget_hr_description, title, point.bpm)
        postInvalidateOnAnimation()
    }

    //
    // Live measurement, only while the widget is on screen
    //

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (windowVisibility == VISIBLE) startLive()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopLive()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!attached) return
        if (visibility == VISIBLE) startLive() else stopLive()
    }

    private fun startLive() {
        if (device == null || receiverRegistered) return
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(DeviceService.ACTION_REALTIME_SAMPLES))
        receiverRegistered = true
        postInvalidateOnAnimation()
    }

    private fun stopLive() {
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
            receiverRegistered = false
        }
        if (measuring) {
            device?.let { GBApplication.deviceService(it).onEnableRealtimeHeartRateMeasurement(false) }
            measuring = false
        }
    }

    /** Starts the band's live measurement as soon as it is connected, which can be after the widget appeared. */
    private fun ensureMeasuring(now: Long) {
        val dev = device ?: return
        if (!receiverRegistered || measuring || !dev.isInitialized) return
        if (now - lastEnableAttemptMs < ENABLE_RETRY_MS) return
        lastEnableAttemptMs = now
        GBApplication.deviceService(dev).onEnableRealtimeHeartRateMeasurement(true)
        measuring = true
    }

    //
    // Drawing
    //

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun maxHeartRate() = data?.maxHeartRate ?: DEFAULT_MAX_HEART_RATE

    private fun zoneOf(bpm: Float) = HeartRateWidget.zoneFor(bpm.toInt(), maxHeartRate())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        ensureMeasuring(now)
        stepAnimation(now)

        val w = width.toFloat()
        val pad = dp(16f)
        var top = pad
        drawHeader(canvas, w, pad, top, now)
        top += dp(24f) + dp(8f)
        drawBpm(canvas, w, pad, top, now)
        top += dp(76f) + dp(4f)
        val chartHeight = dp(156f)
        drawChart(canvas, w, pad, top, chartHeight, now)
        top += chartHeight + dp(16f)
        data?.let {
            drawZones(canvas, w, pad, top, it)
            top += dp(22f) + dp(32f) + dp(10f)
            drawStats(canvas, w, pad, top, it)
        }
        if (receiverRegistered) postInvalidateOnAnimation()
    }

    /** Eases the number, the line color and the scale towards their targets, a little each frame. */
    private fun stepAnimation(now: Long) {
        val latest = points.lastOrNull()
        if (latest != null) {
            smoothBpm = if (smoothBpm == 0f) latest.bpm.toFloat() else smoothBpm + (latest.bpm - smoothBpm) * 0.12f
            lineColor = ColorUtils.blendARGB(lineColor, WidgetColors.hrZone(zoneOf(smoothBpm)), 0.08f)
        }
        val visible = points.filter { it.timeMs >= now - WINDOW_MS - DISPLAY_DELAY_MS }
        if (visible.isNotEmpty()) {
            // Centered on the data, with a minimum span so that a steady heart rate does not look like a storm
            val low = visible.minOf { it.bpm }
            val high = visible.maxOf { it.bpm }
            val span = max(high - low + 16f, MIN_SPAN)
            val lo = max(35f, (high + low) / 2f - span / 2f)
            yMin += (lo - yMin) * 0.08f
            yMax += (lo + span - yMax) * 0.08f
        }
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        font: Typeface?,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        textPaint.typeface = font
        textPaint.textSize = sp(size)
        textPaint.color = color
        textPaint.textAlign = align
        canvas.drawText(value, x, baseline, textPaint)
    }

    private fun isLive(now: Long): Boolean = points.lastOrNull()?.let { now - it.timeMs < LIVE_TIMEOUT_MS } == true

    private fun drawHeader(canvas: Canvas, w: Float, pad: Float, top: Float, now: Long) {
        val live = isLive(now)
        val icon = dp(20f)
        heartIcon?.let {
            val pulse = if (live) beatPulse(now) else 1f
            val size = icon * pulse
            val cx = pad + icon / 2
            val cy = top + dp(12f)
            it.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            it.setTint(lineColor)
            it.draw(canvas)
        }
        text(canvas, title, pad + icon + dp(8f), top + dp(17f), 13f, colorDim, fontSemi)

        val label = context.getString(if (live) R.string.live_hr_live else R.string.live_hr_waiting)
        textPaint.typeface = fontBold
        textPaint.textSize = sp(10f)
        val labelWidth = textPaint.measureText(label)
        val chipWidth = labelWidth + dp(30f)
        val chipLeft = w - pad - chipWidth
        barRect.set(chipLeft, top + dp(2f), w - pad, top + dp(22f))
        shapePaint.style = Paint.Style.FILL
        shapePaint.shader = null
        shapePaint.color = ColorUtils.setAlphaComponent(if (live) lineColor else colorDim, 40)
        canvas.drawRoundRect(barRect, dp(10f), dp(10f), shapePaint)
        val blink = if (live) 0.55f + 0.45f * (0.5f + 0.5f * sin(now / 350.0).toFloat()) else 0.5f
        shapePaint.color = ColorUtils.setAlphaComponent(if (live) lineColor else colorDim, (255 * blink).toInt())
        canvas.drawCircle(chipLeft + dp(11f), top + dp(12f), dp(3.5f), shapePaint)
        text(canvas, label, chipLeft + dp(20f), top + dp(16f), 10f, if (live) lineColor else colorDim, fontBold)
    }

    /** A quick swell at each beat, at the pace of the heart rate. */
    private fun beatPulse(now: Long): Float {
        if (smoothBpm < 30f) return 1f
        val period = 60000f / smoothBpm
        val phase = (now % period.toLong().coerceAtLeast(1L)) / period
        return if (phase < BEAT_SHARE) 1f + 0.3f * sin(phase / BEAT_SHARE * PI).toFloat() else 1f
    }

    private fun drawBpm(canvas: Canvas, w: Float, pad: Float, top: Float, now: Long) {
        val has = smoothBpm > 0f
        val number = if (has) smoothBpm.toInt().toString() else "--"
        val color = if (has) lineColor else colorDim
        text(canvas, number, pad, top + dp(58f), 58f, color, fontBold)
        textPaint.typeface = fontBold
        textPaint.textSize = sp(58f)
        val numberWidth = textPaint.measureText(number)
        text(canvas, context.getString(R.string.bpm), pad + numberWidth + dp(8f), top + dp(58f), 14f, colorDim, fontRegular)

        if (has) {
            val zone = zoneOf(smoothBpm)
            text(canvas, context.getString(ZONE_NAMES[zone]), w - pad, top + dp(34f), 15f, WidgetColors.hrZone(zone), fontSemi, Paint.Align.RIGHT)
            val percent = (smoothBpm * 100f / maxHeartRate()).toInt()
            text(canvas, context.getString(R.string.live_hr_percent_max, percent), w - pad, top + dp(54f), 11f, colorDim, fontRegular, Paint.Align.RIGHT)
        } else if (device?.isInitialized != true) {
            text(canvas, context.getString(R.string.live_hr_no_band), w - pad, top + dp(44f), 12f, colorDim, fontRegular, Paint.Align.RIGHT)
        }
    }

    private fun drawChart(canvas: Canvas, w: Float, pad: Float, top: Float, height: Float, now: Long) {
        val labelSpace = dp(30f)
        val left = pad
        val right = w - pad - labelSpace
        val inner = dp(10f)
        val bottom = top + height
        val span = max(yMax - yMin, 1f)
        fun yFor(bpm: Float) = bottom - inner - (bpm - yMin) / span * (height - 2 * inner)

        // The zone floors, with their heart rate on the right
        val floors = HeartRateWidget.zoneFloors(maxHeartRate())
        gridPaint.color = ColorUtils.setAlphaComponent(colorGrid, 160)
        gridPaint.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(5f)), 0f)
        for (floor in floors) {
            if (floor <= yMin || floor >= yMax) continue
            val y = yFor(floor.toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
            text(canvas, floor.toString(), w - pad, y + dp(3.5f), 9f, colorDim, fontRegular, Paint.Align.RIGHT)
        }

        val displayTime = now - DISPLAY_DELAY_MS
        val start = displayTime - WINDOW_MS
        var count = 0
        var previous: Point? = null
        for (point in points) {
            if (point.timeMs > displayTime) {
                // Interpolate the head between the last point and the first newer one
                val before = previous
                if (before != null) {
                    val fraction = (displayTime - before.timeMs).toFloat() / (point.timeMs - before.timeMs)
                    xs[count] = right
                    ys[count] = yFor(before.bpm + (point.bpm - before.bpm) * fraction)
                    count++
                }
                previous = null
                break
            }
            if (point.timeMs >= start - WINDOW_MS / 10) {
                xs[count] = left + (point.timeMs - start).toFloat() / WINDOW_MS * (right - left)
                ys[count] = yFor(point.bpm.toFloat())
                count++
            }
            previous = point
        }
        if (previous != null && count > 0) {
            // No newer point yet: hold the last value up to the display time
            xs[count] = right
            ys[count] = ys[count - 1]
            count++
        }

        if (count < 2) {
            text(canvas, context.getString(R.string.live_hr_waiting_hint), (left + right) / 2, top + height / 2 - dp(12f), 12f, colorDim, fontRegular, Paint.Align.CENTER)
            return
        }

        buildPath(count)
        canvas.save()
        canvas.clipRect(left - dp(4f), top, right + dp(4f), bottom)

        fillPath.set(path)
        fillPath.lineTo(xs[count - 1], bottom)
        fillPath.lineTo(xs[0], bottom)
        fillPath.close()
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, ColorUtils.setAlphaComponent(lineColor, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)

        linePaint.shader = null
        linePaint.color = lineColor
        linePaint.alpha = 28
        linePaint.strokeWidth = dp(14f)
        canvas.drawPath(path, linePaint)
        linePaint.alpha = 60
        linePaint.strokeWidth = dp(8f)
        canvas.drawPath(path, linePaint)

        linePaint.alpha = 255
        linePaint.strokeWidth = dp(3f)
        linePaint.shader = zoneGradient(top, bottom, floors)
        canvas.drawPath(path, linePaint)
        linePaint.shader = null
        canvas.restore()

        // The head: a dot with a halo that swells at each beat
        val headX = xs[count - 1]
        val headY = ys[count - 1]
        val pulse = beatPulse(now)
        shapePaint.shader = null
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = ColorUtils.setAlphaComponent(lineColor, 55)
        canvas.drawCircle(headX, headY, dp(10f) * pulse, shapePaint)
        shapePaint.color = lineColor
        canvas.drawCircle(headX, headY, dp(4.5f), shapePaint)
        shapePaint.color = Color.WHITE
        canvas.drawCircle(headX, headY, dp(2f), shapePaint)
    }

    /** A smooth curve through the points (Catmull-Rom turned into Béziers). */
    private fun buildPath(count: Int) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 0 until count - 1) {
            val before = max(i - 1, 0)
            val after = min(i + 2, count - 1)
            path.cubicTo(
                xs[i] + (xs[i + 1] - xs[before]) / 6f, ys[i] + (ys[i + 1] - ys[before]) / 6f,
                xs[i + 1] - (xs[after] - xs[i]) / 6f, ys[i + 1] - (ys[after] - ys[i]) / 6f,
                xs[i + 1], ys[i + 1],
            )
        }
    }

    /** The line takes the color of the zone each stretch of it is in. */
    private fun zoneGradient(top: Float, bottom: Float, floors: IntArray): LinearGradient {
        val span = max(yMax - yMin, 1f)
        val colors = ArrayList<Int>()
        val positions = ArrayList<Float>()
        colors += WidgetColors.hrZone(5)
        positions += 0f
        for (zone in floors.size - 1 downTo 0) {
            val at = ((yMax - floors[zone]) / span).coerceIn(0f, 1f)
            colors += WidgetColors.hrZone(zone + 1)
            positions += max((at - 0.03f).coerceIn(0f, 1f), positions.last())
            colors += WidgetColors.hrZone(zone)
            positions += max((at + 0.03f).coerceIn(0f, 1f), positions.last())
        }
        colors += WidgetColors.hrZone(0)
        positions += 1f
        return LinearGradient(0f, top, 0f, bottom, colors.toIntArray(), positions.toFloatArray(), Shader.TileMode.CLAMP)
    }

    /** Today's time at rest and in each zone as one bar, with the minutes of each underneath. */
    private fun drawZones(canvas: Canvas, w: Float, pad: Float, top: Float, data: LiveHeartRateWidget.Data) {
        val total = max(data.zoneSeconds.sum(), 1)
        barRect.set(pad, top, w - pad, top + dp(12f))
        barClip.reset()
        barClip.addRoundRect(barRect, dp(6f), dp(6f), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(barClip)
        shapePaint.shader = null
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = ColorUtils.setAlphaComponent(colorGrid, 120)
        canvas.drawRect(barRect, shapePaint)
        var x = pad
        for (zone in data.zoneSeconds.indices) {
            val share = data.zoneSeconds[zone].toFloat() / total * (w - 2 * pad)
            if (share <= 0f) continue
            shapePaint.color = WidgetColors.hrZone(zone)
            canvas.drawRect(x, top, x + share, top + dp(12f), shapePaint)
            x += share
        }
        canvas.restore()

        // The legend is two rows of three, so that the zone names and the time spent fit
        val cell = (w - 2 * pad) / 3
        for (zone in data.zoneSeconds.indices) {
            val cx = pad + cell * (zone % 3)
            val rowTop = top + dp(28f) + dp(16f) * (zone / 3)
            shapePaint.color = WidgetColors.hrZone(zone)
            canvas.drawCircle(cx + dp(4f), rowTop - dp(3.5f), dp(3.5f), shapePaint)
            val name = if (zone == 0) context.getString(ZONE_NAMES[0]) else ZONE_SHORT[zone]
            val label = "$name ${formatMinutes(data.zoneSeconds[zone])}"
            text(canvas, label, cx + dp(13f), rowTop, 10f, colorDim, fontRegular)
        }
    }

    private fun formatMinutes(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}" else "${minutes}m"
    }

    private fun drawStats(canvas: Canvas, w: Float, pad: Float, top: Float, data: LiveHeartRateWidget.Data) {
        val empty = context.getString(R.string.stats_empty_value)
        fun value(number: Int) = if (number > 0) number.toString() else empty
        val cells = listOf(
            value(data.resting) to context.getString(R.string.hr_resting),
            value(data.average) to context.getString(R.string.live_hr_average),
            value(data.maximum) to context.getString(R.string.hr_maximum),
            value(data.minimum) to context.getString(R.string.hr_minimum),
            value(data.trimp) to context.getString(R.string.live_hr_trimp),
            (if (data.vo2max > 0) "~${data.vo2max}" else empty) to context.getString(R.string.live_hr_vo2),
        )
        val cell = (w - 2 * pad) / 3
        for ((index, entry) in cells.withIndex()) {
            val x = pad + cell * (index % 3)
            val y = top + dp(52f) * (index / 3)
            text(canvas, entry.first, x, y + dp(22f), 20f, colorText, fontBold)
            text(canvas, entry.second, x, y + dp(40f), 10f, colorDim, fontRegular)
        }
    }

    private companion object {
        const val MAX_POINTS = 120
        const val WINDOW_MS = 60_000L
        const val DISPLAY_DELAY_MS = 1_500L
        const val LIVE_TIMEOUT_MS = 8_000L
        const val ENABLE_RETRY_MS = 3_000L
        const val BEAT_SHARE = 0.2f
        const val DEFAULT_MAX_HEART_RATE = 190
        const val MIN_SPAN = 30f

        val ZONE_NAMES = intArrayOf(
            R.string.live_hr_zone_0,
            R.string.live_hr_zone_1,
            R.string.live_hr_zone_2,
            R.string.live_hr_zone_3,
            R.string.live_hr_zone_4,
            R.string.live_hr_zone_5,
        )
        val ZONE_SHORT = arrayOf("R", "Z1", "Z2", "Z3", "Z4", "Z5")
    }
}
