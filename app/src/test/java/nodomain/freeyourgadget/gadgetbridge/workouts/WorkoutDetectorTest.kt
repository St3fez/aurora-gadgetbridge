package nodomain.freeyourgadget.gadgetbridge.workouts

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.workouts.WorkoutDetector.Minute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutDetectorTest {
    private val maxHr = 180

    /** [count] minutes from [start], each with the same steps and heart rate. */
    private fun minutes(start: Long, count: Int, steps: Int, hr: Int, sleeping: Boolean = false) =
        (0 until count).map { Minute(start + it, steps, hr, sleeping) }

    private fun detect(list: List<Minute>, minDuration: Int = 30) = WorkoutDetector.detect(list, maxHr, minDuration)

    @Test
    fun aLongWalkIsAWorkout() {
        val found = detect(minutes(1000, 40, steps = 100, hr = 120))
        assertEquals(1, found.size)
        val walk = found[0]
        assertEquals(40, walk.durationMinutes)
        assertEquals(ActivityKind.WALKING, walk.kind)
        assertEquals(120, walk.averageHr)
        assertEquals(4000, walk.steps)
        assertEquals(1000L * 60, walk.startSeconds)
        assertEquals(1040L * 60, walk.endSeconds)
    }

    @Test
    fun aShorterWalkNeedsALowerMinimum() {
        val walk = minutes(1000, 24, steps = 100, hr = 123)
        assertTrue(detect(walk, minDuration = 30).isEmpty())
        assertEquals(1, detect(walk, minDuration = 20).size)
    }

    @Test
    fun aShortPauseKeepsTheWorkoutTogether() {
        val list = minutes(1000, 20, 100, 120) + minutes(1024, 20, 100, 120)
        val found = detect(list)
        assertEquals(1, found.size)
        assertEquals(44, found[0].durationMinutes)
    }

    @Test
    fun aLongPauseSplitsIt() {
        val list = minutes(1000, 20, 100, 120) + minutes(1030, 20, 100, 120)
        assertTrue(detect(list).isEmpty())
        assertEquals(2, detect(list, minDuration = 15).size)
    }

    @Test
    fun sleepIsNeverAWorkout() {
        assertTrue(detect(minutes(1000, 60, steps = 100, hr = 130, sleeping = true)).isEmpty())
    }

    @Test
    fun aDayOfScatteredWalkingIsNotAWorkout() {
        val scattered = (0 until 100 step 5).flatMap { minutes(1000L + it, 1, steps = 100, hr = 100) }
        assertTrue(detect(scattered).isEmpty())
    }

    @Test
    fun fastStepsWithAHighHeartRateAreRunning() {
        val found = detect(minutes(1000, 35, steps = 160, hr = 150))
        assertEquals(ActivityKind.RUNNING, found.single().kind)
    }

    @Test
    fun aHighHeartRateWithoutStepsIsGenericExercise() {
        val found = detect(minutes(1000, 35, steps = 0, hr = 130))
        assertEquals(ActivityKind.EXERCISE, found.single().kind)
        assertEquals(130, found.single().maxHr)
    }

    @Test
    fun timeInEachZoneAddsUp() {
        // 120 bpm of 180 is 67%: zone 2 (60-69%); 150 bpm is 83%: zone 4 (80-89%)
        val found = detect(minutes(1000, 20, 100, 120) + minutes(1020, 20, 100, 150)).single()
        assertEquals(20 * 60, found.zoneSeconds[2])
        assertEquals(20 * 60, found.zoneSeconds[4])
        assertEquals(150, found.maxHr)
        assertEquals(120, found.minHr)
    }
}
