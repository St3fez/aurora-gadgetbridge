package nodomain.freeyourgadget.gadgetbridge.health

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a measured value compares with its reference. [NONE] means there is not enough data to say. */
enum class Level { GOOD, FAIR, ATTENTION, NONE }

/**
 * The formulas and reference values behind the health report. Each one comes from a published source (named next to
 * it), and none of them is a diagnosis: they compare a wrist sensor's estimates with what large studies found.
 */
object HealthMath {
    /** Tanaka, Monahan and Seals, JACC 2001: 208 - 0.7 x age, from 351 studies; it does not under-estimate older adults. */
    fun maxHeartRate(age: Int): Int = (208 - 0.7 * age).roundToInt()

    /** Walking at about 100 steps a minute is moderate effort and from about 130 it is vigorous (Tudor-Locke et al., BJSM 2018, 38 studies). */
    const val CADENCE_MODERATE = 100
    const val CADENCE_VIGOROUS = 130

    /** A minute with this many steps or fewer, while awake and at an easy heart rate, counts as sitting still. */
    const val SEDENTARY_STEPS = 3

    /** Moderate effort starts at 64% of the maximum heart rate and vigorous effort at 77% (ACSM relative intensity). */
    const val MODERATE_FROM = 0.64f
    const val VIGOROUS_FROM = 0.77f

    /** 0 below moderate effort, 1 for moderate, 2 for vigorous. */
    fun intensity(bpm: Int, maxHeartRate: Int): Int = when {
        bpm >= maxHeartRate * VIGOROUS_FROM -> 2
        bpm >= maxHeartRate * MODERATE_FROM -> 1
        else -> 0
    }

    /** A vigorous minute counts as two moderate ones, as in the WHO 2020 guideline (150-300 moderate or 75-150 vigorous). */
    fun moderateEquivalent(moderateMinutes: Float, vigorousMinutes: Float): Float = moderateMinutes + 2f * vigorousMinutes

    /** Banister's training impulse for one stretch of [minutes] at [bpm]; [weight] is 1.92 for men and 1.67 for women. */
    fun trimp(minutes: Double, bpm: Int, resting: Int, maxHeartRate: Int, weight: Double): Double {
        if (maxHeartRate <= resting) return 0.0
        val reserve = ((bpm - resting).toDouble() / (maxHeartRate - resting)).coerceIn(0.0, 1.0)
        return minutes * reserve * 0.64 * exp(weight * reserve)
    }

    /** Uth et al. 2004, heart rate ratio method: 15.3 x maximum / resting. A rough estimate, not a stress test. */
    fun vo2max(maxHeartRate: Int, resting: Float): Float =
        if (resting > 0f) 15.3f * maxHeartRate / resting else Float.NaN

    /** One MET is 3.5 ml/kg/min of oxygen. */
    fun mets(vo2max: Float): Float = vo2max / 3.5f

