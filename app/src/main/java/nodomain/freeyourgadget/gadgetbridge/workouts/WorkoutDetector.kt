package nodomain.freeyourgadget.gadgetbridge.workouts

import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryProgressEntry
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.health.HealthMath
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.HeartRateWidget
import org.slf4j.LoggerFactory
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds workouts in the activity data the band has already synced: a stretch of walking, running or other effort that
 * lasts at least the chosen number of minutes (30 by default). Each one is saved as a workout summary, so the
 * Workouts section, its details and its widgets show it like one the band recorded itself.
 *
 * A minute counts as active when the band counted at least [MOVING_STEPS] steps in it, or the heart rate reached the
 * moderate effort level (64% of the maximum). A pause of up to [MAX_PAUSE_MINUTES] minutes does not end the workout,
 * and sleep is never a workout. The stretch is only kept if most of its minutes were active, so a day of walking
 * around the house does not count. Workouts the band recorded are never touched or duplicated.
 */
object WorkoutDetector {
    private val LOG = LoggerFactory.getLogger(WorkoutDetector::class.java)

    const val PREF_MIN_MINUTES = "aurora_workout_min_minutes"
    const val DEFAULT_MIN_MINUTES = 30

    /** The minimum durations offered in the Workouts section. */
    val THRESHOLDS = intArrayOf(15, 20, 30, 45, 60)

    private const val PREF_AUTO = "aurora_auto_workouts"
    private const val PREF_SIGNATURE = "aurora_workout_scan_"
    private const val VERSION = 1
    private const val MOVING_STEPS = 40
    private const val MAX_PAUSE_MINUTES = 4
    private const val MIN_ACTIVE_SHARE = 0.6
    private const val WINDOW_DAYS = 120
    private const val MATCH_SECONDS = 600L
    private const val RUNNING_CADENCE = 140.0
    private const val WALKING_CADENCE = 50.0
    private const val ZONES = 6

    /** One minute of the band's data: the steps counted in it, the average heart rate (0 if none), and whether it was asleep. */
    class Minute(val minute: Long, val steps: Int, val heartRate: Int, val sleeping: Boolean)

    class Session(
        val startMinute: Long,
        val endMinute: Long,
        val activeMinutes: Int,
        val steps: Int,
        val averageHr: Int,
        val maxHr: Int,
        val minHr: Int,
        val zoneSeconds: IntArray,
        val kind: ActivityKind,
    ) {
        val durationMinutes: Int get() = (endMinute - startMinute + 1).toInt()
        val startSeconds: Long get() = startMinute * 60
        val endSeconds: Long get() = (endMinute + 1) * 60
    }

    fun minMinutes(): Int = GBApplication.getPrefs().preferences.getInt(PREF_MIN_MINUTES, DEFAULT_MIN_MINUTES)

    fun setMinMinutes(minutes: Int) {
        GBApplication.getPrefs().preferences.edit().putInt(PREF_MIN_MINUTES, minutes).apply()
    }

    /** Whether the workout summary [id] was found by this detector, rather than recorded by the band. */
    fun isAutoDetected(id: Long?): Boolean = id != null && autoEntries().any { it.first == id }

    //
    // Detection
    //

    /** Groups [samples] by minute. Heart rate is averaged over the valid readings of the minute, steps are summed. */
    fun bucket(samples: List<ActivitySample>, isValidHeartRate: (Int) -> Boolean): List<Minute> {
        val steps = HashMap<Long, Int>()
        val hrSum = HashMap<Long, Int>()
        val hrCount = HashMap<Long, Int>()
        val asleep = HashSet<Long>()
        for (sample in samples) {
            val minute = sample.timestamp / 60L
            steps[minute] = (steps[minute] ?: 0) + maxOf(sample.steps, 0)
            if (isValidHeartRate(sample.heartRate)) {
                hrSum[minute] = (hrSum[minute] ?: 0) + sample.heartRate
                hrCount[minute] = (hrCount[minute] ?: 0) + 1
            }
            if (isSleep(sample.kind)) asleep += minute
        }
        return steps.keys.sorted().map { minute ->
            val count = hrCount[minute] ?: 0
            Minute(minute, steps.getValue(minute), if (count == 0) 0 else (hrSum.getValue(minute).toDouble() / count).roundToInt(), minute in asleep)
        }
    }

    private fun isSleep(kind: ActivityKind): Boolean = when (kind) {
        ActivityKind.LIGHT_SLEEP, ActivityKind.DEEP_SLEEP, ActivityKind.REM_SLEEP, ActivityKind.SLEEP_ANY, ActivityKind.AWAKE_SLEEP -> true
        else -> false
    }

