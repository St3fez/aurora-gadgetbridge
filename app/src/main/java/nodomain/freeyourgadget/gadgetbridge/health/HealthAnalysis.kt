package nodomain.freeyourgadget.gadgetbridge.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.SleepRangeUtils
import nodomain.freeyourgadget.gadgetbridge.workouts.WorkoutDetector
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.GregorianCalendar
import kotlin.math.min

/**
 * Reads four weeks of the band's data and turns it into a [HealthReport]. Runs on the IO dispatcher; the last result
 * is kept for a minute, so that the dashboard tile and the report screen do not both read the database.
 */
object HealthAnalysis {
    private val LOG = LoggerFactory.getLogger(HealthAnalysis::class.java)

    const val DAYS = 28

    private const val DEFAULT_MAX_GAP_SECONDS = 120L
    private const val CACHE_MILLIS = 60_000L
    private const val NIGHT_SHIFT_SECONDS = 6 * 3600L
    private const val MIN_NIGHT_SAMPLES = 20
    private const val MIN_STILL_SAMPLES = 60
    private const val DEFAULT_RESTING = 60
    private const val TRIMP_WEIGHT_MEN = 1.92
    private const val TRIMP_WEIGHT_WOMEN = 1.67
    private const val SPO2_LOW = 90
    private const val EVENING_MINUTES = 18 * 60
    private const val MINUTES_PER_DAY = 24 * 60

    @Volatile
    var latest: HealthReport? = null
        private set

    private var cachedKey: String? = null
    private var cachedAt = 0L

    suspend fun load(devices: List<GBDevice>, anchor: LocalDate, days: Int = DAYS): HealthReport =
        withContext(Dispatchers.IO) {
            val key = anchor.toEpochDay().toString() + devices.joinToString(",") { it.address.orEmpty() }
            synchronized(this@HealthAnalysis) {
                val cached = latest
                if (cached != null && key == cachedKey && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) return@withContext cached
            }
            val report = try {
                compute(devices, anchor, days)
            } catch (e: Exception) {
                LOG.error("Could not build the health report", e)
                empty(devices, anchor, days)
            }
            synchronized(this@HealthAnalysis) {
                latest = report
                cachedKey = key
                cachedAt = System.currentTimeMillis()
            }
            report
        }

    private class HrSeries(val times: LongArray, val bpms: IntArray, val maxGap: Long)

    private fun nans(n: Int) = FloatArray(n) { Float.NaN }

    private fun empty(devices: List<GBDevice>, anchor: LocalDate, days: Int): HealthReport {
        val zone = ZoneId.systemDefault()
        val age = ActivityUser().age
        return HealthReport(
            firstDayStart = anchor.minusDays(days - 1L).atStartOfDay(zone).toEpochSecond(), dayCount = days,
            lastDayIsToday = anchor == LocalDate.now(zone), age = age, maxHeartRate = HealthMath.maxHeartRate(age),
            deviceAddress = devices.firstOrNull()?.address, steps = nans(days), restingHr = nans(days), averageHr = nans(days),
            hrCoverageMinutes = nans(days), moderateMinutes = nans(days), vigorousMinutes = nans(days), trimp = nans(days),
            sleepMinutes = nans(days), deepMinutes = nans(days), remMinutes = nans(days), lightMinutes = nans(days),
            awakeMinutes = nans(days), sleepOnset = nans(days), spo2Average = nans(days), spo2Minimum = nans(days),
            spo2LowShare = nans(days), stressAverage = nans(days), pai = nans(days),
        )
    }

