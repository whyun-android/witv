package com.whyun.witv.data;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {

    private static final String PREF_NAME = "witv_prefs";
    private static final String KEY_LAST_CHANNEL_ID = "last_channel_id";
    private static final String KEY_LAST_SOURCE_ID = "last_source_id";
    private static final String KEY_AUTO_PLAY_LAST = "auto_play_last";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveLastChannel(long channelId, long sourceId) {
        prefs.edit()
                .putLong(KEY_LAST_CHANNEL_ID, channelId)
                .putLong(KEY_LAST_SOURCE_ID, sourceId)
                .apply();
    }

    public long getLastChannelId() {
        return prefs.getLong(KEY_LAST_CHANNEL_ID, -1);
    }

    public long getLastSourceId() {
        return prefs.getLong(KEY_LAST_SOURCE_ID, -1);
    }

    public boolean hasLastChannel() {
        return prefs.contains(KEY_LAST_CHANNEL_ID) && getLastChannelId() != -1;
    }

    public void setAutoPlayLast(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_PLAY_LAST, enabled).apply();
    }

    public boolean isAutoPlayLastEnabled() {
        return prefs.getBoolean(KEY_AUTO_PLAY_LAST, true);
    }

    public void clearLastChannel() {
        prefs.edit()
                .remove(KEY_LAST_CHANNEL_ID)
                .remove(KEY_LAST_SOURCE_ID)
                .apply();
    }
}