    /** The [p] percentile (0..1) of [values], or [Int.MIN_VALUE] when there are none. */
    fun percentile(values: List<Int>, p: Double): Int {
        if (values.isEmpty()) return Int.MIN_VALUE
        val sorted = values.sorted()
        return sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    /** The values of [series] in [from] until [to] that are not NaN. */
    fun finite(series: FloatArray, from: Int = 0, to: Int = series.size): List<Float> =
        (from.coerceAtLeast(0) until to.coerceAtMost(series.size)).map { series[it] }.filter { !it.isNaN() }

    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    fun mean(values: List<Float>): Float = if (values.isEmpty()) Float.NaN else values.sum() / values.size

    fun standardDeviation(values: List<Float>): Float {
        if (values.size < 2) return Float.NaN
        val mean = mean(values)
        return sqrt(values.sumOf { (it - mean).toDouble().pow(2) } / (values.size - 1)).toFloat()
    }

    //
    // Levels
    //

    /**
     * Zhang, Shen and Qi, CMAJ 2016 (46 cohorts): above 80 bpm the cardiovascular death risk is 1.33 times the one
     * below 60, and each 10 bpm adds about 8%. Between 60 and 80 the difference was not significant.
     */
    fun restingLevel(bpm: Float): Level = when {
        bpm.isNaN() -> Level.NONE
        bpm <= 80f -> Level.GOOD
        else -> Level.ATTENTION
    }

    /**
     * Resting heart rate over the last days against the person's own usual one. The thresholds (3 bpm, 5 bpm, or 1.5
     * and 2 standard deviations) are this app's, not a published cut-off: a rise over the usual one has been seen
     * with infections (Hirten et al., JMIR 2021), but it also follows poor sleep, alcohol, stress and heat.
     */
    fun trendLevel(delta: Float, sd: Float): Level {
        if (delta.isNaN()) return Level.NONE
        val spread = if (sd.isNaN()) 0f else sd
        return when {
            delta >= maxOf(5f, 2f * spread) -> Level.ATTENTION
            delta >= maxOf(3f, 1.5f * spread) -> Level.FAIR
            else -> Level.GOOD
        }
    }

    /** Paluch et al., Lancet Public Health 2022: the benefit levels off at 8,000-10,000 steps (under 60) or 6,000-8,000 (60 and over). */
    fun stepsTarget(age: Int): Int = if (age >= 60) 6000 else 8000

    /** The second quartile of that study (median 5,801 steps) already had a 40% lower death rate than the least active. */
    fun stepsLevel(average: Float, age: Int): Level = when {
        average.isNaN() -> Level.NONE
        average >= stepsTarget(age) -> Level.GOOD
        average >= 5500f -> Level.FAIR
        else -> Level.ATTENTION
    }

    /** WHO 2020: 150-300 minutes of moderate effort a week, or 75-150 vigorous (a vigorous minute counts double). */
    fun activityLevel(weeklyEquivalent: Float): Level = when {
        weeklyEquivalent.isNaN() -> Level.NONE
        weeklyEquivalent >= 150f -> Level.GOOD
        weeklyEquivalent >= 75f -> Level.FAIR
        else -> Level.ATTENTION
    }

    /** Nes et al., HUNT study 2017: a weekly PAI of 100 or more went with 17% (men) and 23% (women) lower cardiovascular mortality. */
    fun paiLevel(pai: Float): Level = when {
        pai.isNaN() -> Level.NONE
        pai >= 100f -> Level.GOOD
        pai >= 50f -> Level.FAIR
        else -> Level.ATTENTION
    }

    /**
     * Watson et al. 2015 (AASM and Sleep Research Society): adults should sleep 7 hours or more. Cappuccio et al. 2010
     * (16 studies, 1.4 million people) found 12% more deaths with short sleep and 30% more with long sleep, which is
     * more often a sign of illness than a cause, so over 9 hours is only flagged gently.
     */
    fun sleepLevel(averageMinutes: Float): Level = when {
        averageMinutes.isNaN() -> Level.NONE
        averageMinutes > 540f -> Level.FAIR
        averageMinutes >= 420f -> Level.GOOD
        averageMinutes >= 360f -> Level.FAIR
        else -> Level.ATTENTION
    }

    /** National Sleep Foundation consensus (Ohayon et al., Sleep Health 2017): a sleep efficiency of 85% or more is good. */
    fun sleepQualityLevel(efficiencyPercent: Float): Level = when {
        efficiencyPercent.isNaN() -> Level.NONE
        efficiencyPercent >= 85f -> Level.GOOD
        efficiencyPercent >= 75f -> Level.FAIR
        else -> Level.ATTENTION
    }

    /**
     * Diaz et al., Ann Intern Med 2017 (7,985 adults): the highest risk was in people sitting 12.5 hours or more a day
     * in bouts of 10 minutes or more. Having only one of the two is flagged gently. The band counts steps, not posture,
     * so this is an approximation of what that study measured with a hip accelerometer.
     */
    fun sedentaryLevel(hoursPerDay: Float, meanBoutMinutes: Float): Level {
        if (hoursPerDay.isNaN() || meanBoutMinutes.isNaN()) return Level.NONE
        val long = hoursPerDay >= 12.5f
        val sustained = meanBoutMinutes >= 10f
        return when {
            long && sustained -> Level.ATTENTION
            long || meanBoutMinutes >= 12.4f -> Level.FAIR
            else -> Level.GOOD
        }
    }

    /** Cole et al., N Engl J Med 1999 (2,428 adults, 6 years): a heart rate fall of 12 bpm or less in the first minute after effort doubled the risk of death. */
    fun recoveryLevel(dropBpm: Float): Level = when {
        dropBpm.isNaN() -> Level.NONE
        dropBpm <= 12f -> Level.ATTENTION
        else -> Level.GOOD
    }

    /** Usual resting values are 95-100%; repeated readings under 90% are worth a medical check. */
    fun spo2Level(average: Float): Level = when {
        average.isNaN() -> Level.NONE
        average >= 95f -> Level.GOOD
        average >= 90f -> Level.FAIR
        else -> Level.ATTENTION
    }

    /** The band's own stress scale: 1-39 relaxed, 40-59 normal, 60-79 medium, 80-99 high. */
    fun stressLevel(average: Float): Level = when {
        average.isNaN() -> Level.NONE
        average < 60f -> Level.GOOD
        average < 80f -> Level.FAIR
        else -> Level.ATTENTION
    }
}
