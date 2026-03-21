package com.whyun.witv.ui;

import com.whyun.witv.player.PlayerManager;

/**
 * Host for {@link SettingsCollapsibleFragment}: playback page vs standalone settings.
 */
public interface SettingsPanelHost {

    boolean shouldShowStreamSwitchGroup();

    PlayerManager getPlayerManagerOrNull();

    /** Switch current channel play URL by index (same order as {@link com.whyun.witv.data.repository.ChannelRepository#getChannelSources}). */
    void onManualStreamSwitch(int index);

    /** Called when load-speed overlay preference changes (PlayerActivity refreshes overlay). */
    default void onPlaybackOverlayPreferenceChanged() {
    }
}
