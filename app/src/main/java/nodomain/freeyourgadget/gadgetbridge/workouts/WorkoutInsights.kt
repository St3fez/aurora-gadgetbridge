package nodomain.freeyourgadget.gadgetbridge.workouts

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.health.HealthAnalysis
import nodomain.freeyourgadget.gadgetbridge.health.HealthMath
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.HeartRateWidget
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One heart rate reading of a workout, [minute] minutes after it started. */
class HeartPoint(val minute: Float, val bpm: Int)

/** A workout with the numbers the Workouts section shows. [series] is only filled for the latest one. */
class WorkoutEntry(
    val summaryId: Long,
    val kind: ActivityKind,
    val name: String?,
    val auto: Boolean,
    val startMs: Long,
    val endMs: Long,
    val steps: Int,
    val averageHr: Int,
    val maxHr: Int,
    val zoneSeconds: IntArray,
    val trimp: Int,
    val series: List<HeartPoint>,
) {
    val durationMinutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
}

/**
 * What the top of the Workouts section shows: the latest workout of at least [minMinutes] minutes, a table of the
 * most recent ones, and the minutes of workout of each of the last [DAYS] days.
 */
class WorkoutInsights(
    val minMinutes: Int,
    val maxHeartRate: Int,
    val last: WorkoutEntry?,
    val recent: List<WorkoutEntry>,
    val dailyMinutes: FloatArray,
    val firstDayStart: Long,
    val totalMinutes: Int,
    val totalCount: Int,
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(WorkoutInsights::class.java)

        const val DAYS = 14
        private const val TABLE_ROWS = 8
        private const val DEFAULT_RESTING = 60
        private const val TRIMP_WEIGHT_MEN = 1.92
        private const val TRIMP_WEIGHT_WOMEN = 1.67

        /** Builds the insights from [summaries], newest first; only workouts of the chosen minimum length count. */
        fun compute(summaries: List<BaseActivitySummary>): WorkoutInsights {
            val minMinutes = WorkoutDetector.minMinutes()
            val user = ActivityUser()
            val maxHr = HealthMath.maxHeartRate(user.age)
            val resting = HealthAnalysis.latest?.summary?.restingNow?.takeIf { !it.isNaN() }?.roundToInt() ?: DEFAULT_RESTING
            val weight = if (user.gender == ActivityUser.GENDER_MALE) TRIMP_WEIGHT_MEN else TRIMP_WEIGHT_WOMEN

            val qualifying = summaries.filter { duration(it) >= minMinutes }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val firstDay = today.minusDays(DAYS - 1L)
            val daily = FloatArray(DAYS)
            for (summary in qualifying) {
                val day = Instant.ofEpochMilli(summary.startTime.time).atZone(zone).toLocalDate()
                val index = (day.toEpochDay() - firstDay.toEpochDay()).toInt()
                if (index in 0 until DAYS) daily[index] += duration(summary).toFloat()
            }

            val entries = qualifying.take(TABLE_ROWS).mapIndexed { i, summary ->
                try {
                    entry(summary, maxHr, resting, weight, keepSeries = i == 0)
                } catch (e: Exception) {
                    LOG.error("Could not read the workout {}", summary.id, e)
                    entry(summary, maxHr, resting, weight, keepSeries = false, samples = emptyList())
                }
            }
            return WorkoutInsights(
                minMinutes = minMinutes,
                maxHeartRate = maxHr,
                last = entries.firstOrNull(),
                recent = entries,
                dailyMinutes = daily,
                firstDayStart = firstDay.atStartOfDay(zone).toEpochSecond(),
                totalMinutes = daily.sum().roundToInt(),
                totalCount = qualifying.count { it.startTime.time >= firstDay.atStartOfDay(zone).toInstant().toEpochMilli() },
            )
        }

        private fun duration(summary: BaseActivitySummary): Int = ((summary.endTime.time - summary.startTime.time) / 60_000L).toInt()

        private fun samplesOf(summary: BaseActivitySummary): List<ActivitySample> {
            val identifier = summary.device?.identifier ?: return emptyList()
            val device = GBApplication.app().deviceManager.devices.firstOrNull { it.address.equals(identifier, ignoreCase = true) }
                ?: return emptyList()
            return GBApplication.acquireDbReadOnly().use { db ->
                device.deviceCoordinator.getSampleProvider(device, db.daoSession)
                    ?.getAllActivitySamples((summary.startTime.time / 1000L).toInt(), (summary.endTime.time / 1000L).toInt())
                    .orEmpty()
            }
        }

        private fun entry(
            summary: BaseActivitySummary,
            maxHr: Int,
            resting: Int,
            weight: Double,
            keepSeries: Boolean,
            samples: List<ActivitySample> = samplesOf(summary),
        ): WorkoutEntry {
            val utils = HeartRateUtils.getInstance()
            val startSeconds = summary.startTime.time / 1000L
            val minutes = WorkoutDetector.bucket(samples) { utils.isValidHeartRateValue(it) }.filter { it.heartRate > 0 }
            val zones = IntArray(ZONES)
            var trimp = 0.0
            for (m in minutes) {
                zones[HeartRateWidget.zoneFor(m.heartRate, maxHr)] += 60
                trimp += HealthMath.trimp(1.0, m.heartRate, resting, maxHr, weight)
            }
            val data = summary.summaryData?.let { ActivitySummaryData.fromJson(it) }
            val steps = if (samples.isNotEmpty()) samples.sumOf { maxOf(it.steps, 0) } else data?.getNumber(ActivitySummaryEntries.STEPS, 0)?.toInt() ?: 0
            val average = if (minutes.isNotEmpty()) minutes.map { it.heartRate }.average().roundToInt()
            else data?.getNumber(ActivitySummaryEntries.HR_AVG, 0)?.toInt() ?: 0
            val max = minutes.maxOfOrNull { it.heartRate } ?: data?.getNumber(ActivitySummaryEntries.HR_MAX, 0)?.toInt() ?: 0
            return WorkoutEntry(
                summaryId = summary.id ?: 0L,
                kind = ActivityKind.fromCode(summary.activityKind),
                name = summary.name,
                auto = WorkoutDetector.isAutoDetected(summary.id),
                startMs = summary.startTime.time,
                endMs = summary.endTime.time,
                steps = steps,
                averageHr = average,
                maxHr = max,
                zoneSeconds = zones,
                trimp = trimp.roundToInt(),
                series = if (keepSeries) minutes.map { HeartPoint((it.minute * 60 - startSeconds) / 60f, it.heartRate) } else emptyList(),
            )
        }

        const val ZONES = 6
    }
}
