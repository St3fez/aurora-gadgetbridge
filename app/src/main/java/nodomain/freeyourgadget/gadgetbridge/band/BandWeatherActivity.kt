package nodomain.freeyourgadget.gadgetbridge.band

import android.os.Bundle
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity

/** Where the user types the city whose temperature the band shows on its watchface and weather screen. */
class BandWeatherActivity : AbstractGBActivity() {
    private lateinit var city: TextInputEditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_band_weather)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        city = findViewById(R.id.band_weather_city)
        status = findViewById(R.id.band_weather_status)
        city.setText(WeatherFeed.city(this))
        status.text = WeatherFeed.status(this).ifEmpty { getString(R.string.band_weather_off) }
        findViewById<Button>(R.id.band_weather_save).setOnClickListener { save() }
        city.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) save()
            action == EditorInfo.IME_ACTION_DONE
        }
    }

    private fun save() {
        val name = city.text.toString()
        val appContext = applicationContext
        status.text = getString(R.string.band_weather_working)
        Thread {
            val result = WeatherFeed.setCity(appContext, name)
            runOnUiThread { status.text = result.text }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
