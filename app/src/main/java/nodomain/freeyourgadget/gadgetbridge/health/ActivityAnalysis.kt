package nodomain.freeyourgadget.gadgetbridge.health

import nodomain.freeyourgadget.gadgetbridge.workouts.WorkoutDetector
import nodomain.freeyourgadget.gadgetbridge.workouts.WorkoutDetector.Minute
import kotlin.math.max

/** Numbers that come from the band's minute-by-minute steps, heart rate and sleep state. Days without enough data are NaN. */
class ActivityMetrics(days: Int) {
    /** Minutes a day at 100-129 steps a minute, and at 130 or more. */
    val cadenceModerate = FloatArray(days) { Float.NaN }
    val cadenceVigorous = FloatArray(days) { Float.NaN }

    /** Hours a day awake with almost no steps, the average length of those stretches and the longest one. */
    val sedentaryHours = FloatArray(days) { Float.NaN }
    val sedentaryMeanBout = FloatArray(days) { Float.NaN }
    val sedentaryLongest = FloatArray(days) { Float.NaN }

    /** How far the heart rate fell in the first minute after the day's hardest effort (workouts of 10 minutes or more). */
    val heartRateRecovery = FloatArray(days) { Float.NaN }

    /** Share (0..100) of minutes in the last week that were in the same state, asleep or awake, 24 hours earlier. */
    var sleepRegularity = Float.NaN
}

object ActivityAnalysis {
    /** A day needs this many minutes of awake data before its cadence and sitting numbers are trusted. */
    const val MIN_AWAKE_MINUTES = 600

    private const val MIN_RECOVERY_WORKOUT_MINUTES = 10
    private const val RECOVERY_PEAK_FROM = 0.7f
    private const val REGULARITY_DAYS = 8
    private const val MIN_REGULARITY_PAIRS = 2880
    private const val MINUTES_PER_DAY = 1440L

    /**
     * [minutes] must be in time order. [dayIndex] gives the day (0 until [days]) of an epoch second, or something outside
     * that range for a second that is not shown.
     */
    fun analyze(minutes: List<Minute>, dayIndex: (Long) -> Int, days: Int, maxHeartRate: Int): ActivityMetrics {
        val result = ActivityMetrics(days)
        val moderateFloor = HealthMath.MODERATE_FROM * maxHeartRate

        val moderate = IntArray(days)
        val vigorous = IntArray(days)
        val awake = IntArray(days)
        val still = IntArray(days)
        val bouts = IntArray(days)
        val longest = IntArray(days)
        var run = 0
        var runDay = -1
        var previous = Long.MIN_VALUE

        fun endRun() {
            if (run > 0 && runDay in 0 until days) {
                still[runDay] += run
                bouts[runDay]++
                longest[runDay] = max(longest[runDay], run)
            }
            run = 0
        }

        for (m in minutes) {
            val day = dayIndex(m.minute * 60)
            if (day !in 0 until days || m.sleeping) {
                endRun()
                previous = m.minute
                continue
            }
            awake[day]++
            if (m.steps >= HealthMath.CADENCE_VIGOROUS) vigorous[day]++ else if (m.steps >= HealthMath.CADENCE_MODERATE) moderate[day]++
            val sitting = m.steps <= HealthMath.SEDENTARY_STEPS && (m.heartRate == 0 || m.heartRate < moderateFloor)
            if (sitting) {
                if (run > 0 && (m.minute - previous != 1L || day != runDay)) endRun()
                run++
                runDay = day
            } else {
                endRun()
            }
            previous = m.minute
        }
        endRun()

        for (day in 0 until days) {
            if (awake[day] < MIN_AWAKE_MINUTES) continue
            result.cadenceModerate[day] = moderate[day].toFloat()
            result.cadenceVigorous[day] = vigorous[day].toFloat()
            result.sedentaryHours[day] = still[day] / 60f
            if (bouts[day] > 0) {
                result.sedentaryMeanBout[day] = still[day].toFloat() / bouts[day]
                result.sedentaryLongest[day] = longest[day].toFloat()
            }
        }

        recovery(minutes, dayIndex, days, maxHeartRate, result)
        result.sleepRegularity = regularity(minutes)
        return result
    }

    /** The fall in heart rate from the last two minutes of each workout to the minute after it, as in Cole et al. 1999. */
    private fun recovery(minutes: List<Minute>, dayIndex: (Long) -> Int, days: Int, maxHeartRate: Int, result: ActivityMetrics) {
        val byMinute = HashMap<Long, Minute>(minutes.size * 2)
        for (m in minutes) byMinute[m.minute] = m
        for (workout in WorkoutDetector.detect(minutes, maxHeartRate, MIN_RECOVERY_WORKOUT_MINUTES)) {
            val peak = listOfNotNull(byMinute[workout.endMinute], byMinute[workout.endMinute - 1])
                .map { it.heartRate }.filter { it > 0 }.maxOrNull() ?: continue
            if (peak < RECOVERY_PEAK_FROM * maxHeartRate) continue
            val after = byMinute[workout.endMinute + 1]?.heartRate?.takeIf { it > 0 } ?: continue
            val day = dayIndex(workout.endSeconds)
            if (day in 0 until days) result.heartRateRecovery[day] = (peak - after).toFloat()
        }
    }

    /** Percent of minutes with the same asleep or awake state as 24 hours earlier over the last week, which is Windred's SRI on a 0-100 scale. */
    private fun regularity(minutes: List<Minute>): Float {
        if (minutes.isEmpty()) return Float.NaN
        val from = minutes.last().minute - REGULARITY_DAYS * MINUTES_PER_DAY
        val asleep = HashMap<Long, Boolean>()
        for (m in minutes) if (m.minute >= from) asleep[m.minute] = m.sleeping
        var pairs = 0
        var same = 0
        for ((minute, state) in asleep) {
            val other = asleep[minute + MINUTES_PER_DAY] ?: continue
            pairs++
            if (state == other) same++
        }
        return if (pairs >= MIN_REGULARITY_PAIRS) 100f * same / pairs else Float.NaN
    }
}
