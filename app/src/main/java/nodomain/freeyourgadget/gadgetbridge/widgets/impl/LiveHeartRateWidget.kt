package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.LiveHeartRateView
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.health.HealthMath
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetActions
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import org.slf4j.LoggerFactory
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The heart rate as it happens, drawn as a live line, with an analysis of the day below it: time in each zone,
 * resting, average and peak heart rate, training load and an estimate of the fitness level.
 *
 * Only the heart rate itself is measured live, so the heart rate zones, the training load (Banister's TRIMP) and the
 * VO2max estimate (Uth: 15.3 times the maximum over the resting heart rate) are all derived from it. The maximum heart
 * rate is Tanaka's 208 - 0.7 x age, the same one the health report uses. Heart rate
 * variability and stress need the time between beats, which the band does not send.
 */
object LiveHeartRateWidget : GBWidget<LiveHeartRateWidget.Data> {
    private val LOG = LoggerFactory.getLogger(LiveHeartRateWidget::class.java)

    private const val RECENT_SECONDS = 120L
    private const val DEFAULT_MAX_GAP_SECONDS = 120L
    private const val RESTING_MIN_SAMPLES = 20
    private const val RESTING_PERCENTILE = 0.05
    private const val UTH_FACTOR = 15.3
    private const val TRIMP_WEIGHT_MEN = 1.92
    private const val TRIMP_WEIGHT_WOMEN = 1.67
    private const val DEFAULT_RESTING = 60

    override val id = "heartrate_live"
    override val label = R.string.widget_live_hr_title
    override val icon = R.drawable.ic_heartrate
    override val variants = listOf(WidgetVariant("live", R.string.widget_variant_live, 6, 500))
    override val supportsLauncher = false

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsHeartRateMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val maxHeartRate = HealthMath.maxHeartRate(ActivityUser().age)
        val utils = HeartRateUtils.getInstance()
        var device: GBDevice? = null
        var maxGapSeconds = DEFAULT_MAX_GAP_SECONDS
        val samples = mutableListOf<Pair<Long, Int>>()

        try {
            for (dev in scope.devices) {
                if (device == null && dev.deviceCoordinator.supportsHeartRateMeasurement(dev)) {
                    device = dev
                }
                maxGapSeconds = maxOf(maxGapSeconds, 60L * dev.deviceCoordinator.getMaxHeartRateMeasurementsGapMinutes(dev))
                for (sample in scope.allSamples(dev)) {
                    if (utils.isValidHeartRateValue(sample.heartRate)) {
                        samples += sample.timestamp.toLong() to sample.heartRate
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get heart rate samples", e)
        }
        samples.sortBy { it.first }

        val values = samples.map { it.second }
        val resting = HeartRateWidget.deviceResting(scope) ?: percentile(values)
        val restingForLoad = resting.takeIf { it > 0 } ?: DEFAULT_RESTING

        val zoneSeconds = IntArray(ZONES)
        var trimp = 0.0
        val weight = if (ActivityUser().gender == ActivityUser.GENDER_MALE) TRIMP_WEIGHT_MEN else TRIMP_WEIGHT_WOMEN
        for (i in 0 until samples.size - 1) {
            val seconds = min(samples[i + 1].first - samples[i].first, maxGapSeconds)
            val bpm = samples[i].second
            zoneSeconds[HeartRateWidget.zoneFor(bpm, maxHeartRate)] += seconds.toInt()
            val reserve = ((bpm - restingForLoad).toDouble() / (maxHeartRate - restingForLoad)).coerceIn(0.0, 1.0)
            trimp += seconds / 60.0 * reserve * 0.64 * exp(weight * reserve)
        }

        val since = (System.currentTimeMillis() / 1000L) - RECENT_SECONDS
        return Data(
            deviceAddress = device?.address,
            maxHeartRate = maxHeartRate,
            resting = resting,
            minimum = values.minOrNull() ?: 0,
            average = if (values.isEmpty()) 0 else values.average().roundToInt(),
            maximum = values.maxOrNull() ?: 0,
            zoneSeconds = zoneSeconds,
            trimp = trimp.roundToInt(),
            vo2max = if (resting > 0) (UTH_FACTOR * maxHeartRate / resting).roundToInt() else 0,
            recent = samples.filter { it.first >= since }.map { LiveHeartRateView.Point(it.first * 1000L, it.second) },
        )
    }

    /** The low end of the day's heart rate, as a stand-in for the resting one when the device reports none. */
    private fun percentile(values: List<Int>): Int {
        if (values.size < RESTING_MIN_SAMPLES) return 0
        val sorted = values.sorted()
        return sorted[(sorted.size * RESTING_PERCENTILE).toInt()]
    }

    override fun previewData(): Data = Data(
        deviceAddress = null,
        maxHeartRate = 190,
        resting = 56,
        minimum = 49,
        average = 68,
        maximum = 142,
        zoneSeconds = intArrayOf(40000, 5400, 2400, 900, 240, 60),
        trimp = 86,
        vo2max = 52,
        recent = emptyList(),
    )

    override fun createView(inflater: LayoutInflater, parent: ViewGroup, variant: WidgetVariant): View =
        LiveHeartRateView(inflater.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    override fun bind(view: View, variant: WidgetVariant, config: WidgetConfig, data: Data) {
        (view as LiveHeartRateView).setData(data, config.title(view.context, label))
    }

    override fun bindLoading(view: View, variant: WidgetVariant, config: WidgetConfig) {
        (view as LiveHeartRateView).setLoading(config.title(view.context, label))
    }

    override fun onClick(view: View, variant: WidgetVariant, config: WidgetConfig, timestamp: Int) {
        WidgetActions.openChart(view.context, config, this, "heartrate", label, "", timestamp)
    }

    /** Resting, then the five zones. */
    const val ZONES = 6

    /**
     * [zoneSeconds] is the time of the day spent at rest and in each zone, [recent] the last couple of minutes that
     * seed the live line.
     */
    class Data(
        val deviceAddress: String?,
        val maxHeartRate: Int,
        val resting: Int,
        val minimum: Int,
        val average: Int,
        val maximum: Int,
        val zoneSeconds: IntArray,
        val trimp: Int,
        val vo2max: Int,
        val recent: List<LiveHeartRateView.Point>,
    )
}
