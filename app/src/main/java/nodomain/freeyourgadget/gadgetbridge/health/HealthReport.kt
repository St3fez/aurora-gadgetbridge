package nodomain.freeyourgadget.gadgetbridge.health

import kotlin.math.sin

/**
 * Four weeks of the wearer's health data, one value per day, oldest first. A day without a measurement is NaN.
 * Everything here comes from the sensors of the band: heart rate, steps, sleep, blood oxygen, stress and PAI.
 */
class HealthReport(
    /** Epoch second at which the first day starts. */
    val firstDayStart: Long,
    val dayCount: Int,
    /** Whether the last day is today, so that its daytime numbers are still incomplete. */
    val lastDayIsToday: Boolean,
    val age: Int,
    val maxHeartRate: Int,
    val deviceAddress: String?,
    val steps: FloatArray,
    val restingHr: FloatArray,
    val averageHr: FloatArray,
    /** Minutes in each day with a heart rate measurement, to judge how much to trust the daily numbers. */
    val hrCoverageMinutes: FloatArray,
    val moderateMinutes: FloatArray,
    val vigorousMinutes: FloatArray,
    val trimp: FloatArray,
    val sleepMinutes: FloatArray,
    val deepMinutes: FloatArray,
    val remMinutes: FloatArray,
    val lightMinutes: FloatArray,
    val awakeMinutes: FloatArray,
    /** Minutes after 18:00 at which the main sleep started. */
    val sleepOnset: FloatArray,
    val spo2Average: FloatArray,
    val spo2Minimum: FloatArray,
    /** Share (0..1) of the day's blood oxygen readings under 90%. */
    val spo2LowShare: FloatArray,
    val stressAverage: FloatArray,
    val pai: FloatArray,
    /** Percent of the time in bed that was spent asleep, per night. */
    val sleepEfficiency: FloatArray = FloatArray(dayCount) { Float.NaN },
    /** Minutes a day walking at 100-129 steps a minute, and at 130 or more. */
    val cadenceModerate: FloatArray = FloatArray(dayCount) { Float.NaN },
    val cadenceVigorous: FloatArray = FloatArray(dayCount) { Float.NaN },
    /** Hours a day awake with almost no steps, the average length of those stretches in minutes, and the longest. */
    val sedentaryHours: FloatArray = FloatArray(dayCount) { Float.NaN },
    val sedentaryMeanBout: FloatArray = FloatArray(dayCount) { Float.NaN },
    val sedentaryLongest: FloatArray = FloatArray(dayCount) { Float.NaN },
    /** Heart rate fall in the first minute after the day's workout, in bpm. */
    val heartRateRecovery: FloatArray = FloatArray(dayCount) { Float.NaN },
    /** Percent of the last week's minutes in the same sleep state as 24 hours before. */
    var sleepRegularity: Float = Float.NaN,
) {
    /** The estimated VO2max of each day, from that day's resting heart rate. */
    val vo2max: FloatArray by lazy {
        FloatArray(dayCount) { if (restingHr[it].isNaN()) Float.NaN else HealthMath.vo2max(maxHeartRate, restingHr[it]) }
    }

    val summary: HealthSummary by lazy { HealthSummary.of(this) }

    val hasAnyData: Boolean get() = summary.measured > 0

    /** Epoch second at which day [index] starts, ignoring daylight saving shifts of a few hours. */
    fun dayStart(index: Int): Long = firstDayStart + index * 86_400L

    companion object {
        /** A plausible report, for the widget picker. */
        fun preview(): HealthReport {
            val n = HealthAnalysis.DAYS
            fun series(base: Float, swing: Float, phase: Float = 0f) = FloatArray(n) { base + swing * sin(it * 0.9f + phase) }
            return HealthReport(
                firstDayStart = 0L, dayCount = n, lastDayIsToday = false, age = 35, maxHeartRate = 184, deviceAddress = null,
                steps = series(8200f, 2600f), restingHr = series(58f, 2.5f), averageHr = series(72f, 4f),
                hrCoverageMinutes = FloatArray(n) { 900f }, moderateMinutes = series(28f, 18f, 1f),
                vigorousMinutes = series(9f, 7f, 2f), trimp = series(70f, 35f, 3f), sleepMinutes = series(425f, 45f, 1f),
                deepMinutes = series(80f, 15f), remMinutes = series(95f, 18f, 2f), lightMinutes = series(250f, 25f),
                awakeMinutes = FloatArray(n) { 22f }, sleepOnset = series(330f, 35f), spo2Average = series(96.5f, 0.8f),
                spo2Minimum = series(92f, 1.5f), spo2LowShare = FloatArray(n) { 0f }, stressAverage = series(34f, 10f, 2f),
                pai = FloatArray(n) { 70f + it * 1.6f }, sleepEfficiency = series(90f, 3f),
                cadenceModerate = series(26f, 14f, 1f), cadenceVigorous = series(5f, 4f, 2f),
                sedentaryHours = series(9.5f, 1.2f), sedentaryMeanBout = series(8f, 2f, 1f), sedentaryLongest = series(70f, 25f),
                heartRateRecovery = FloatArray(n) { if (it % 6 == 0) 24f + it % 5 else Float.NaN }, sleepRegularity = 88f,
            )
        }
    }
}

