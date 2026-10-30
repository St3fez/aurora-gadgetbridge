package nodomain.freeyourgadget.gadgetbridge.health

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import java.util.Locale
import kotlin.math.roundToInt

/** Text for the numbers of the health report. A missing number is shown as a dash. */
object HealthFormat {
    const val DASH = "–"

    fun whole(value: Float): String = if (value.isNaN()) DASH else value.roundToInt().toString()

    fun signed(value: Float): String = if (value.isNaN()) DASH else String.format(Locale.getDefault(), "%+d", value.roundToInt())

    fun steps(value: Float): String = when {
        value.isNaN() -> DASH
        value >= 1000f -> String.format(Locale.getDefault(), "%.1fk", value / 1000f)
        else -> value.roundToInt().toString()
    }

    fun grouped(value: Float): String = if (value.isNaN()) DASH else String.format(Locale.getDefault(), "%,d", value.roundToInt())

    fun hoursMinutes(minutes: Float): String =
        if (minutes.isNaN()) DASH else String.format(Locale.getDefault(), "%dh%02d", minutes.roundToInt() / 60, minutes.roundToInt() % 60)

    fun levelName(level: Level): Int = when (level) {
        Level.GOOD -> R.string.health_level_good
        Level.FAIR -> R.string.health_level_fair
        Level.ATTENTION -> R.string.health_level_attention
        Level.NONE -> R.string.health_level_none
    }
}

/**
 * The twelve indicators of the health report as three rows of small tiles, each with its value and a dot in the color
 * of its level. Used on the dashboard and at the top of the report.
 */
class HealthChipsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private class Chip(val root: LinearLayout, val value: TextView, val label: TextView)

    private val fontBold: Typeface? = ResourcesCompat.getFont(context, R.font.sora_bold)
    private val fontRegular: Typeface? = ResourcesCompat.getFont(context, R.font.sora_regular)
    private val colorText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val colorDim = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    private val labels = intArrayOf(
        R.string.health_chip_resting, R.string.health_chip_trend, R.string.health_chip_steps, R.string.health_chip_effort,
        R.string.health_chip_cadence, R.string.health_chip_pai, R.string.health_chip_sleep, R.string.health_chip_sleepq,
        R.string.health_chip_oxygen, R.string.health_chip_stress, R.string.health_chip_sedentary, R.string.health_chip_recovery,
    )
    private val chips = ArrayList<Chip>()

    init {
        orientation = VERTICAL
        for (row in 0 until 3) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (column in 0 until 4) {
                val chip = createChip()
                chips += chip
                line.addView(chip.root, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (column == 0) 0 else dp(3f)
                    marginEnd = if (column == 3) 0 else dp(3f)
                    topMargin = if (row == 0) 0 else dp(6f)
                })
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun createChip(): Chip {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(9f), dp(4f), dp(8f))
            val tv = TypedValue()
            if (context.theme.resolveAttribute(R.attr.aurora_chip_bg, tv, true) && tv.resourceId != 0) {
                background = ContextCompat.getDrawable(context, tv.resourceId)
            }
        }
        val value = TextView(context).apply {
            typeface = fontBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colorText)
            maxLines = 1
            gravity = Gravity.CENTER
        }
        val label = TextView(context).apply {
            typeface = fontRegular
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(colorDim)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            // The label shrinks to fit rather than wrapping its word under the dot, where a single line would hide it
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 7, 10, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        root.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1f) })
        return Chip(root, value, label)
    }

    fun setReport(report: HealthReport) {
        val s = report.summary
        val values = listOf(
            HealthFormat.whole(s.restingNow),
            HealthFormat.signed(s.restingDelta),
            HealthFormat.steps(s.stepsAverage),
            if (s.equivalentWeek.isNaN()) HealthFormat.DASH else HealthFormat.whole(s.equivalentWeek) + "m",
            if (s.cadenceEquivalentWeek.isNaN()) HealthFormat.DASH else HealthFormat.whole(s.cadenceEquivalentWeek) + "m",
            HealthFormat.whole(s.pai),
            HealthFormat.hoursMinutes(s.sleepAverage),
            if (s.sleepEfficiency.isNaN()) HealthFormat.DASH else HealthFormat.whole(s.sleepEfficiency) + "%",
            if (s.spo2Average.isNaN()) HealthFormat.DASH else HealthFormat.whole(s.spo2Average) + "%",
            HealthFormat.whole(s.stressAverage),
            if (s.sedentaryHours.isNaN()) HealthFormat.DASH else String.format(java.util.Locale.getDefault(), "%.1fh", s.sedentaryHours),
            if (s.recoveryDrop.isNaN()) HealthFormat.DASH else HealthFormat.whole(s.recoveryDrop),
        )
        val levels = s.levels
        for ((i, chip) in chips.withIndex()) {
            chip.value.text = values[i]
            val text = SpannableStringBuilder("● ").append(context.getString(labels[i]))
            text.setSpan(ForegroundColorSpan(WidgetColors.level(levels[i])), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            chip.label.text = text
            chip.root.contentDescription = context.getString(labels[i]) + ": " + values[i] + ", " + context.getString(HealthFormat.levelName(levels[i]))
        }
    }
}