    private fun compute(devices: List<GBDevice>, anchor: LocalDate, days: Int): HealthReport {
        val zone = ZoneId.systemDefault()
        val firstDay = anchor.minusDays(days - 1L)
        val firstStart = firstDay.atStartOfDay(zone).toEpochSecond()
        val endTs = anchor.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        fun dayIndex(epochSecond: Long): Int =
            (Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate().toEpochDay() - firstDay.toEpochDay()).toInt()

        val user = ActivityUser()
        val age = user.age
        val maxHr = HealthMath.maxHeartRate(age)
        val weight = if (user.gender == ActivityUser.GENDER_MALE) TRIMP_WEIGHT_MEN else TRIMP_WEIGHT_WOMEN
        val utils = HeartRateUtils.getInstance()

        val report = empty(devices, anchor, days)
        val steps = DoubleArray(days)
        val hasSteps = BooleanArray(days)
        val sleepHr = Array(days) { ArrayList<Int>() }
        val stillHr = Array(days) { ArrayList<Int>() }
        val hrSum = DoubleArray(days)
        val hrCount = IntArray(days)
        val series = ArrayList<HrSeries>()
        val activity = ArrayList<ActivityMetrics>()

        GBApplication.acquireDbReadOnly().use { db ->
            for (dev in devices) {
                val coordinator = dev.deviceCoordinator
                try {
                    val minutes = readActivity(db, dev, firstStart.toInt(), endTs.toInt(), ::dayIndex, days, utils, steps, hasSteps, sleepHr, stillHr, hrSum, hrCount, series)
                    activity += ActivityAnalysis.analyze(minutes, ::dayIndex, days, maxHr)
                } catch (e: Exception) {
                    LOG.error("Could not read the activity samples of {}", dev, e)
                }
                try {
                    if (coordinator.supportsHeartRateRestingMeasurement(dev)) {
                        coordinator.getHeartRateRestingSampleProvider(dev, db.daoSession)
                            ?.getAllSamples(firstStart * 1000L, endTs * 1000L).orEmpty()
                            .filter { it.heartRate > 0 }
                            .forEach { sample ->
                                val i = dayIndex(sample.timestamp / 1000L)
                                if (i in 0 until days) report.restingHr[i] = sample.heartRate.toFloat()
                            }
                    }
                } catch (e: Exception) {
                    LOG.error("Could not read the resting heart rate of {}", dev, e)
                }
                try {
                    readSleep(db, dev, firstDay, days, report)
                } catch (e: Exception) {
                    LOG.error("Could not read the sleep of {}", dev, e)
                }
                try {
                    readOxygen(db, dev, firstStart, endTs, ::dayIndex, days, report)
                } catch (e: Exception) {
                    LOG.error("Could not read the blood oxygen of {}", dev, e)
                }
                try {
                    readStress(db, dev, firstStart, endTs, ::dayIndex, days, report)
                } catch (e: Exception) {
                    LOG.error("Could not read the stress of {}", dev, e)
                }
                try {
                    readPai(db, dev, firstStart, endTs, ::dayIndex, days, report)
                } catch (e: Exception) {
                    LOG.error("Could not read the PAI of {}", dev, e)
                }
            }
        }

        for (metrics in activity) {
            for (i in 0 until days) {
                if (report.cadenceModerate[i].isNaN()) {
                    report.cadenceModerate[i] = metrics.cadenceModerate[i]
                    report.cadenceVigorous[i] = metrics.cadenceVigorous[i]
                }
                if (report.sedentaryHours[i].isNaN()) {
                    report.sedentaryHours[i] = metrics.sedentaryHours[i]
                    report.sedentaryMeanBout[i] = metrics.sedentaryMeanBout[i]
                    report.sedentaryLongest[i] = metrics.sedentaryLongest[i]
                }
                if (report.heartRateRecovery[i].isNaN()) report.heartRateRecovery[i] = metrics.heartRateRecovery[i]
            }
            if (report.sleepRegularity.isNaN()) report.sleepRegularity = metrics.sleepRegularity
        }

        for (i in 0 until days) {
            if (hasSteps[i]) report.steps[i] = steps[i].toFloat()
            if (hrCount[i] > 0) report.averageHr[i] = (hrSum[i] / hrCount[i]).toFloat()
            if (report.restingHr[i].isNaN()) {
                val night = sleepHr[i]
                val still = stillHr[i]
                when {
                    night.size >= MIN_NIGHT_SAMPLES -> report.restingHr[i] = HealthMath.percentile(night, 0.10).toFloat()
                    still.size >= MIN_STILL_SAMPLES -> report.restingHr[i] = HealthMath.percentile(still, 0.05).toFloat()
                }
            }
        }

        val restingForLoad = HealthMath.median(HealthMath.finite(report.restingHr)).let { if (it.isNaN()) DEFAULT_RESTING else it.toInt() }
        val moderate = DoubleArray(days)
        val vigorous = DoubleArray(days)
        val trimp = DoubleArray(days)
        val coverage = DoubleArray(days)
        for (s in series) {
            for (k in 0 until s.times.size - 1) {
                val i = dayIndex(s.times[k])
                if (i !in 0 until days) continue
                val minutes = min(s.times[k + 1] - s.times[k], s.maxGap) / 60.0
                coverage[i] += minutes
                when (HealthMath.intensity(s.bpms[k], maxHr)) {
                    1 -> moderate[i] += minutes
                    2 -> vigorous[i] += minutes
                }
                trimp[i] += HealthMath.trimp(minutes, s.bpms[k], restingForLoad, maxHr, weight)
            }
        }
        for (i in 0 until days) {
            if (coverage[i] <= 0.0) continue
            report.hrCoverageMinutes[i] = coverage[i].toFloat()
            report.moderateMinutes[i] = moderate[i].toFloat()
            report.vigorousMinutes[i] = vigorous[i].toFloat()
            report.trimp[i] = trimp[i].toFloat()
        }
        return report
    }