/** The thing to work on first, with how far from the target the wearer is. */
class Tip(val type: Type, val amount: Float, val level: Level) {
    enum class Type { EFFORT, STEPS, SLEEP, SEDENTARY, RESTING }
}

/**
 * The report boiled down to the numbers and levels that the dashboard and the report screen show. Windows are the
 * last 7 days; today's daytime numbers are left out while the day is still going. A number needs at least
 * [MIN_DAYS] days of data, so one odd day never rates anyone.
 */
class HealthSummary private constructor(
    val restingNow: Float,
    val restingBaseline: Float,
    val restingRecent: Float,
    val restingDelta: Float,
    val restingSd: Float,
    val stepsAverage: Float,
    val moderateWeek: Float,
    val vigorousWeek: Float,
    val equivalentWeek: Float,
    val activeDays: Float,
    val cadenceModerateWeek: Float,
    val cadenceVigorousWeek: Float,
    val cadenceEquivalentWeek: Float,
    val pai: Float,
    val sleepAverage: Float,
    val deepShare: Float,
    val remShare: Float,
    val lightShare: Float,
    val onsetSd: Float,
    val sleepEfficiency: Float,
    val wakeAfterOnset: Float,
    val sleepRegularity: Float,
    val spo2Average: Float,
    val spo2Minimum: Float,
    val spo2LowShare: Float,
    val stressAverage: Float,
    val sedentaryHours: Float,
    val sedentaryBout: Float,
    val sedentaryLongest: Float,
    val trimpChange: Float,
    val vo2max: Float,
    val recoveryDrop: Float,
    val restingLevel: Level,
    val trendLevel: Level,
    val stepsLevel: Level,
    val activityLevel: Level,
    val cadenceLevel: Level,
    val paiLevel: Level,
    val sleepLevel: Level,
    val sleepQualityLevel: Level,
    val spo2Level: Level,
    val stressLevel: Level,
    val sedentaryLevel: Level,
    val recoveryLevel: Level,
    val stepsTarget: Int,
    val tips: List<Tip>,
) {
    /** The levels of the twelve indicators shown on the dashboard, in a fixed order. */
    val levels: List<Level>
        get() = listOf(
            restingLevel, trendLevel, stepsLevel, activityLevel, cadenceLevel, paiLevel,
            sleepLevel, sleepQualityLevel, spo2Level, stressLevel, sedentaryLevel, recoveryLevel,
        )

    val measured: Int get() = levels.count { it != Level.NONE }
    val inRange: Int get() = levels.count { it == Level.GOOD }

    companion object {
        private const val WEEK = 7
        private const val RECENT = 3
        private const val MIN_BASELINE_DAYS = 5
        private const val MAX_TIPS = 3

        /** A number is only given once this many days of data stand behind it, so that one odd day does not rate you. */
        const val MIN_DAYS = 3

        /** PAI is a rolling 7-day score: with fewer days than this it is still filling up. */
        private const val MIN_PAI_DAYS = 5

        fun of(r: HealthReport): HealthSummary {
            val n = r.dayCount
            val full = if (r.lastDayIsToday) n - 1 else n

            fun mean(series: FloatArray, to: Int = full): Float {
                val values = HealthMath.finite(series, to - WEEK, to)
                return if (values.size >= MIN_DAYS) HealthMath.mean(values) else Float.NaN
            }

            /** The week's total of [a] and [b] scaled to 7 days, from the days that have both, or NaN if too few. */
            fun weekly(a: FloatArray, b: FloatArray): Pair<Float, Float> {
                val days = (full - WEEK until full).filter { it >= 0 && !a[it].isNaN() && !b[it].isNaN() }
                if (days.size < MIN_DAYS) return Float.NaN to Float.NaN
                val scale = WEEK.toFloat() / days.size
                return days.sumOf { a[it].toDouble() }.toFloat() * scale to days.sumOf { b[it].toDouble() }.toFloat() * scale
            }

            val resting = HealthMath.finite(r.restingHr, n - WEEK, n)
            val restingNow = if (resting.size >= MIN_DAYS) HealthMath.median(resting) else Float.NaN
            val recent = HealthMath.median(HealthMath.finite(r.restingHr, n - RECENT, n))
            val earlier = HealthMath.finite(r.restingHr, 0, n - RECENT)
            val baseline = if (earlier.size >= MIN_BASELINE_DAYS) HealthMath.median(earlier) else Float.NaN
            val sd = if (earlier.size >= MIN_BASELINE_DAYS) HealthMath.standardDeviation(earlier) else Float.NaN
            val delta = if (baseline.isNaN() || recent.isNaN()) Float.NaN else recent - baseline

            val steps = mean(r.steps)

            val (moderateWeek, vigorousWeek) = weekly(r.moderateMinutes, r.vigorousMinutes)
            val equivalent = if (moderateWeek.isNaN()) Float.NaN else HealthMath.moderateEquivalent(moderateWeek, vigorousWeek)
            val effortDays = (full - WEEK until full).filter { it >= 0 && !r.moderateMinutes[it].isNaN() }
            val activeDays = if (effortDays.size < MIN_DAYS) Float.NaN
            else effortDays.count { r.moderateMinutes[it] + r.vigorousMinutes[it] >= 10f }.toFloat() * WEEK / effortDays.size

            val (cadenceModerateWeek, cadenceVigorousWeek) = weekly(r.cadenceModerate, r.cadenceVigorous)
            val cadenceEquivalent = if (cadenceModerateWeek.isNaN()) Float.NaN else HealthMath.moderateEquivalent(cadenceModerateWeek, cadenceVigorousWeek)

            val paiValues = HealthMath.finite(r.pai)
            val pai = if (paiValues.size >= MIN_PAI_DAYS) paiValues.last() else Float.NaN

            val sleepDays = (n - WEEK until n).filter { it >= 0 && !r.sleepMinutes[it].isNaN() }
                .takeIf { it.size >= MIN_DAYS }.orEmpty()
            val sleepTotal = sleepDays.sumOf { r.sleepMinutes[it].toDouble() }
            fun share(series: FloatArray) = if (sleepTotal <= 0.0) Float.NaN else (sleepDays.sumOf { series[it].toDouble() } / sleepTotal).toFloat()
            val onset = HealthMath.finite(r.sleepOnset, n - 14, n)
            val sleepAverage = HealthMath.mean(sleepDays.map { r.sleepMinutes[it] })
            val efficiency = mean(r.sleepEfficiency, n)

            val trimpWeek = HealthMath.mean(HealthMath.finite(r.trimp, full - WEEK, full))
            val trimpAll = HealthMath.mean(HealthMath.finite(r.trimp, 0, full))
            val trimpChange = if (trimpWeek.isNaN() || trimpAll.isNaN() || trimpAll <= 0f) Float.NaN else trimpWeek / trimpAll - 1f

            val spo2 = mean(r.spo2Average)
            val stress = mean(r.stressAverage)
            val sedentaryHours = mean(r.sedentaryHours)
            val sedentaryBout = mean(r.sedentaryMeanBout)
            val recovery = HealthMath.finite(r.heartRateRecovery).lastOrNull() ?: Float.NaN

            val restingLevel = HealthMath.restingLevel(restingNow)
            val trendLevel = HealthMath.trendLevel(delta, sd)
            val stepsLevel = HealthMath.stepsLevel(steps, r.age)
            val activityLevel = HealthMath.activityLevel(equivalent)
            val sleepLevel = HealthMath.sleepLevel(sleepAverage)
            val sedentaryLevel = HealthMath.sedentaryLevel(sedentaryHours, sedentaryBout)
            val target = HealthMath.stepsTarget(r.age)

            val tips = ArrayList<Tip>()
            if (activityLevel == Level.FAIR || activityLevel == Level.ATTENTION) tips += Tip(Tip.Type.EFFORT, 150f - equivalent, activityLevel)
            if (stepsLevel == Level.FAIR || stepsLevel == Level.ATTENTION) tips += Tip(Tip.Type.STEPS, target - steps, stepsLevel)
            if (!sleepAverage.isNaN() && sleepAverage < 420f) tips += Tip(Tip.Type.SLEEP, 420f - sleepAverage, sleepLevel)
            if (sedentaryLevel == Level.FAIR || sedentaryLevel == Level.ATTENTION) tips += Tip(Tip.Type.SEDENTARY, sedentaryHours, sedentaryLevel)
            if (trendLevel == Level.FAIR || trendLevel == Level.ATTENTION) tips += Tip(Tip.Type.RESTING, delta, trendLevel)
            // Attention before "could be better", and the stable order of the list otherwise
            val ranked = tips.sortedBy { if (it.level == Level.ATTENTION) 0 else 1 }.take(MAX_TIPS)

            return HealthSummary(
                restingNow = restingNow,
                restingBaseline = baseline,
                restingRecent = recent,
                restingDelta = delta,
                restingSd = sd,
                stepsAverage = steps,
                moderateWeek = moderateWeek,
                vigorousWeek = vigorousWeek,
                equivalentWeek = equivalent,
                activeDays = activeDays,
                cadenceModerateWeek = cadenceModerateWeek,
                cadenceVigorousWeek = cadenceVigorousWeek,
                cadenceEquivalentWeek = cadenceEquivalent,
                pai = pai,
                sleepAverage = sleepAverage,
                deepShare = share(r.deepMinutes),
                remShare = share(r.remMinutes),
                lightShare = share(r.lightMinutes),
                onsetSd = if (onset.size >= 4) HealthMath.standardDeviation(onset) else Float.NaN,
                sleepEfficiency = efficiency,
                wakeAfterOnset = mean(r.awakeMinutes, n),
                sleepRegularity = r.sleepRegularity,
                spo2Average = spo2,
                spo2Minimum = HealthMath.finite(r.spo2Minimum, full - WEEK, full).minOrNull() ?: Float.NaN,
                spo2LowShare = mean(r.spo2LowShare),
                stressAverage = stress,
                sedentaryHours = sedentaryHours,
                sedentaryBout = sedentaryBout,
                sedentaryLongest = mean(r.sedentaryLongest),
                trimpChange = trimpChange,
                vo2max = HealthMath.vo2max(r.maxHeartRate, restingNow),
                recoveryDrop = recovery,
                restingLevel = restingLevel,
                trendLevel = trendLevel,
                stepsLevel = stepsLevel,
                activityLevel = activityLevel,
                cadenceLevel = HealthMath.activityLevel(cadenceEquivalent),
                paiLevel = HealthMath.paiLevel(pai),
                sleepLevel = sleepLevel,
                sleepQualityLevel = HealthMath.sleepQualityLevel(efficiency),
                spo2Level = HealthMath.spo2Level(spo2),
                stressLevel = HealthMath.stressLevel(stress),
                sedentaryLevel = sedentaryLevel,
                recoveryLevel = HealthMath.recoveryLevel(recovery),
                stepsTarget = target,
                tips = ranked,
            )
        }
    }
}
