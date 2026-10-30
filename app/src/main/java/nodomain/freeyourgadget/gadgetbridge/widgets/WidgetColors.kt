package nodomain.freeyourgadget.gadgetbridge.widgets

import android.graphics.Color
import androidx.annotation.ColorInt
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.health.Level

/**
 * Shared palette for the dashboard widgets. It follows the theme chosen in the Themes screen
 * (Aurora or Neon Pulse), so every color is read when it is used and never cached.
 */
object WidgetColors {
    private fun pick(aurora: Int, neon: Int): Int = if (GBApplication.isNeonThemeEnabled()) neon else aurora

    @get:ColorInt
    val unknown: Int get() = Color.argb(25, 128, 128, 128)

    @get:ColorInt
    val notWorn: Int get() = pick(Color.rgb(0x2A, 0x2E, 0x5C), Color.rgb(0x24, 0x24, 0x24))

    @get:ColorInt
    val worn: Int get() = pick(Color.rgb(0x8A, 0x8F, 0xBF), Color.rgb(0x8A, 0x8A, 0x8A))

    @get:ColorInt
    val activity: Int get() = pick(Color.rgb(0x22, 0xD3, 0xEE), Color.rgb(0xC6, 0xFF, 0x3D))

    @get:ColorInt
    val exercise: Int get() = pick(Color.rgb(0xF4, 0x72, 0xB6), Color.rgb(0xFF, 0x3D, 0x6E))

    @get:ColorInt
    val deepSleep: Int get() = pick(Color.rgb(0x63, 0x66, 0xF1), Color.rgb(0x3D, 0x5B, 0xFF))

    @get:ColorInt
    val lightSleep: Int get() = pick(Color.rgb(0x38, 0xBD, 0xF8), Color.rgb(0x3D, 0xA5, 0xFF))

    @get:ColorInt
    val remSleep: Int get() = pick(Color.rgb(0xE8, 0x79, 0xF9), Color.rgb(0xE8, 0x5B, 0xFF))

    @get:ColorInt
    val awakeSleep: Int get() = pick(Color.rgb(0xFB, 0x71, 0x85), Color.rgb(0xFF, 0xB0, 0x3D))

    @get:ColorInt
    val distance: Int get() = pick(Color.rgb(0xA7, 0x8B, 0xFA), Color.rgb(0xFF, 0xB0, 0x3D))

    @get:ColorInt
    val activeTime: Int get() = pick(Color.rgb(0xF4, 0x72, 0xB6), Color.rgb(0xFF, 0x3D, 0x6E))

    // Cool to hot: cyan at rest, then indigo, violet, pink, rose and red
    private val AURORA_ZONES = intArrayOf(
        Color.rgb(0x22, 0xD3, 0xEE), Color.rgb(0x81, 0x8C, 0xF8), Color.rgb(0xA7, 0x8B, 0xFA),
        Color.rgb(0xF4, 0x72, 0xB6), Color.rgb(0xFB, 0x71, 0x85), Color.rgb(0xFF, 0x4D, 0x6D),
    )

    // Lime at rest, then yellow, amber, orange, red and magenta
    private val NEON_ZONES = intArrayOf(
        Color.rgb(0xC6, 0xFF, 0x3D), Color.rgb(0xFF, 0xE0, 0x3D), Color.rgb(0xFF, 0xB0, 0x3D),
        Color.rgb(0xFF, 0x7A, 0x3D), Color.rgb(0xFF, 0x3D, 0x6E), Color.rgb(0xFF, 0x3D, 0xD0),
    )

    // Bright accents for the icons of the device card, each with its own hue
    private val AURORA_ACCENTS = intArrayOf(
        Color.rgb(0x22, 0xD3, 0xEE), Color.rgb(0xA7, 0x8B, 0xFA), Color.rgb(0xF4, 0x72, 0xB6), Color.rgb(0xFB, 0xBF, 0x24),
        Color.rgb(0x34, 0xD3, 0x99), Color.rgb(0x60, 0xA5, 0xFA), Color.rgb(0xFB, 0x92, 0x3C), Color.rgb(0xFB, 0x71, 0x85),
    )
    private val NEON_ACCENTS = intArrayOf(
        Color.rgb(0xC6, 0xFF, 0x3D), Color.rgb(0xFF, 0xE0, 0x3D), Color.rgb(0xFF, 0x7A, 0x3D), Color.rgb(0xFF, 0x3D, 0xD0),
        Color.rgb(0x3D, 0xE0, 0xFF), Color.rgb(0x3D, 0xA5, 0xFF), Color.rgb(0xFF, 0xB0, 0x3D), Color.rgb(0xFF, 0x3D, 0x6E),
    )

    /** One of eight bright accent colors of the current theme; any [index] maps to one of them. */
    @ColorInt
    fun accent(index: Int): Int {
        val colors = if (GBApplication.isNeonThemeEnabled()) NEON_ACCENTS else AURORA_ACCENTS
        return colors[Math.floorMod(index, colors.size)]
    }

    @get:ColorInt
    val levelGood: Int get() = pick(Color.rgb(0x34, 0xD3, 0x99), Color.rgb(0xC6, 0xFF, 0x3D))

    @get:ColorInt
    val levelFair: Int get() = pick(Color.rgb(0xFB, 0xBF, 0x24), Color.rgb(0xFF, 0xB0, 0x3D))

    @get:ColorInt
    val levelAttention: Int get() = pick(Color.rgb(0xFB, 0x71, 0x85), Color.rgb(0xFF, 0x3D, 0x6E))

    /** The color that marks a health [level]: green, amber, rose, or grey when there is no data. */
    @ColorInt
    fun level(level: Level): Int = when (level) {
        Level.GOOD -> levelGood
        Level.FAIR -> levelFair
        Level.ATTENTION -> levelAttention
        Level.NONE -> worn
    }

    /** The color of heart rate [zone]: 0 is resting, 1 to 5 are the zones up to the maximum. */
    @ColorInt
    fun hrZone(zone: Int): Int = pick(AURORA_ZONES[zone.coerceIn(0, 5)], NEON_ZONES[zone.coerceIn(0, 5)])
}