    private fun readActivity(
        db: DBHandler,
        dev: GBDevice,
        from: Int,
        to: Int,
        dayIndex: (Long) -> Int,
        days: Int,
        utils: HeartRateUtils,
        steps: DoubleArray,
        hasSteps: BooleanArray,
        sleepHr: Array<ArrayList<Int>>,
        stillHr: Array<ArrayList<Int>>,
        hrSum: DoubleArray,
        hrCount: IntArray,
        series: ArrayList<HrSeries>,
    ): List<WorkoutDetector.Minute> {
        val coordinator = dev.deviceCoordinator
        val provider = coordinator.getSampleProvider(dev, db.daoSession) ?: return emptyList()
        val samples: List<ActivitySample> = provider.getAllActivitySamples(from, to)
        val countSteps = coordinator.supportsStepCounter(dev)
        val measuresHr = coordinator.supportsHeartRateMeasurement(dev)
        val maxGap = maxOf(DEFAULT_MAX_GAP_SECONDS, 60L * coordinator.getMaxHeartRateMeasurementsGapMinutes(dev))

        val times = ArrayList<Long>()
        val bpms = ArrayList<Int>()
        for (sample in samples.sortedBy { it.timestamp }) {
            val t = sample.timestamp.toLong()
            if (countSteps && sample.steps > 0) {
                val i = dayIndex(t)
                if (i in 0 until days) {
                    steps[i] += sample.steps.toDouble()
                    hasSteps[i] = true
                }
            }
            if (!measuresHr || !utils.isValidHeartRateValue(sample.heartRate)) continue
            times += t
            bpms += sample.heartRate
            val i = dayIndex(t)
            if (i in 0 until days) {
                hrSum[i] += sample.heartRate.toDouble()
                hrCount[i]++
                if (isSleep(sample.kind)) {
                    val night = dayIndex(t + NIGHT_SHIFT_SECONDS)
                    if (night in 0 until days) sleepHr[night] += sample.heartRate
                } else if (sample.steps <= 0 && sample.kind != ActivityKind.AWAKE_SLEEP) {
                    stillHr[i] += sample.heartRate
                }
            }
        }
        if (times.size > 1) series += HrSeries(times.toLongArray(), bpms.toIntArray(), maxGap)
        return WorkoutDetector.bucket(samples) { utils.isValidHeartRateValue(it) }
    }

    private fun isSleep(kind: ActivityKind): Boolean =
        kind == ActivityKind.LIGHT_SLEEP || kind == ActivityKind.DEEP_SLEEP || kind == ActivityKind.REM_SLEEP || kind == ActivityKind.SLEEP_ANY

