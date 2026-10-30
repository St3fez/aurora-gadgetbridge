package nodomain.freeyourgadget.gadgetbridge.health

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import kotlin.math.roundToInt

/**
 * The health condition tile of the dashboard: how many of the indicators are on target, the resting heart rate of
 * the last four weeks against the person's usual, and the eight indicators. A tap opens [HealthReportActivity].
 */
class HealthSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val fontBold: Typeface? = ResourcesCompat.getFont(context, R.font.sora_bold)
    private val fontSemi: Typeface? = ResourcesCompat.getFont(context, R.font.sora_semibold)
    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.sora_regular)
    private val colorText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val colorDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    private val title = TextView(context)
    private val count = TextView(context)
    private val chart = HealthChartView(context)
    private val caption = TextView(context)
    private val chips = HealthChipsView(context)
    private val hint = TextView(context)

    init {
        orientation = VERTICAL
        setPadding(dp(16f), dp(14f), dp(16f), dp(12f))

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        title.apply {
            typeface = fontSemi
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colorText)
            text = context.getString(R.string.health_title)
            compoundDrawablePadding = dp(8f)
            ContextCompat.getDrawable(context, R.drawable.ic_heartrate)?.mutate()?.let {
                it.setTint(WidgetColors.exercise)
                it.setBounds(0, 0, dp(18f), dp(18f))
                setCompoundDrawables(it, null, null, null)
            }
        }
        count.apply {
            typeface = fontBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(count, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })

        caption.apply {
            typeface = fontRegular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colorDim)
            maxLines = 2
        }
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
        addView(chips, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

        hint.apply {
            typeface = fontRegular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(colorDim)
            gravity = Gravity.CENTER
            text = context.getString(R.string.health_tap_details)
        }
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    fun setLoading() {
        count.text = ""
        caption.text = ""
    }

    fun setReport(report: HealthReport) {
        chips.setReport(report)
        val s = report.summary
        if (!report.hasAnyData) {
            count.text = ""
            caption.text = context.getString(R.string.health_no_data)
        } else {
            count.text = context.getString(R.string.health_in_range, s.inRange, s.measured)
            count.setTextColor(WidgetColors.level(if (s.inRange * 2 >= s.measured) Level.GOOD else Level.FAIR))
            caption.text = if (s.restingBaseline.isNaN() || s.restingRecent.isNaN()) {
                context.getString(R.string.health_resting_no_baseline)
            } else {
                context.getString(
                    R.string.health_resting_line,
                    s.restingBaseline.roundToInt(), s.restingRecent.roundToInt(), s.restingDelta.roundToInt(),
                )
            }
        }
        chart.setSpec(
            HealthChartView.Spec(
                style = HealthChartView.Style.LINE,
                series = listOf(HealthChartView.Series(report.restingHr, WidgetColors.exercise)),
                firstDayStart = report.firstDayStart,
                visibleDays = report.dayCount,
                baseline = s.restingBaseline,
            )
        )
        contentDescription = context.getString(R.string.health_title) + ": " + count.text
    }
}