    /** The workouts of at least [minDurationMinutes] in [minutes], which must be in time order. */
    fun detect(minutes: List<Minute>, maxHeartRate: Int, minDurationMinutes: Int): List<Session> {
        val moderateFloor = HealthMath.MODERATE_FROM * maxHeartRate
        val sessions = ArrayList<Session>()
        var current = ArrayList<Minute>()

        fun close() {
            if (current.isNotEmpty()) toSession(current, maxHeartRate, moderateFloor, minDurationMinutes)?.let { sessions += it }
            current = ArrayList()
        }

        for (m in minutes) {
            val active = !m.sleeping && (m.steps >= MOVING_STEPS || (m.heartRate > 0 && m.heartRate >= moderateFloor))
            if (!active) continue
            if (current.isNotEmpty() && m.minute - current.last().minute > MAX_PAUSE_MINUTES + 1) close()
            current += m
        }
        close()
        return sessions
    }

    private fun toSession(active: List<Minute>, maxHeartRate: Int, moderateFloor: Float, minDurationMinutes: Int): Session? {
        val start = active.first().minute
        val end = active.last().minute
        val duration = (end - start + 1).toInt()
        if (duration < minDurationMinutes || active.size < duration * MIN_ACTIVE_SHARE) return null

        val steps = active.sumOf { it.steps }
        val heartRates = active.map { it.heartRate }.filter { it > 0 }
        val zones = IntArray(ZONES)
        for (hr in heartRates) zones[HeartRateWidget.zoneFor(hr, maxHeartRate)] += 60
        val average = if (heartRates.isEmpty()) 0 else heartRates.average().roundToInt()
        val cadence = steps.toDouble() / active.size
        val kind = when {
            cadence >= RUNNING_CADENCE && (average == 0 || average >= moderateFloor) -> ActivityKind.RUNNING
            cadence >= WALKING_CADENCE -> ActivityKind.WALKING
            else -> ActivityKind.EXERCISE
        }
        return Session(
            startMinute = start, endMinute = end, activeMinutes = active.size, steps = steps,
            averageHr = average, maxHr = heartRates.maxOrNull() ?: 0, minHr = heartRates.minOrNull() ?: 0,
            zoneSeconds = zones, kind = kind,
        )
    }

    //
    // Saving
    //

    /**
     * Scans the data of each device in [devices] and brings its detected workouts up to date: new ones are added,
     * ones that grew with a later sync are extended, and ones that no longer qualify (a longer minimum, say) are removed.
     * A device is skipped when nothing has changed since the last scan. A workout the user deleted stays deleted.
     */
    fun sync(devices: List<GBDevice>) {
        for (device in devices) {
            val coordinator = device.deviceCoordinator
            if (!coordinator.supportsHeartRateMeasurement(device) && !coordinator.supportsStepCounter(device)) continue
            try {
                syncDevice(device)
            } catch (e: Exception) {
                LOG.error("Could not detect the workouts of {}", device, e)
            }
        }
    }

    private fun syncDevice(device: GBDevice) {
        val prefs = GBApplication.getPrefs().preferences
        val minDuration = minMinutes()
        GBApplication.acquireDB().use { db ->
            val session = db.daoSession
            val dbDevice = DBHelper.findDevice(device, session) ?: return
            val provider = device.deviceCoordinator.getSampleProvider(device, session) ?: return
            val now = System.currentTimeMillis() / 1000L
            val from = now - WINDOW_DAYS * 86_400L
            val samples: List<ActivitySample> = provider.getAllActivitySamples(from.toInt(), now.toInt())
            val signature = "${samples.size}:${samples.maxOfOrNull { it.timestamp }}:$minDuration:$VERSION"
            val signatureKey = PREF_SIGNATURE + dbDevice.id
            if (prefs.getString(signatureKey, null) == signature) return

            val utils = HeartRateUtils.getInstance()
            val maxHr = HealthMath.maxHeartRate(ActivityUser().age)
            val detections = detect(bucket(samples) { utils.isValidHeartRateValue(it) }, maxHr, minDuration)

            val dao = session.baseActivitySummaryDao
            val rows = dao.queryBuilder()
                .where(BaseActivitySummaryDao.Properties.DeviceId.eq(dbDevice.id), BaseActivitySummaryDao.Properties.StartTime.ge(Date(from * 1000L)))
                .list()
            val existingIds = rows.mapNotNull { it.id }.toSet()
            val entries = autoEntries().toMutableList()
            val autoIds = entries.map { it.first }.toSet()
            val autoRows = rows.filter { it.id in autoIds }
            val manualRows = rows.filter { it.id !in autoIds }
            val matched = HashSet<Long>()
            val user = DBHelper.getUser(session)

            for (d in detections) {
                val startMs = d.startSeconds * 1000L
                val endMs = d.endSeconds * 1000L
                if (manualRows.any { overlaps(it, startMs - PAD_MS, endMs + PAD_MS) }) continue
                val data = summaryData(d, maxHr).toString()
                val row = autoRows.firstOrNull { it.id !in matched && overlaps(it, startMs, endMs) }
                if (row != null) {
                    val rowId = row.id!!
                    matched += rowId
                    row.startTime = Date(startMs)
                    row.endTime = Date(endMs)
                    row.summaryData = data
                    if (!row.overrideActivityKind) row.activityKind = d.kind.code
                    dao.update(row)
                    val index = entries.indexOfFirst { it.first == rowId }
                    if (index >= 0) entries[index] = rowId to d.startSeconds
                } else if (entries.any { it.first !in existingIds && abs(it.second - d.startSeconds) <= MATCH_SECONDS }) {
                    continue // the user deleted this one
                } else {
                    val created = BaseActivitySummary().apply {
                        startTime = Date(startMs)
                        endTime = Date(endMs)
                        activityKind = d.kind.code
                        setDevice(dbDevice)
                        setUser(user)
                        summaryData = data
                    }
                    dao.insert(created)
                    entries += created.id!! to d.startSeconds
                }
            }
            for (row in autoRows) {
                val rowId = row.id!!
                if (rowId in matched) continue
                dao.delete(row)
                entries.removeAll { it.first == rowId }
            }
            saveEntries(entries)
            prefs.edit().putString(signatureKey, signature).apply()
        }
    }