    /** Each day's number is the night that ends on it, using the same night window as the sleep charts. */
    private fun readSleep(db: DBHandler, dev: GBDevice, firstDay: LocalDate, days: Int, report: HealthReport) {
        val coordinator = dev.deviceCoordinator
        if (!coordinator.supportsSleepMeasurement(dev)) return
        val provider = coordinator.getSleepSessionProvider(dev, db.daoSession) ?: return
        val zone = ZoneId.systemDefault()
        for (i in 0 until days) {
            if (!report.sleepMinutes[i].isNaN()) continue
            val noon = firstDay.plusDays(i.toLong()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val range = SleepRangeUtils.getSleepRange(GregorianCalendar().apply { timeInMillis = noon })
            val sessions = provider.getSleepSessionsEndingIn(range[0], range[1])
            if (sessions.isEmpty()) continue
            val light = sessions.sumOf { it.lightSleepDuration } / 60f
            val deep = sessions.sumOf { it.deepSleepDuration } / 60f
            val rem = sessions.sumOf { it.remSleepDuration } / 60f
            val total = light + deep + rem
            if (total <= 0f) continue
            report.sleepMinutes[i] = total
            report.lightMinutes[i] = light
            report.deepMinutes[i] = deep
            report.remMinutes[i] = rem
            val awake = sessions.sumOf { it.awakeSleepDuration } / 60f
            report.awakeMinutes[i] = awake
            report.sleepEfficiency[i] = 100f * total / (total + awake)
            val main = sessions.maxByOrNull { it.endTime - it.startTime }!!
            val start = Instant.ofEpochMilli(main.startTime).atZone(zone)
            report.sleepOnset[i] = ((start.hour * 60 + start.minute - EVENING_MINUTES + MINUTES_PER_DAY) % MINUTES_PER_DAY).toFloat()
        }
    }

    private fun readOxygen(db: DBHandler, dev: GBDevice, from: Long, to: Long, dayIndex: (Long) -> Int, days: Int, report: HealthReport) {
        val coordinator = dev.deviceCoordinator
        if (!coordinator.supportsSpo2(dev)) return
        val provider = coordinator.getSpo2SampleProvider(dev, db.daoSession) ?: return
        val sum = DoubleArray(days)
        val count = IntArray(days)
        val low = IntArray(days)
        val minimum = IntArray(days) { Int.MAX_VALUE }
        for (sample in provider.getAllSamples(from * 1000L, to * 1000L)) {
            if (sample.spo2 <= 0) continue
            val i = dayIndex(sample.timestamp / 1000L)
            if (i !in 0 until days) continue
            sum[i] += sample.spo2.toDouble()
            count[i]++
            if (sample.spo2 < SPO2_LOW) low[i]++
            minimum[i] = min(minimum[i], sample.spo2)
        }
        for (i in 0 until days) {
            if (count[i] == 0 || !report.spo2Average[i].isNaN()) continue
            report.spo2Average[i] = (sum[i] / count[i]).toFloat()
            report.spo2Minimum[i] = minimum[i].toFloat()
            report.spo2LowShare[i] = low[i].toFloat() / count[i]
        }
    }

    private fun readStress(db: DBHandler, dev: GBDevice, from: Long, to: Long, dayIndex: (Long) -> Int, days: Int, report: HealthReport) {
        val coordinator = dev.deviceCoordinator
        if (!coordinator.supportsStressMeasurement(dev)) return
        val provider = coordinator.getStressSampleProvider(dev, db.daoSession) ?: return
        val sum = DoubleArray(days)
        val count = IntArray(days)
        for (sample in provider.getAllSamples(from * 1000L, to * 1000L)) {
            if (sample.stress <= 0) continue
            val i = dayIndex(sample.timestamp / 1000L)
            if (i !in 0 until days) continue
            sum[i] += sample.stress.toDouble()
            count[i]++
        }
        for (i in 0 until days) {
            if (count[i] > 0 && report.stressAverage[i].isNaN()) report.stressAverage[i] = (sum[i] / count[i]).toFloat()
        }
    }

    /** PAI is a rolling 7-day total, so each day keeps the last value the band reported on it. */
    private fun readPai(db: DBHandler, dev: GBDevice, from: Long, to: Long, dayIndex: (Long) -> Int, days: Int, report: HealthReport) {
        val coordinator = dev.deviceCoordinator
        if (!coordinator.supportsPai(dev)) return
        val provider = coordinator.getPaiSampleProvider(dev, db.daoSession) ?: return
        for (sample in provider.getAllSamples(from * 1000L, to * 1000L).sortedBy { it.timestamp }) {
            val i = dayIndex(sample.timestamp / 1000L)
            if (i in 0 until days) report.pai[i] = sample.paiTotal
        }
    }
}
