package com.whyun.witv.ui;

import com.whyun.witv.data.AppLanguage;
import com.whyun.witv.data.PreferenceManager;
import android.content.Context;
import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

import com.whyun.witv.R;
import com.whyun.witv.player.PlayerManager;

public class SettingsActivity extends FragmentActivity implements SettingsPanelHost {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(new PreferenceManager(this).getAppTheme().getStyleRes());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_fragment_container, new SettingsCollapsibleFragment())
                    .commit();
        }
    }

    @Override
    public boolean shouldShowStreamSwitchGroup() {
        return false;
    }

    @Override
    public boolean shouldShowSourceTimeoutGroup() {
        return true;
    }

    @Override
    public boolean shouldShowPlaybackMediaInfoHelp() {
        return false;
    }

    @Override
    public PlayerManager getPlayerManagerOrNull() {
        return null;
    }

    @Override
    public void onManualStreamSwitch(int index) {
    }

    @Override
    public void onPlaybackOverlayPreferenceChanged() {
    }
}
