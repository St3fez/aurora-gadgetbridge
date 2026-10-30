package nodomain.freeyourgadget.gadgetbridge.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenMeteoTest {
    private val place = OpenMeteo.Place("Roma", 41.89, 12.48)

    @Test
    fun temperaturesAreSentInKelvin() {
        assertEquals(273, OpenMeteo.kelvin(0.0))
        assertEquals(292, OpenMeteo.kelvin(18.4))
        assertEquals(269, OpenMeteo.kelvin(-4.0))
    }

    @Test
    fun wmoCodesBecomeOpenWeatherMapCodes() {
        assertEquals(800, OpenMeteo.conditionCode(0))
        assertEquals(500, OpenMeteo.conditionCode(61))
        assertEquals(601, OpenMeteo.conditionCode(73))
        assertEquals(200, OpenMeteo.conditionCode(95))
        assertEquals(3200, OpenMeteo.conditionCode(1234))
    }

    @Test
    fun theFirstCityFoundIsUsed() {
        val json = """{"results":[{"name":"Roma","latitude":41.89193,"longitude":12.51133,"country":"Italia"},{"name":"Roma","latitude":1.0,"longitude":2.0}]}"""
        val found = OpenMeteo.parsePlace(json)!!
        assertEquals("Roma", found.name)
        assertEquals(41.89193, found.latitude, 1e-9)
        assertEquals(12.51133, found.longitude, 1e-9)
    }

    @Test
    fun anUnknownCityHasNoResults() {
        assertNull(OpenMeteo.parsePlace("""{"generationtime_ms":0.5}"""))
        assertNull(OpenMeteo.parsePlace("""{"results":[]}"""))
    }

    @Test
    fun theForecastFillsWhatTheBandNeeds() {
        val json = """
            {"current":{"time":"2026-10-30T17:00","temperature_2m":18.4,"relative_humidity_2m":62,"apparent_temperature":17.0,
              "weather_code":3,"cloud_cover":90,"surface_pressure":1011.2,"wind_speed_10m":12.5,"wind_direction_10m":200},
             "daily":{"temperature_2m_max":[21.2],"temperature_2m_min":[11.0],"precipitation_probability_max":[40],"uv_index_max":[3.4]}}
        """
        val spec = OpenMeteo.parseForecast(json, place, 1_000)
        assertEquals(292, spec.currentTemp)
        assertEquals(804, spec.currentConditionCode)
        assertEquals(62, spec.currentHumidity)
        assertEquals(OpenMeteo.kelvin(21.2), spec.todayMaxTemp)
        assertEquals(OpenMeteo.kelvin(11.0), spec.todayMinTemp)
        assertEquals(40, spec.precipProbability)
        assertEquals(1_000, spec.timestamp)
        assertEquals("Roma", spec.location)
    }

    @Test
    fun aForecastWithoutTheDailyNumbersStillWorks() {
        val json = """{"current":{"temperature_2m":5.0,"weather_code":0},"daily":{"temperature_2m_max":[null],"temperature_2m_min":[null]}}"""
        val spec = OpenMeteo.parseForecast(json, place, 1)
        assertEquals(OpenMeteo.kelvin(5.0), spec.currentTemp)
        assertEquals(OpenMeteo.kelvin(5.0), spec.todayMaxTemp)
        assertEquals(800, spec.currentConditionCode)
    }
}
