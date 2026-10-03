package com.whyun.witv.ui;

import com.whyun.witv.player.PlayerManager;

/**
 * Host for {@link SettingsCollapsibleFragment}: playback page vs standalone settings.
 */
public interface SettingsPanelHost {

    boolean shouldShowStreamSwitchGroup();

    /** 为 true 时显示「超时换源」菜单（全局偏好，任意设置入口均可展示）。 */
    boolean shouldShowSourceTimeoutGroup();

    /** 为 true 时在「帮助与说明」子菜单中显示「媒体信息」入口（仅播放页）。 */
    boolean shouldShowPlaybackMediaInfoHelp();

    PlayerManager getPlayerManagerOrNull();

    /** Switch current channel play URL by index (same order as {@link com.whyun.witv.data.repository.ChannelRepository#getChannelSources}). */
    void onManualStreamSwitch(int index);

    /** Called when load-speed overlay preference changes (PlayerActivity refreshes overlay). */
    default void onPlaybackOverlayPreferenceChanged() {
    }

    /**
     * 解码方式变更后由播放页重建播放器。RenderersFactory 只能在 ExoPlayer 构建时指定，
     * 改不了，所以必须重建并重新挂载播放页自己的监听器。
     *
     * @return 是否已重建并续播（未在播放页或尚未初始化时为 false）
     */
    default boolean onPlaybackDecoderModeChanged() {
        return false;
    }

    /** 超时换源时长变更后由播放页刷新 PlayerManager 计时。 */
    default void onSourceSwitchTimeoutChanged() {
    }

    /** 激活新的 M3U 源后，播放页可立即重载频道列表与当前播放。 */
    default void onActiveM3USourceChanged(long sourceId) {
    }

    /** 展示当前播放的媒体与 EPG 等信息（仅 {@link PlayerActivity} 实现）。 */
    default void showPlaybackMediaInfoDialog() {
    }
}