    private const val PAD_MS = 5 * 60_000L

    private fun overlaps(row: BaseActivitySummary, startMs: Long, endMs: Long): Boolean =
        row.startTime.time < endMs && row.endTime.time > startMs

    private fun summaryData(d: Session, maxHeartRate: Int): ActivitySummaryData {
        val data = ActivitySummaryData()
        data.add(ActivitySummaryEntries.ACTIVE_SECONDS, d.activeMinutes * 60, ActivitySummaryEntries.UNIT_SECONDS)
        data.add(ActivitySummaryEntries.STEPS, d.steps, ActivitySummaryEntries.UNIT_STEPS)
        val stride = ActivityUser().stepLengthCm
        if (stride > 0 && d.steps > 0) {
            data.add(ActivitySummaryEntries.DISTANCE_METERS, d.steps * stride / 100f, ActivitySummaryEntries.UNIT_METERS)
        }
        if (d.averageHr > 0) {
            data.add(ActivitySummaryEntries.HR_AVG, d.averageHr, ActivitySummaryEntries.UNIT_BPM)
            data.add(ActivitySummaryEntries.HR_MAX, d.maxHr, ActivitySummaryEntries.UNIT_BPM)
            data.add(ActivitySummaryEntries.HR_MIN, d.minHr, ActivitySummaryEntries.UNIT_BPM)
            val floors = HeartRateWidget.zoneFloors(maxHeartRate)
            val bounds = listOf(
                ActivitySummaryEntries.HR_ZONE_BOUND_1, ActivitySummaryEntries.HR_ZONE_BOUND_2, ActivitySummaryEntries.HR_ZONE_BOUND_3,
                ActivitySummaryEntries.HR_ZONE_BOUND_4, ActivitySummaryEntries.HR_ZONE_BOUND_5,
            )
            for (i in bounds.indices) data.add(bounds[i], floors[i], ActivitySummaryEntries.UNIT_BPM)

            val context = GBApplication.getContext()
            val names = listOf(
                ActivitySummaryEntries.HR_ZONE_NA, ActivitySummaryEntries.HR_ZONE_WARM_UP, ActivitySummaryEntries.HR_ZONE_FAT_BURN,
                ActivitySummaryEntries.HR_ZONE_AEROBIC, ActivitySummaryEntries.HR_ZONE_ANAEROBIC, ActivitySummaryEntries.HR_ZONE_EXTREME,
            )
            val colors = intArrayOf(
                0,
                ContextCompat.getColor(context, R.color.hr_zone_warm_up_color),
                ContextCompat.getColor(context, R.color.hr_zone_easy_color),
                ContextCompat.getColor(context, R.color.hr_zone_aerobic_color),
                ContextCompat.getColor(context, R.color.hr_zone_threshold_color),
                ContextCompat.getColor(context, R.color.hr_zone_maximum_color),
            )
            val total = d.zoneSeconds.sum().coerceAtLeast(1)
            for (i in names.indices) {
                data.add(names[i], ActivitySummaryProgressEntry(d.zoneSeconds[i], ActivitySummaryEntries.UNIT_SECONDS, 100 * d.zoneSeconds[i] / total, colors[i]))
            }
        }
        return data
    }

    /** The workouts this detector created, as pairs of summary id and start second. */
    private fun autoEntries(): List<Pair<Long, Long>> =
        GBApplication.getPrefs().preferences.getStringSet(PREF_AUTO, emptySet()).orEmpty().mapNotNull {
            val parts = it.split(":")
            val id = parts.getOrNull(0)?.toLongOrNull()
            val start = parts.getOrNull(1)?.toLongOrNull()
            if (id != null && start != null) id to start else null
        }

    private fun saveEntries(entries: List<Pair<Long, Long>>) {
        GBApplication.getPrefs().preferences.edit().putStringSet(PREF_AUTO, entries.map { "${it.first}:${it.second}" }.toHashSet()).apply()
    }
}
