package nodomain.freeyourgadget.gadgetbridge.workouts

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.ActivityIconView
import nodomain.freeyourgadget.gadgetbridge.health.HealthChartView
import nodomain.freeyourgadget.gadgetbridge.health.HealthFormat
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The top of the Workouts section: the latest workout of the chosen minimum length with its heart rate chart and
 * zones, a chart of the last two weeks, and a table of the most recent workouts. It is rebuilt when the data changes.
 */
class WorkoutInsightsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called with the id of the workout summary to open. */
    var onOpen: ((Long) -> Unit)? = null

    /** Called with the new minimum length in minutes. */
    var onThreshold: ((Int) -> Unit)? = null

    private val fontBold: Typeface? = ResourcesCompat.getFont(context, R.font.sora_bold)
    private val fontSemi: Typeface? = ResourcesCompat.getFont(context, R.font.sora_semibold)
    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.sora_regular)
    private val colorText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val colorDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private var shownKey: String? = null

    init {
        orientation = VERTICAL
    }

    fun setInsights(insights: WorkoutInsights) {
        val key = key(insights)
        if (key == shownKey) return
        shownKey = key
        removeAllViews()
        addView(heroCard(insights))
        addView(weekCard(insights))
        if (insights.recent.isNotEmpty()) addView(tableCard(insights))
    }

    private fun key(i: WorkoutInsights): String =
        i.minMinutes.toString() + "|" + i.recent.joinToString(",") { "${it.summaryId}:${it.endMs}:${it.series.size}:${it.steps}" } + "|" + i.totalMinutes

    //
    // The latest workout
    //

    private fun heroCard(i: WorkoutInsights): View = card(hero = true) { box ->
        val last = i.last
        if (last == null) {
            text(box, context.getString(R.string.workouts_last_title), 12f, fontRegular, colorDim)
            text(box, context.getString(R.string.workouts_none_title, i.minMinutes), 18f, fontSemi, colorText, top = 4f)
            text(box, context.getString(R.string.workouts_none_hint), 12.5f, fontRegular, colorDim, top = 6f)
            thresholdRow(box, i.minMinutes, top = 14f)
            return@card
        }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = ActivityIconView(context).apply { setImageResource(last.kind.icon.takeIf { it != 0 } ?: R.drawable.ic_activity_exercise) }
        header.addView(icon, LayoutParams(dp(44f), dp(44f)))
        val titles = LinearLayout(context).apply { orientation = VERTICAL }
        text(titles, context.getString(R.string.workouts_last_title), 11.5f, fontRegular, colorDim)
        text(titles, last.name?.takeIf { it.isNotBlank() } ?: last.kind.getLabel(context), 17f, fontSemi, colorText)
        header.addView(titles, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) })
        box.addView(header, params())

        val whenLine = dateLabel(last.startMs) + " · " + timeLabel(last.startMs) + "–" + timeLabel(last.endMs)
        val timing = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        val big = makeText(durationLabel(last.durationMinutes), 34f, fontBold, colorText)
        big.setLineSpacing(0f, 1f)
        big.includeFontPadding = true
        big.setPadding(0, dp(4f), 0, dp(2f))
        big.contentDescription = context.getString(R.string.workouts_threshold_value, last.durationMinutes)
        val whenView = makeText(whenLine, 12f, fontRegular, colorDim)
        whenView.gravity = Gravity.END
        timing.addView(big, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        timing.addView(whenView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { bottomMargin = dp(6f) })
        box.addView(timing, params(top = 12f))

        if (last.auto) {
            val tag = text(box, "● " + context.getString(R.string.workouts_auto_tag), 11f, fontRegular, colorDim, top = 2f)
            val span = SpannableStringBuilder(tag.text)
            span.setSpan(ForegroundColorSpan(WidgetColors.activity), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tag.text = span
        }

        val stats = LinearLayout(context)
        val values = listOf(
            R.string.workouts_stat_avg_hr to if (last.averageHr > 0) last.averageHr.toString() else HealthFormat.DASH,
            R.string.workouts_stat_max_hr to if (last.maxHr > 0) last.maxHr.toString() else HealthFormat.DASH,
            R.string.workouts_stat_steps to HealthFormat.steps(last.steps.toFloat()),
            R.string.workouts_stat_load to if (last.trimp > 0) last.trimp.toString() else HealthFormat.DASH,
        )
        for ((n, pair) in values.withIndex()) {
            stats.addView(statTile(pair.second, context.getString(pair.first)), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (n == 0) 0 else dp(3f)
                marginEnd = if (n == values.size - 1) 0 else dp(3f)
            })
        }
        box.addView(stats, params(top = 14f))

        if (last.series.size >= 2) {
            val chart = WorkoutHrChartView(context)
            chart.setData(last.series, last.durationMinutes, i.maxHeartRate)
            box.addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, dp(176f)).apply { topMargin = dp(12f) })
            zoneBar(box, last)
        }

        text(box, context.getString(R.string.workouts_footer), 11f, fontRegular, colorDim, top = 14f)
        thresholdRow(box, i.minMinutes, top = 6f)
        box.setOnClickListener { onOpen?.invoke(last.summaryId) }
    }

    /** One stacked bar of the time in each zone, with the minutes of every zone that was visited under it. */
    private fun zoneBar(parent: LinearLayout, entry: WorkoutEntry) {
        val total = entry.zoneSeconds.sum()
        if (total <= 0) return
        text(parent, context.getString(R.string.workouts_zones_title), 12f, fontSemi, colorText, top = 12f)
        val bar = LinearLayout(context)
        val clip = GradientDrawable().apply {
            cornerRadius = dp(7f).toFloat()
            setColor(ColorUtils.setAlphaComponent(colorDim, 40))
        }
        bar.background = clip
        bar.clipToOutline = true
        for (zone in 0..5) {
            val seconds = entry.zoneSeconds[zone]
            if (seconds <= 0) continue
            val segment = View(context).apply { setBackgroundColor(WidgetColors.hrZone(zone)) }
            bar.addView(segment, LayoutParams(0, LayoutParams.MATCH_PARENT, seconds.toFloat()))
        }
        parent.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(14f)).apply { topMargin = dp(6f) })

        val names = intArrayOf(
            R.string.live_hr_zone_0, R.string.live_hr_zone_1, R.string.live_hr_zone_2,
            R.string.live_hr_zone_3, R.string.live_hr_zone_4, R.string.live_hr_zone_5,
        )
        val items = (0..5).filter { entry.zoneSeconds[it] >= 30 }
        for (chunk in items.chunked(2)) {
            val row = LinearLayout(context)
            for (zone in chunk) {
                val minutes = (entry.zoneSeconds[zone] / 60f).roundToInt()
                val label = SpannableStringBuilder("● ").append(context.getString(names[zone])).append("  ").append(minutes.toString() + "′")
                label.setSpan(ForegroundColorSpan(WidgetColors.hrZone(zone)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val view = makeText(label, 11f, fontRegular, colorDim)
                view.maxLines = 1
                view.ellipsize = TextUtils.TruncateAt.END
                row.addView(view, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            parent.addView(row, params(top = 5f))
        }
    }

    private fun thresholdRow(parent: LinearLayout, current: Int, top: Float) {
        text(parent, context.getString(R.string.workouts_threshold), 11f, fontRegular, colorDim, top = top)
        val row = LinearLayout(context)
        for ((n, minutes) in WorkoutDetector.THRESHOLDS.withIndex()) {
            val selected = minutes == current
            val chip = TextView(context).apply {
                text = context.getString(R.string.workouts_threshold_value, minutes)
                typeface = if (selected) fontSemi else fontRegular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (selected) Color.BLACK else colorText)
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(7f), dp(4f), dp(7f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18f).toFloat()
                    setColor(if (selected) WidgetColors.activity else ColorUtils.setAlphaComponent(colorDim, 38))
                }
                setOnClickListener { if (!selected) onThreshold?.invoke(minutes) }
            }
            row.addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = if (n == 0) 0 else dp(5f) })
        }
        parent.addView(row, params(top = 6f))
    }

    //
    // The last two weeks and the table
    //

    private fun weekCard(i: WorkoutInsights): View = card { box ->
        text(box, context.getString(R.string.workouts_14_days), 15f, fontSemi, colorText)
        text(box, context.getString(R.string.workouts_14_summary, i.totalCount, i.totalMinutes), 12f, fontRegular, colorDim, top = 2f)

        val chart = HealthChartView(context)
        chart.setSpec(
            HealthChartView.Spec(
                style = HealthChartView.Style.BARS,
                series = listOf(HealthChartView.Series(i.dailyMinutes, WidgetColors.activity)),
                firstDayStart = i.firstDayStart,
                visibleDays = WorkoutInsights.DAYS,
                minimum = 0f,
                maximum = if (i.dailyMinutes.all { it <= 0f }) 60f else Float.NaN,
            )
        )
        box.addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, dp(140f)).apply { topMargin = dp(10f) })
    }

    private fun tableCard(i: WorkoutInsights): View = card { box ->
        text(box, context.getString(R.string.workouts_table_title), 15f, fontSemi, colorText)
        val weights = floatArrayOf(1.5f, 2.2f, 1.2f, 0.9f, 0.9f, 1.1f)
        val head = row(
            listOf(R.string.workouts_col_date, R.string.workouts_col_type, R.string.workouts_col_duration, R.string.workouts_col_hr,
                R.string.workouts_col_max, R.string.workouts_col_steps).map { context.getString(it) },
            weights, fontRegular, colorDim, 10.5f,
        )
        box.addView(head, params(top = 10f))
        for ((n, w) in i.recent.withIndex()) {
            val cells = listOf(
                shortDate(w.startMs),
                w.name?.takeIf { it.isNotBlank() } ?: w.kind.getLabel(context),
                durationLabel(w.durationMinutes),
                if (w.averageHr > 0) w.averageHr.toString() else HealthFormat.DASH,
                if (w.maxHr > 0) w.maxHr.toString() else HealthFormat.DASH,
                HealthFormat.steps(w.steps.toFloat()),
            )
            val line = row(cells, weights, fontRegular, colorText, 12f)
            line.setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
            if (n % 2 == 0) {
                line.background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(ColorUtils.setAlphaComponent(colorDim, 26))
                }
            }
            line.setOnClickListener { onOpen?.invoke(w.summaryId) }
            box.addView(line, params(top = if (n == 0) 4f else 0f))
        }
    }

    private fun row(cells: List<String>, weights: FloatArray, font: Typeface?, color: Int, size: Float): LinearLayout {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        for ((n, value) in cells.withIndex()) {
            val view = TextView(context).apply {
                text = value
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setTextColor(color)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = if (n <= 1) Gravity.START else Gravity.END
            }
            line.addView(view, LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[n]))
        }
        return line
    }

    //
    // Building blocks
    //

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun params(top: Float = 0f) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun card(hero: Boolean = false, build: (LinearLayout) -> Unit): View {
        val card = MaterialCardView(context)
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            if (hero) {
                val tv = TypedValue()
                if (context.theme.resolveAttribute(R.attr.aurora_hero_bg, tv, true) && tv.resourceId != 0) {
                    background = ContextCompat.getDrawable(context, tv.resourceId)
                }
            }
        }
        build(box)
        card.addView(box)
        card.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16f)
            marginEnd = dp(16f)
            bottomMargin = dp(12f)
        }
        return card
    }

    private fun makeText(value: CharSequence, size: Float, font: Typeface?, color: Int): TextView = TextView(context).apply {
        text = value
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        setLineSpacing(0f, 1.1f)
    }

    private fun text(parent: LinearLayout, value: String, size: Float, font: Typeface?, color: Int, top: Float = 0f): TextView {
        val view = makeText(value, size, font, color)
        parent.addView(view, params(top))
        return view
    }

    private fun statTile(value: String, label: String): View {
        val tile = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(9f), dp(4f), dp(8f))
            val tv = TypedValue()
            if (context.theme.resolveAttribute(R.attr.aurora_chip_bg, tv, true) && tv.resourceId != 0) {
                background = ContextCompat.getDrawable(context, tv.resourceId)
            }
        }
        val number = text(tile, value, 16f, fontBold, colorText)
        number.gravity = Gravity.CENTER
        number.maxLines = 1
        val name = text(tile, label, 10f, fontRegular, colorDim, top = 1f)
        name.gravity = Gravity.CENTER
        name.maxLines = 1
        return tile
    }

    private fun dateLabel(ms: Long): String =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Locale.getDefault()).format(Date(ms))

    private fun shortDate(ms: Long): String =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEd"), Locale.getDefault()).format(Date(ms))

    private fun timeLabel(ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    private fun durationLabel(minutes: Int): String =
        if (minutes >= 60) String.format(Locale.getDefault(), "%dh%02d", minutes / 60, minutes % 60)
        else context.getString(R.string.workouts_threshold_value, minutes)
}
