package nodomain.freeyourgadget.gadgetbridge.health

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The full health report: one card per indicator with a chart of the last two or four weeks, how it compares with
 * what large studies found, and the source of each reference. Everything is computed on the phone from the band's data.
 */
class HealthReportActivity : AbstractGBActivity() {
    private lateinit var content: LinearLayout

    private val fontBold: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.sora_bold) }
    private val fontSemi: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) }
    private val fontRegular: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) }
    private val colorText by lazy { MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurface, Color.WHITE) }
    private val colorDim by lazy { MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.health_title)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(24f))
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        setContentView(scroll)

        HealthAnalysis.latest?.let { render(it) }
        lifecycleScope.launch { render(HealthAnalysis.load(devices(), LocalDate.now())) }
    }

    private fun devices(): List<GBDevice> {
        val all = GBApplication.app().deviceManager.devices
        val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        return all.filter { it.address == address }
            .ifEmpty { all.filter { it.deviceCoordinator.supportsHeartRateMeasurement(it) } }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    //
    // The report
    //

    private fun render(report: HealthReport) {
        content.removeAllViews()
        val s = report.summary
        val n = report.dayCount

        card(hero = true) { box ->
            text(box, getString(R.string.health_title), 22f, fontBold, colorText)
            text(box, getString(R.string.health_report_subtitle, n), 12f, fontRegular, colorDim, top = 2f)
            if (report.hasAnyData) {
                val count = text(box, getString(R.string.health_in_range, s.inRange, s.measured), 15f, fontSemi,
                    WidgetColors.level(if (s.inRange * 2 >= s.measured) Level.GOOD else Level.FAIR), top = 12f)
                count.contentDescription = count.text
                val chips = HealthChipsView(this)
                chips.setReport(report)
                box.addView(chips, params(top = 12f))
            } else {
                text(box, getString(R.string.health_no_data), 13f, fontRegular, colorDim, top = 12f)
            }
        }

        if (report.hasAnyData) {
            prioritiesCard(report)
            restingSection(report)
            effortSection(report)
            cadenceSection(report)
            sedentarySection(report)
            loadSection(report)
            sleepSection(report)
            sleepQualitySection(report)
            oxygenSection(report)
            stressSection(report)
            stepsSection(report)
            paiSection(report)
            fitnessSection(report)
            recoverySection(report)
        }

        card { box ->
            text(box, getString(R.string.health_sec_limits), 15f, fontSemi, colorText)
            text(box, getString(R.string.health_limits_note), 12.5f, fontRegular, colorDim, top = 6f)
        }
        card { box ->
            text(box, getString(R.string.health_disclaimer), 12.5f, fontRegular, colorText)
            text(box, getString(R.string.health_sources_title), 13f, fontSemi, colorText, top = 14f)
            val list = text(box, getString(R.string.health_sources_list), 11.5f, fontRegular, colorDim, top = 4f)
            list.autoLinkMask = Linkify.WEB_URLS
            list.movementMethod = LinkMovementMethod.getInstance()
            list.text = getString(R.string.health_sources_list)
            list.setLinkTextColor(WidgetColors.activity)
        }
    }

    private fun hasData(series: FloatArray) = HealthMath.finite(series).isNotEmpty()

    private fun restingSection(r: HealthReport) {
        val s = r.summary
        if (s.restingNow.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_resting, s.restingLevel, getString(R.string.health_resting_value, s.restingNow.roundToInt()))
            if (s.restingBaseline.isNaN() || s.restingRecent.isNaN()) {
                text(box, getString(R.string.health_resting_no_baseline), 12.5f, fontRegular, colorDim, top = 2f)
            } else {
                text(box, getString(R.string.health_resting_line, s.restingBaseline.roundToInt(), s.restingRecent.roundToInt(), s.restingDelta.roundToInt()),
                    12.5f, fontSemi, WidgetColors.level(s.trendLevel).takeIf { s.trendLevel != Level.NONE } ?: colorDim, top = 2f)
            }
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.LINE, series = listOf(HealthChartView.Series(r.restingHr, WidgetColors.exercise)),
                firstDayStart = r.firstDayStart, visibleDays = r.dayCount, target = 80f, baseline = s.restingBaseline,
            ))
            text(box, getString(R.string.health_resting_note), 12.5f, fontRegular, colorDim, top = 10f)
            if (s.trendLevel == Level.FAIR || s.trendLevel == Level.ATTENTION) {
                text(box, getString(R.string.health_resting_trend_note), 12.5f, fontRegular, colorText, top = 8f)
            }
            sources(box, R.string.health_resting_sources)
        }
    }

    private fun effortSection(r: HealthReport) {
        val s = r.summary
        if (s.equivalentWeek.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_effort, s.activityLevel, getString(R.string.health_effort_value, s.equivalentWeek.roundToInt()))
            text(box, getString(R.string.health_effort_line, s.moderateWeek.roundToInt(), s.vigorousWeek.roundToInt()), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.STACK,
                series = listOf(
                    HealthChartView.Series(r.moderateMinutes, WidgetColors.hrZone(2)),
                    HealthChartView.Series(r.vigorousMinutes, WidgetColors.hrZone(4)),
                ),
                firstDayStart = r.firstDayStart, visibleDays = 14,
            ))
            legend(box, R.string.health_legend_moderate to WidgetColors.hrZone(2), R.string.health_legend_vigorous to WidgetColors.hrZone(4))
            if (!s.activeDays.isNaN()) {
                text(box, getString(R.string.health_effort_days, s.activeDays.roundToInt()), 12.5f, fontSemi, colorText, top = 10f)
            }
            text(box, getString(R.string.health_effort_note, r.maxHeartRate), 12.5f, fontRegular, colorDim, top = 8f)
            text(box, getString(R.string.health_effort_pattern), 12.5f, fontRegular, colorDim, top = 6f)
            text(box, getString(R.string.health_effort_coverage), 12f, fontRegular, colorDim, top = 6f)
            sources(box, R.string.health_effort_sources)
        }
    }

    private fun loadSection(r: HealthReport) {
        if (!hasData(r.trimp)) return
        val change = r.summary.trimpChange
        card { box ->
            sectionHeader(box, R.string.health_sec_load, null,
                if (change.isNaN()) HealthFormat.DASH else getString(R.string.health_load_value, (change * 100f).roundToInt()))
            text(box, getString(R.string.health_load_line), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.BARS, series = listOf(HealthChartView.Series(r.trimp, WidgetColors.hrZone(3))),
                firstDayStart = r.firstDayStart, visibleDays = 14,
            ))
            text(box, getString(R.string.health_load_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_load_sources)
        }
    }

    private fun sleepSection(r: HealthReport) {
        val s = r.summary
        if (s.sleepAverage.isNaN()) return
        fun hours(series: FloatArray) = FloatArray(series.size) { series[it] / 60f }
        card { box ->
            val minutes = s.sleepAverage.roundToInt()
            sectionHeader(box, R.string.health_sec_sleep, s.sleepLevel, getString(R.string.health_sleep_value, minutes / 60, minutes % 60))
            text(box, getString(R.string.health_sleep_line), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.STACK,
                series = listOf(
                    HealthChartView.Series(hours(r.deepMinutes), WidgetColors.deepSleep),
                    HealthChartView.Series(hours(r.remMinutes), WidgetColors.remSleep),
                    HealthChartView.Series(hours(r.lightMinutes), WidgetColors.lightSleep),
                ),
                firstDayStart = r.firstDayStart, visibleDays = 14, target = 7f, maximum = 10f,
            ))
            legend(box, R.string.health_legend_deep to WidgetColors.deepSleep, R.string.health_legend_rem to WidgetColors.remSleep,
                R.string.health_legend_light to WidgetColors.lightSleep)
            if (!s.deepShare.isNaN()) {
                text(box, getString(R.string.health_sleep_stages, (s.deepShare * 100).roundToInt(), (s.remShare * 100).roundToInt(),
                    (s.lightShare * 100).roundToInt()), 12.5f, fontSemi, colorText, top = 10f)
            }
            if (!s.onsetSd.isNaN()) {
                text(box, getString(R.string.health_sleep_onset, s.onsetSd.roundToInt()), 12.5f, fontRegular, colorDim, top = 2f)
            }
            text(box, getString(R.string.health_sleep_note), 12.5f, fontRegular, colorDim, top = 8f)
            text(box, getString(R.string.health_sleep_caution), 12f, fontRegular, colorDim, top = 6f)
            sources(box, R.string.health_sleep_sources)
        }
    }

    private fun oxygenSection(r: HealthReport) {
        val s = r.summary
        if (s.spo2Average.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_oxygen, s.spo2Level, getString(R.string.health_oxygen_value, s.spo2Average.roundToInt()))
            if (!s.spo2Minimum.isNaN()) {
                text(box, getString(R.string.health_oxygen_line, s.spo2Minimum.roundToInt(), (s.spo2LowShare * 100).roundToInt()),
                    12.5f, fontRegular, colorDim, top = 2f)
            }
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.LINE, series = listOf(HealthChartView.Series(r.spo2Average, WidgetColors.hrZone(1))),
                firstDayStart = r.firstDayStart, visibleDays = r.dayCount, bandLow = 95f, bandHigh = 100f, maximum = 100.5f,
            ))
            text(box, getString(R.string.health_oxygen_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_oxygen_sources)
        }
    }

    private fun stressSection(r: HealthReport) {
        val s = r.summary
        if (s.stressAverage.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_stress, s.stressLevel, getString(R.string.health_stress_value, s.stressAverage.roundToInt()))
            text(box, getString(R.string.health_stress_line), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.BARS, series = listOf(HealthChartView.Series(r.stressAverage, WidgetColors.hrZone(2))),
                firstDayStart = r.firstDayStart, visibleDays = 14, bandLow = 0f, bandHigh = 59f, minimum = 0f, maximum = 100f,
            ))
            text(box, getString(R.string.health_stress_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_stress_sources)
        }
    }

    private fun stepsSection(r: HealthReport) {
        val s = r.summary
        if (s.stepsAverage.isNaN()) return
        val target = HealthMath.stepsTarget(r.age)
        card { box ->
            sectionHeader(box, R.string.health_sec_steps, s.stepsLevel, HealthFormat.grouped(s.stepsAverage))
            text(box, getString(R.string.health_steps_line, String.format(Locale.getDefault(), "%,d", target)), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.BARS, series = listOf(HealthChartView.Series(r.steps, WidgetColors.activity)),
                firstDayStart = r.firstDayStart, visibleDays = 14, target = target.toFloat(),
            ))
            text(box, getString(R.string.health_steps_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_steps_sources)
        }
    }

    private fun paiSection(r: HealthReport) {
        val s = r.summary
        if (s.pai.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_pai, s.paiLevel, HealthFormat.whole(s.pai))
            text(box, getString(R.string.health_pai_line), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.LINE, series = listOf(HealthChartView.Series(r.pai, WidgetColors.distance)),
                firstDayStart = r.firstDayStart, visibleDays = r.dayCount, target = 100f,
            ))
            text(box, getString(R.string.health_pai_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_pai_sources)
        }
    }

    private fun fitnessSection(r: HealthReport) {
        val s = r.summary
        if (s.vo2max.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_fitness, null, getString(R.string.health_fitness_value, s.vo2max.roundToInt()))
            text(box, getString(R.string.health_fitness_line, String.format(Locale.getDefault(), "%.1f", HealthMath.mets(s.vo2max))),
                12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.LINE, series = listOf(HealthChartView.Series(r.vo2max, WidgetColors.activity)),
                firstDayStart = r.firstDayStart, visibleDays = r.dayCount,
            ))
            text(box, getString(R.string.health_fitness_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_fitness_sources)
        }
    }

    private fun prioritiesCard(r: HealthReport) {
        val s = r.summary
        card { box ->
            text(box, getString(R.string.health_sec_priorities), 15f, fontSemi, colorText)
            if (s.tips.isEmpty()) {
                text(box, getString(R.string.health_adv_ok), 13f, fontRegular, colorDim, top = 6f)
            }
            for (tip in s.tips) {
                val line = when (tip.type) {
                    Tip.Type.EFFORT -> getString(R.string.health_adv_effort, tip.amount.roundToInt())
                    Tip.Type.STEPS -> getString(R.string.health_adv_steps, HealthFormat.grouped(tip.amount))
                    Tip.Type.SLEEP -> getString(R.string.health_adv_sleep, tip.amount.roundToInt())
                    Tip.Type.SEDENTARY -> getString(R.string.health_adv_sedentary)
                    Tip.Type.RESTING -> getString(R.string.health_adv_resting)
                }
                val spannable = SpannableStringBuilder("\u25CF ").append(line)
                spannable.setSpan(ForegroundColorSpan(WidgetColors.level(tip.level)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text(box, "", 13f, fontRegular, colorText, top = 8f).text = spannable
            }
        }
    }

    private fun cadenceSection(r: HealthReport) {
        val s = r.summary
        if (s.cadenceEquivalentWeek.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_cadence, s.cadenceLevel, getString(R.string.health_effort_value, s.cadenceEquivalentWeek.roundToInt()))
            text(box, getString(R.string.health_cadence_line, s.cadenceModerateWeek.roundToInt(), s.cadenceVigorousWeek.roundToInt()),
                12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.STACK,
                series = listOf(
                    HealthChartView.Series(r.cadenceModerate, WidgetColors.hrZone(2)),
                    HealthChartView.Series(r.cadenceVigorous, WidgetColors.hrZone(4)),
                ),
                firstDayStart = r.firstDayStart, visibleDays = 14,
            ))
            legend(box, R.string.health_legend_moderate to WidgetColors.hrZone(2), R.string.health_legend_vigorous to WidgetColors.hrZone(4))
            text(box, getString(R.string.health_cadence_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_cadence_sources)
        }
    }

    private fun sedentarySection(r: HealthReport) {
        val s = r.summary
        if (s.sedentaryHours.isNaN()) return
        card { box ->
            val minutes = (s.sedentaryHours * 60f).roundToInt()
            sectionHeader(box, R.string.health_sec_sedentary, s.sedentaryLevel, getString(R.string.health_sleep_value, minutes / 60, minutes % 60))
            text(box, getString(R.string.health_sedentary_line, s.sedentaryBout.roundToInt(), s.sedentaryLongest.takeIf { !it.isNaN() }?.roundToInt() ?: 0),
                12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.BARS, series = listOf(HealthChartView.Series(r.sedentaryHours, WidgetColors.hrZone(1))),
                firstDayStart = r.firstDayStart, visibleDays = 14, target = 12.5f, minimum = 0f, maximum = 18f,
            ))
            text(box, getString(R.string.health_sedentary_note), 12.5f, fontRegular, colorDim, top = 10f)
            text(box, getString(R.string.health_sedentary_caution), 12f, fontRegular, colorDim, top = 6f)
            sources(box, R.string.health_sedentary_sources)
        }
    }

    private fun sleepQualitySection(r: HealthReport) {
        val s = r.summary
        if (s.sleepEfficiency.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_sleepq, s.sleepQualityLevel, getString(R.string.health_sleepq_value, s.sleepEfficiency.roundToInt()))
            text(box, getString(R.string.health_sleepq_line, s.wakeAfterOnset.roundToInt()), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.LINE, series = listOf(HealthChartView.Series(r.sleepEfficiency, WidgetColors.deepSleep)),
                firstDayStart = r.firstDayStart, visibleDays = r.dayCount, bandLow = 85f, bandHigh = 100f, maximum = 100.5f,
            ))
            if (!s.sleepRegularity.isNaN()) {
                text(box, getString(R.string.health_sleepq_regularity, s.sleepRegularity.roundToInt()), 12.5f, fontSemi, colorText, top = 10f)
            }
            text(box, getString(R.string.health_sleepq_note), 12.5f, fontRegular, colorDim, top = 8f)
            sources(box, R.string.health_sleepq_sources)
        }
    }

    private fun recoverySection(r: HealthReport) {
        val s = r.summary
        if (s.recoveryDrop.isNaN()) return
        card { box ->
            sectionHeader(box, R.string.health_sec_recovery, s.recoveryLevel, getString(R.string.health_resting_value, s.recoveryDrop.roundToInt()))
            text(box, getString(R.string.health_recovery_line), 12.5f, fontRegular, colorDim, top = 2f)
            chart(box, HealthChartView.Spec(
                style = HealthChartView.Style.BARS, series = listOf(HealthChartView.Series(r.heartRateRecovery, WidgetColors.exercise)),
                firstDayStart = r.firstDayStart, visibleDays = r.dayCount, target = 13f, minimum = 0f,
            ))
            text(box, getString(R.string.health_recovery_note), 12.5f, fontRegular, colorDim, top = 10f)
            sources(box, R.string.health_recovery_sources)
        }
    }

    //
    // Building blocks
    //

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun params(top: Float = 0f) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun card(hero: Boolean = false, build: (LinearLayout) -> Unit) {
        val card = MaterialCardView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            if (hero) {
                val tv = TypedValue()
                if (theme.resolveAttribute(R.attr.aurora_hero_bg, tv, true) && tv.resourceId != 0) {
                    background = ContextCompat.getDrawable(this@HealthReportActivity, tv.resourceId)
                }
            }
        }
        build(box)
        card.addView(box)
        content.addView(card, params(top = 0f).apply { bottomMargin = dp(12f) })
    }

    private fun text(parent: LinearLayout, value: String, size: Float, font: Typeface?, color: Int, top: Float = 0f): TextView {
        val view = TextView(this).apply {
            text = value
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            setLineSpacing(0f, 1.12f)
        }
        parent.addView(view, params(top))
        return view
    }

    /** The section title with the level chip on the right, and the headline number below. */
    private fun sectionHeader(parent: LinearLayout, title: Int, level: Level?, value: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply {
            text = getString(title)
            typeface = fontSemi
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colorText)
        }
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (level != null) {
            val color = WidgetColors.level(level)
            val chip = TextView(this).apply {
                text = getString(HealthFormat.levelName(level))
                typeface = fontSemi
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(color)
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    setColor(ColorUtils.setAlphaComponent(color, 40))
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        parent.addView(row, params())
        text(parent, value, 30f, fontBold, colorText, top = 4f)
    }

    private fun chart(parent: LinearLayout, spec: HealthChartView.Spec, heightDp: Float = 168f) {
        val view = HealthChartView(this)
        view.setSpec(spec)
        parent.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { topMargin = dp(12f) })
    }

    private fun legend(parent: LinearLayout, vararg items: Pair<Int, Int>) {
        val row = LinearLayout(this)
        for ((label, color) in items) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { gravity = Gravity.CENTER_VERTICAL })
            val name = TextView(this).apply {
                text = getString(label)
                typeface = fontRegular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(colorDim)
            }
            row.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(5f); marginEnd = dp(14f) })
        }
        parent.addView(row, params(top = 6f))
    }

    private fun sources(parent: LinearLayout, value: Int) {
        text(parent, getString(value), 11f, fontRegular, ColorUtils.setAlphaComponent(colorDim, 200), top = 8f)
    }

    companion object {
        private const val EXTRA_DEVICE_ADDRESS = "device_address"

        fun open(context: Context, deviceAddress: String? = null) {
            val intent = Intent(context, HealthReportActivity::class.java)
            deviceAddress?.let { intent.putExtra(EXTRA_DEVICE_ADDRESS, it) }
            var base: Context = context
            while (base is ContextWrapper && base !is Activity) base = base.baseContext
            if (base !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
