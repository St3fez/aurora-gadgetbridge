package nodomain.freeyourgadget.gadgetbridge.band

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Keeps the band's weather fresh: the temperature outside on the watchface and the weather screen. The phone asks
 * Open-Meteo for the city the user typed, every half hour, and hands the answer to the band support.
 */
object WeatherFeed {
    private val LOG = LoggerFactory.getLogger(WeatherFeed::class.java)

    private const val PREFS = "band_extras"
    private const val KEY_CITY = "weather_city"
    private const val KEY_PLACE = "weather_place"
    private const val KEY_LATITUDE = "weather_latitude"
    private const val KEY_LONGITUDE = "weather_longitude"
    private const val KEY_STATUS = "weather_status"
    private const val WORK_NAME = "band_weather"
    private const val TIMEOUT_MS = 10_000

    class Outcome(val ok: Boolean, val text: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun city(context: Context): String = prefs(context).getString(KEY_CITY, "").orEmpty()

    fun status(context: Context): String = prefs(context).getString(KEY_STATUS, "").orEmpty()

    private fun place(context: Context): OpenMeteo.Place? {
        val p = prefs(context)
        val name = p.getString(KEY_PLACE, null) ?: return null
        val latitude = p.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = p.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return OpenMeteo.Place(name, latitude, longitude)
    }

    /** Looks the city up, remembers it, fetches the weather once and starts the half-hourly updates. Blocks: run it off the main thread. */
    fun setCity(context: Context, city: String): Outcome {
        if (city.isBlank()) {
            prefs(context).edit().clear().apply()
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return Outcome(true, context.getString(R.string.band_weather_off))
        }
        return try {
            val place = OpenMeteo.parsePlace(get(OpenMeteo.geocodingUrl(city)))
                ?: return Outcome(false, context.getString(R.string.band_weather_not_found))
            prefs(context).edit()
                .putString(KEY_CITY, city.trim())
                .putString(KEY_PLACE, place.name)
                .putString(KEY_LATITUDE, place.latitude.toString())
                .putString(KEY_LONGITUDE, place.longitude.toString())
                .apply()
            ensureScheduled(context)
            refreshNow(context)
        } catch (e: Exception) {
            LOG.warn("Could not look up the city", e)
            Outcome(false, context.getString(R.string.band_weather_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Fetches the weather of the saved city and gives it to the band. Blocks: run it off the main thread. */
    fun refreshNow(context: Context): Outcome {
        val place = place(context) ?: return Outcome(false, context.getString(R.string.band_weather_off))
        return try {
            val now = (System.currentTimeMillis() / 1000).toInt()
            val spec = OpenMeteo.parseForecast(get(OpenMeteo.forecastUrl(place)), place, now)
            Weather.setWeatherSpec(listOf(spec))
            if (DeviceCommunicationService.isRunning(context)) GBApplication.deviceService().onSendWeather()
            val celsius = Math.round(spec.currentTemp - 273.15).toInt()
            val text = context.getString(
                R.string.band_weather_status, place.name, celsius, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
            )
            prefs(context).edit().putString(KEY_STATUS, text).apply()
            Outcome(true, text)
        } catch (e: Exception) {
            LOG.warn("Could not update the weather", e)
            Outcome(false, context.getString(R.string.band_weather_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Starts the half-hourly updates if a city is saved; harmless to call again. */
    fun ensureScheduled(context: Context) {
        if (place(context) == null) return
        val request = PeriodicWorkRequestBuilder<WeatherWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Gadgetbridge-Aurora")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    class WeatherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = if (refreshNow(applicationContext).ok) Result.success() else Result.retry()
    }
}
