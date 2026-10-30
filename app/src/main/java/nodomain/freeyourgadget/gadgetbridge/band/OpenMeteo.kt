package nodomain.freeyourgadget.gadgetbridge.band

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Reads the answers of Open-Meteo (open-meteo.com, free and without a key) and turns them into the [WeatherSpec] that the
 * band support already knows how to send: temperatures in kelvin, conditions as OpenWeatherMap codes.
 */
object OpenMeteo {
    const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
    const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    class Place(val name: String, val latitude: Double, val longitude: Double)

    fun geocodingUrl(city: String): String =
        "$GEOCODING?name=${java.net.URLEncoder.encode(city.trim(), "UTF-8")}&count=1&language=it&format=json"

    fun forecastUrl(place: Place): String =
        "$FORECAST?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,cloud_cover,surface_pressure," +
            "wind_speed_10m,wind_direction_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max" +
            "&timezone=auto&forecast_days=1&wind_speed_unit=kmh"

    fun kelvin(celsius: Double): Int = (celsius + 273.15).roundToInt()

    /** The WMO weather code that Open-Meteo reports, as the OpenWeatherMap condition code Gadgetbridge expects. */
    fun conditionCode(wmo: Int): Int = when (wmo) {
        0 -> 800
        1 -> 801
        2 -> 802
        3 -> 804
        45, 48 -> 741
        51 -> 300
        53 -> 301
        55 -> 302
        56, 57, 66, 67 -> 511
        61 -> 500
        63 -> 501
        65 -> 502
        71, 77 -> 600
        73 -> 601
        75 -> 602
        80 -> 520
        81 -> 521
        82 -> 522
        85 -> 620
        86 -> 621
        95 -> 200
        96, 99 -> 202
        else -> 3200 // unknown, the value WeatherSpec starts with
    }

    fun parsePlace(json: String): Place? {
        val first = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        val name = first.optString("name", "").ifEmpty { return null }
        if (!first.has("latitude") || !first.has("longitude")) return null
        return Place(name, first.getDouble("latitude"), first.getDouble("longitude"))
    }

    fun parseForecast(json: String, place: Place, nowSeconds: Int): WeatherSpec {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        fun day(key: String, fallback: Double = 0.0) = daily.optJSONArray(key)?.optDouble(0, fallback) ?: fallback

        return WeatherSpec().apply {
            timestamp = nowSeconds
            location = place.name
            latitude = place.latitude.toFloat()
            longitude = place.longitude.toFloat()
            isCurrentLocation = 0
            currentTemp = kelvin(current.getDouble("temperature_2m"))
            feelsLikeTemp = kelvin(current.optDouble("apparent_temperature", current.getDouble("temperature_2m")))
            currentConditionCode = conditionCode(current.optInt("weather_code", -1))
            currentCondition = ""
            currentHumidity = current.optInt("relative_humidity_2m", 0)
            cloudCover = current.optInt("cloud_cover", 0)
            pressure = current.optDouble("surface_pressure", 0.0).toFloat()
            windSpeed = current.optDouble("wind_speed_10m", 0.0).toFloat()
            windDirection = current.optInt("wind_direction_10m", 0)
            todayMaxTemp = kelvin(day("temperature_2m_max", current.getDouble("temperature_2m")))
            todayMinTemp = kelvin(day("temperature_2m_min", current.getDouble("temperature_2m")))
            precipProbability = day("precipitation_probability_max").roundToInt()
            uvIndex = day("uv_index_max").toFloat()
        }
    }
}
