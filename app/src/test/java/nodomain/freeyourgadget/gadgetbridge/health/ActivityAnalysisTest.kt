package nodomain.freeyourgadget.gadgetbridge.health

import nodomain.freeyourgadget.gadgetbridge.workouts.WorkoutDetector.Minute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAnalysisTest {
    private val maxHr = 180

    private fun dayOf(seconds: Long) = (seconds / 86_400L).toInt()

    private fun block(start: Int, count: Int, steps: Int, hr: Int = 0, sleeping: Boolean = false) =
        (0 until count).map { Minute((start + it).toLong(), steps, hr, sleeping) }

    @Test
    fun cadenceAndSittingFromAFullDay() {
        // 300 still, 60 at 110 steps, 40 at 135 steps, then 300 still: 700 awake minutes
        val day = block(0, 300, 0) + block(300, 60, 110) + block(360, 40, 135) + block(400, 300, 0)
        val metrics = ActivityAnalysis.analyze(day, ::dayOf, 1, maxHr)
        assertEquals(60f, metrics.cadenceModerate[0], 0f)
        assertEquals(40f, metrics.cadenceVigorous[0], 0f)
        assertEquals(10f, metrics.sedentaryHours[0], 0.001f)
        assertEquals(300f, metrics.sedentaryMeanBout[0], 0f)
        assertEquals(300f, metrics.sedentaryLongest[0], 0f)
    }

    @Test
    fun aDayWithTooLittleDataIsLeftOut() {
        val metrics = ActivityAnalysis.analyze(block(0, 300, 0), ::dayOf, 1, maxHr)
        assertTrue(metrics.sedentaryHours[0].isNaN())
        assertTrue(metrics.cadenceModerate[0].isNaN())
    }

    @Test
    fun sleepIsNotSittingStill() {
        val day = block(0, 400, 0, sleeping = true) + block(400, 700, 0)
        val metrics = ActivityAnalysis.analyze(day, ::dayOf, 1, maxHr)
        assertEquals(700f / 60f, metrics.sedentaryHours[0], 0.001f)
    }

    @Test
    fun aMissingStretchOfDataBreaksTheSittingBout() {
        // Two stretches of still minutes with an hour of no data between them are two bouts, not one long one
        val day = block(0, 350, 0) + block(410, 350, 0)
        val metrics = ActivityAnalysis.analyze(day, ::dayOf, 1, maxHr)
        assertEquals(350f, metrics.sedentaryLongest[0], 0f)
        assertEquals(350f, metrics.sedentaryMeanBout[0], 0f)
    }

    @Test
    fun recoveryIsTheFallInTheMinuteAfterTheWorkout() {
        val minutes = block(1000, 40, steps = 100, hr = 150) + block(1040, 5, steps = 0, hr = 100)
        val metrics = ActivityAnalysis.analyze(minutes, { 0 }, 1, maxHr)
        assertEquals(50f, metrics.heartRateRecovery[0], 0f)
    }

    @Test
    fun anEasyWorkoutHasNoRecoveryNumber() {
        // 100 bpm is under 70% of the maximum, so the drop would say nothing about recovery from hard effort
        val minutes = block(1000, 40, steps = 100, hr = 100) + block(1040, 5, steps = 0, hr = 80)
        assertTrue(ActivityAnalysis.analyze(minutes, { 0 }, 1, maxHr).heartRateRecovery[0].isNaN())
    }

    private fun week(sleepStart: (Int) -> Int): List<Minute> = (0 until 8).flatMap { day ->
        val start = sleepStart(day)
        (0 until 1440).map { Minute(day * 1440L + it, 0, 0, it in start until start + 420) }
    }

    @Test
    fun sameSleepTimesEveryDayAreFullyRegular() {
        assertEquals(100f, ActivityAnalysis.analyze(week { 0 }, ::dayOf, 8, maxHr).sleepRegularity, 0f)
    }

    @Test
    fun aShiftedNightLowersRegularity() {
        val regularity = ActivityAnalysis.analyze(week { if (it == 3) 120 else 0 }, ::dayOf, 8, maxHr).sleepRegularity
        assertTrue(regularity < 100f)
        assertTrue(regularity > 90f)
    }

    @Test
    fun twoDaysOfDataAreNotEnoughForRegularity() {
        val minutes = (0 until 2).flatMap { day -> (0 until 1440).map { Minute(day * 1440L + it, 0, 0, it < 420) } }
        assertTrue(ActivityAnalysis.analyze(minutes, ::dayOf, 2, maxHr).sleepRegularity.isNaN())
    }
}
