package com.whyun.witv.data;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {

    private static final String PREF_NAME = "witv_prefs";
    private static final String KEY_LAST_CHANNEL_ID = "last_channel_id";
    private static final String KEY_LAST_SOURCE_ID = "last_source_id";
    /** 上次成功开播时，在该频道多线路中的索引（0-based），未成功开播过则为 -1 */
    private static final String KEY_LAST_PLAY_STREAM_INDEX = "last_play_stream_index";
    private static final String KEY_AUTO_PLAY_LAST = "auto_play_last";
    private static final String KEY_SHOW_LOAD_SPEED_OVERLAY = "show_load_speed_overlay";
    private static final String KEY_REVERSE_CHANNEL_KEYS = "reverse_channel_keys";
    private static final String KEY_SOURCE_SWITCH_TIMEOUT_MS = "source_switch_timeout_ms";

    /** 单线路超时未起播则换源；可选值见 {@link #normalizeSourceSwitchTimeoutMs(long)} */
    public static final long DEFAULT_SOURCE_SWITCH_TIMEOUT_MS = 15_000L;
    private static final long[] ALLOWED_SOURCE_TIMEOUTS_MS = {
            5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L
    };
    private static final String KEY_LAST_EPG_AUTO_REFRESH_AT = "last_epg_auto_refresh_at";
    private static final String KEY_LAST_EPG_AUTO_REFRESH_URL = "last_epg_auto_refresh_url";

    /** 首页后台 EPG 自动刷新的最小间隔 */
    public static final long EPG_AUTO_REFRESH_INTERVAL_MS = 60 * 60 * 1000L;

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveLastChannel(long channelId, long sourceId) {
        long oldChannelId = getLastChannelId();
        long oldSourceId = getLastSourceId();
        SharedPreferences.Editor ed = prefs.edit()
                .putLong(KEY_LAST_CHANNEL_ID, channelId)
                .putLong(KEY_LAST_SOURCE_ID, sourceId);
        if (oldChannelId != channelId || oldSourceId != sourceId) {
            ed.remove(KEY_LAST_PLAY_STREAM_INDEX);
        }
        ed.apply();
    }

    /** 某频道成功起播后记录当前使用的线路索引（与 {@link #saveLastChannel} 搭配，先换频道会清掉旧索引） */
    public void saveLastPlayStreamIndex(int streamIndex) {
        prefs.edit().putInt(KEY_LAST_PLAY_STREAM_INDEX, streamIndex).apply();
    }

    /** @return 上次成功开播的线路索引，未知为 -1 */
    public int getLastPlayStreamIndex() {
        return prefs.getInt(KEY_LAST_PLAY_STREAM_INDEX, -1);
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

    /** 是否在播放画面右上角显示视频加载速度（带宽估算） */
    public void setShowLoadSpeedOverlay(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHOW_LOAD_SPEED_OVERLAY, enabled).apply();
    }

    public boolean isShowLoadSpeedOverlay() {
        return prefs.getBoolean(KEY_SHOW_LOAD_SPEED_OVERLAY, false);
    }

    /** 上/下键换台方向与默认相反 */
    public void setReverseChannelKeys(boolean enabled) {
        prefs.edit().putBoolean(KEY_REVERSE_CHANNEL_KEYS, enabled).apply();
    }

    public boolean isReverseChannelKeysEnabled() {
        return prefs.getBoolean(KEY_REVERSE_CHANNEL_KEYS, false);
    }

    public long getSourceSwitchTimeoutMs() {
        long v = prefs.getLong(KEY_SOURCE_SWITCH_TIMEOUT_MS, DEFAULT_SOURCE_SWITCH_TIMEOUT_MS);
        return normalizeSourceSwitchTimeoutMs(v);
    }

    public void setSourceSwitchTimeoutMs(long ms) {
        prefs.edit().putLong(KEY_SOURCE_SWITCH_TIMEOUT_MS, normalizeSourceSwitchTimeoutMs(ms)).apply();
    }

    public static long normalizeSourceSwitchTimeoutMs(long ms) {
        for (long a : ALLOWED_SOURCE_TIMEOUTS_MS) {
            if (a == ms) {
                return ms;
            }
        }
        return DEFAULT_SOURCE_SWITCH_TIMEOUT_MS;
    }

    public static int[] getAllowedSourceTimeoutSeconds() {
        return new int[]{5, 10, 15, 20, 25, 30};
    }

    public void clearLastChannel() {
        prefs.edit()
                .remove(KEY_LAST_CHANNEL_ID)
                .remove(KEY_LAST_SOURCE_ID)
                .remove(KEY_LAST_PLAY_STREAM_INDEX)
                .apply();
    }

    /**
     * 是否应在进入首页时后台拉取 EPG：URL 变更、从未成功刷新、或距上次成功已满 intervalMs。
     */
    public boolean shouldAutoRefreshEpg(String epgUrl, long intervalMs) {
        if (epgUrl == null || epgUrl.isEmpty()) {
            return false;
        }
        String lastUrl = prefs.getString(KEY_LAST_EPG_AUTO_REFRESH_URL, "");
        long lastAt = prefs.getLong(KEY_LAST_EPG_AUTO_REFRESH_AT, 0L);
        if (!epgUrl.equals(lastUrl)) {
            return true;
        }
        if (lastAt == 0L) {
            return true;
        }
        return System.currentTimeMillis() - lastAt >= intervalMs;
    }

    public void markEpgAutoRefreshSuccess(String epgUrl) {
        prefs.edit()
                .putString(KEY_LAST_EPG_AUTO_REFRESH_URL, epgUrl != null ? epgUrl : "")
                .putLong(KEY_LAST_EPG_AUTO_REFRESH_AT, System.currentTimeMillis())
                .apply();
    }
}
