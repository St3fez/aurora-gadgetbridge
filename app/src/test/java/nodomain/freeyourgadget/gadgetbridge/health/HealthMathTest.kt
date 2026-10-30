package nodomain.freeyourgadget.gadgetbridge.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMathTest {
    private val days = 28

    private fun nan() = FloatArray(days) { Float.NaN }

    private fun report(
        steps: FloatArray = nan(),
        restingHr: FloatArray = nan(),
        lastDayIsToday: Boolean = false,
    ) = HealthReport(
        firstDayStart = 0L, dayCount = days, lastDayIsToday = lastDayIsToday, age = 40, maxHeartRate = 180, deviceAddress = null,
        steps = steps, restingHr = restingHr, averageHr = nan(), hrCoverageMinutes = nan(), moderateMinutes = nan(),
        vigorousMinutes = nan(), trimp = nan(), sleepMinutes = nan(), deepMinutes = nan(), remMinutes = nan(),
        lightMinutes = nan(), awakeMinutes = nan(), sleepOnset = nan(), spo2Average = nan(), spo2Minimum = nan(),
        spo2LowShare = nan(), stressAverage = nan(), pai = nan(),
    )

    @Test
    fun maximumHeartRateFollowsTanaka() {
        assertEquals(180, HealthMath.maxHeartRate(40))
        assertEquals(166, HealthMath.maxHeartRate(60))
    }

    @Test
    fun intensityStartsAt64And77PercentOfMaximum() {
        assertEquals(0, HealthMath.intensity(115, 180))
        assertEquals(1, HealthMath.intensity(116, 180))
        assertEquals(1, HealthMath.intensity(138, 180))
        assertEquals(2, HealthMath.intensity(139, 180))
    }

    @Test
    fun vigorousMinutesCountDouble() {
        assertEquals(50f, HealthMath.moderateEquivalent(30f, 10f), 0.001f)
    }

    @Test
    fun uthEstimateIsFifteenPointThreeTimesTheRatio() {
        assertEquals(45.9f, HealthMath.vo2max(180, 60f), 0.01f)
        assertTrue(HealthMath.vo2max(180, 0f).isNaN())
        assertEquals(13.11f, HealthMath.mets(45.9f), 0.01f)
    }

    @Test
    fun trimpWeighsEffortByHeartRateReserve() {
        assertEquals(8.357, HealthMath.trimp(10.0, 120, 60, 180, 1.92), 0.01)
        assertEquals(0.0, HealthMath.trimp(10.0, 60, 60, 180, 1.92), 0.0)
        assertEquals(0.0, HealthMath.trimp(10.0, 120, 180, 180, 1.92), 0.0)
    }

    @Test
    fun percentileAndMedian() {
        assertEquals(1, HealthMath.percentile(listOf(5, 1, 3, 2, 4), 0.10))
        assertEquals(3, HealthMath.percentile(listOf(5, 1, 3, 2, 4), 0.5))
        assertEquals(Int.MIN_VALUE, HealthMath.percentile(emptyList(), 0.5))
        assertEquals(3f, HealthMath.median(listOf(5f, 1f, 3f)), 0f)
        assertEquals(2.5f, HealthMath.median(listOf(1f, 2f, 3f, 4f)), 0f)
        assertTrue(HealthMath.median(emptyList()).isNaN())
    }

    @Test
    fun restingHeartRateAbove80NeedsAttention() {
        assertEquals(Level.GOOD, HealthMath.restingLevel(59f))
        assertEquals(Level.GOOD, HealthMath.restingLevel(80f))
        assertEquals(Level.ATTENTION, HealthMath.restingLevel(81f))
        assertEquals(Level.NONE, HealthMath.restingLevel(Float.NaN))
    }

    @Test
    fun aRiseOverTheUsualRestingRateDependsOnTheSpread() {
        assertEquals(Level.GOOD, HealthMath.trendLevel(2f, 1f))
        assertEquals(Level.FAIR, HealthMath.trendLevel(4f, 1f))
        assertEquals(Level.ATTENTION, HealthMath.trendLevel(6f, 1f))
        assertEquals(Level.FAIR, HealthMath.trendLevel(6f, 4f))
        assertEquals(Level.NONE, HealthMath.trendLevel(Float.NaN, 1f))
    }

    @Test
    fun stepTargetsDependOnAge() {
        assertEquals(Level.GOOD, HealthMath.stepsLevel(8000f, 35))
        assertEquals(Level.FAIR, HealthMath.stepsLevel(7000f, 35))
        assertEquals(Level.ATTENTION, HealthMath.stepsLevel(5000f, 35))
        assertEquals(Level.GOOD, HealthMath.stepsLevel(6500f, 65))
    }

    @Test
    fun sleepOfSevenHoursIsOnTarget() {
        assertEquals(Level.GOOD, HealthMath.sleepLevel(420f))
        assertEquals(Level.FAIR, HealthMath.sleepLevel(400f))
        assertEquals(Level.ATTENTION, HealthMath.sleepLevel(359f))
    }

    @Test
    fun weeklyEffortFollowsTheWhoTargets() {
        assertEquals(Level.GOOD, HealthMath.activityLevel(150f))
        assertEquals(Level.FAIR, HealthMath.activityLevel(100f))
        assertEquals(Level.ATTENTION, HealthMath.activityLevel(40f))
        assertEquals(Level.GOOD, HealthMath.paiLevel(100f))
        assertEquals(Level.FAIR, HealthMath.paiLevel(60f))
    }

    @Test
    fun longSleepIsOnlyFlaggedGently() {
        assertEquals(Level.GOOD, HealthMath.sleepLevel(540f))
        assertEquals(Level.FAIR, HealthMath.sleepLevel(560f))
    }

    @Test
    fun sleepEfficiencyFollowsTheNationalSleepFoundation() {
        assertEquals(Level.GOOD, HealthMath.sleepQualityLevel(90f))
        assertEquals(Level.FAIR, HealthMath.sleepQualityLevel(80f))
        assertEquals(Level.ATTENTION, HealthMath.sleepQualityLevel(70f))
    }

    @Test
    fun sittingIsRatedOnTotalTimeAndLengthOfBouts() {
        assertEquals(Level.GOOD, HealthMath.sedentaryLevel(9f, 8f))
        assertEquals(Level.FAIR, HealthMath.sedentaryLevel(13f, 8f))
        assertEquals(Level.FAIR, HealthMath.sedentaryLevel(9f, 13f))
        assertEquals(Level.ATTENTION, HealthMath.sedentaryLevel(13f, 11f))
        assertEquals(Level.NONE, HealthMath.sedentaryLevel(Float.NaN, 11f))
    }

    @Test
    fun aHeartRateFallOf12OrLessInTheFirstMinuteNeedsAttention() {
        assertEquals(Level.ATTENTION, HealthMath.recoveryLevel(12f))
        assertEquals(Level.GOOD, HealthMath.recoveryLevel(13f))
    }

    @Test
    fun oxygenAndStressLevels() {
        assertEquals(Level.GOOD, HealthMath.spo2Level(96f))
        assertEquals(Level.FAIR, HealthMath.spo2Level(92f))
        assertEquals(Level.ATTENTION, HealthMath.spo2Level(89f))
        assertEquals(Level.GOOD, HealthMath.stressLevel(45f))
        assertEquals(Level.FAIR, HealthMath.stressLevel(65f))
        assertEquals(Level.ATTENTION, HealthMath.stressLevel(85f))
    }

    @Test
    fun aReportWithoutDataSaysSo() {
        val empty = report()
        assertFalse(empty.hasAnyData)
        assertEquals(0, empty.summary.measured)
    }

    @Test
    fun theIncompleteDayIsLeftOutOfTheStepAverage() {
        val steps = FloatArray(days) { 8000f }
        steps[days - 1] = 100f
        assertEquals(8000f, report(steps = steps, lastDayIsToday = true).summary.stepsAverage, 0.01f)
        assertTrue(report(steps = steps, lastDayIsToday = false).summary.stepsAverage < 8000f)
    }

    @Test
    fun aNumberNeedsThreeDaysOfData() {
        val steps = nan()
        steps[days - 1] = 9000f
        steps[days - 2] = 9000f
        assertTrue(report(steps = steps).summary.stepsAverage.isNaN())
        steps[days - 3] = 9000f
        assertEquals(9000f, report(steps = steps).summary.stepsAverage, 0.01f)
    }

    @Test
    fun usualRestingRateNeedsFiveEarlierDays() {
        val resting = nan()
        for (i in days - 3 until days) resting[i] = 70f
        assertTrue(report(restingHr = resting).summary.restingBaseline.isNaN())

        for (i in 0 until 10) resting[i] = 60f
        val summary = report(restingHr = resting).summary
        assertEquals(60f, summary.restingBaseline, 0f)
        assertEquals(10f, summary.restingDelta, 0f)
        assertEquals(Level.ATTENTION, summary.trendLevel)
    }

    @Test
    fun theSampleReportHasAllIndicators() {
        val summary = HealthReport.preview().summary
        assertEquals(12, summary.measured)
        assertEquals(Level.GOOD, summary.restingLevel)
        assertTrue(summary.inRange in 1..12)
    }
}
