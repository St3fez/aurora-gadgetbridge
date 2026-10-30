package nodomain.freeyourgadget.gadgetbridge.band

import android.content.Context
import android.os.Build
import org.slf4j.LoggerFactory
import java.security.KeyStore

/**
 * Forgets what the gate button and the Gemini shortcut, both removed, left on the phone: the saved gate settings with
 * their encrypted secret and the key that encrypted it, and the Gemini switch. Runs once.
 */
object LegacyCleanup {
    private val LOG = LoggerFactory.getLogger(LegacyCleanup::class.java)

    private const val PREFS = "band_extras"
    private const val KEY_DONE = "gate_and_gemini_removed"
    private const val GATE_PREFS = "gate_config"
    private const val GATE_KEY_ALIAS = "aurora_gate_key"
    private const val KEY_GEMINI = "gemini_enabled"

    fun run(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(GATE_PREFS)
        } else {
            context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(GATE_KEY_ALIAS)
        } catch (e: Exception) {
            LOG.warn("Could not remove the gate key", e)
        }
        prefs.edit().remove(KEY_GEMINI).putBoolean(KEY_DONE, true).apply()
    }
}
