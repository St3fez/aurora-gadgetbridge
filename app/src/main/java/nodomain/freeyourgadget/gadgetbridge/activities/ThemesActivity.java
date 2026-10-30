package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;

/**
 * Lets the user pick the look of the whole app: Aurora or Neon Pulse.
 */
public class ThemesActivity extends AbstractGBActivity {
    private MaterialCardView auroraCard;
    private MaterialCardView neonCard;
    private View auroraActive;
    private View neonActive;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_themes);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        auroraCard = findViewById(R.id.theme_card_aurora);
        neonCard = findViewById(R.id.theme_card_neon);
        auroraActive = findViewById(R.id.theme_active_aurora);
        neonActive = findViewById(R.id.theme_active_neon);

        auroraCard.setOnClickListener(v -> select(GBApplication.THEME_STYLE_AURORA));
        neonCard.setOnClickListener(v -> select(GBApplication.THEME_STYLE_NEON));

        showSelection();
    }

    /**
     * Saves the choice and tells every screen to redraw. This screen redraws too, which shows the new look.
     */
    private void select(final String style) {
        if (style.equals(GBApplication.getThemeStyle())) {
            return;
        }
        GBApplication.getPrefs().getPreferences().edit().putString(GBApplication.PREF_THEME_STYLE, style).apply();
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(GBApplication.ACTION_THEME_CHANGE));
    }

    private void showSelection() {
        final boolean neon = GBApplication.isNeonThemeEnabled();
        final int selectedBorder = MaterialColors.getColor(auroraCard, R.attr.subtab_active);
        final int idleBorder = MaterialColors.getColor(auroraCard, R.attr.stat_tile_border);
        final int selectedWidth = Math.round(3 * getResources().getDisplayMetrics().density);
        final int idleWidth = Math.round(getResources().getDisplayMetrics().density);

        auroraCard.setStrokeColor(neon ? idleBorder : selectedBorder);
        auroraCard.setStrokeWidth(neon ? idleWidth : selectedWidth);
        neonCard.setStrokeColor(neon ? selectedBorder : idleBorder);
        neonCard.setStrokeWidth(neon ? selectedWidth : idleWidth);
        auroraActive.setVisibility(neon ? View.GONE : View.VISIBLE);
        neonActive.setVisibility(neon ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
