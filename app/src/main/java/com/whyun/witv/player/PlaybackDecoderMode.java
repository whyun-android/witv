package com.whyun.witv.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;

/**
 * 解码方式：硬解（MediaCodec）与 FFmpeg 软解（NextLib）之间的优先级，音频与视频分别指定。
 *
 * <p>IPTV 直播里硬解覆盖不全是常态，而且音频和视频的最佳取舍常常相反：
 *
 * <ul>
 *   <li>{@link #AUTO}：音视频都硬解优先，硬解不支持时自动用软解补位。默认。</li>
 *   <li>{@link #SOFTWARE_AUDIO}：<b>音频软解、视频硬解</b>。盒子声称支持 AC-3/E-AC-3 直通时，
 *       {@code MediaCodecAudioRenderer} 会以直通方式胜出、压根不解码，HDMI 下游解不了就完全没声音；
 *       强制音频走 FFmpeg 可绕开直通，同时视频仍用硬解，不会因软解跟不上实时而卡顿。</li>
 *   <li>{@link #PREFER_SOFTWARE}：音视频都软解优先。用于硬解声称支持但实际解不出来的设备，
 *       CPU 占用高，低端盒子上 1080p 视频可能跟不上。</li>
 *   <li>{@link #HARDWARE_ONLY}：完全不加载 FFmpeg 渲染器。用于排查软解自身引入的问题。</li>
 * </ul>
 *
 * <p>音视频分别取值由 {@link WiTVRenderersFactory} 落实——{@code DefaultRenderersFactory}
 * 本身只有一个全局的 {@code extensionRendererMode}。
 */
@OptIn(markerClass = UnstableApi.class)
public enum PlaybackDecoderMode {

    AUTO("auto",
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    SOFTWARE_AUDIO("software_audio",
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    PREFER_SOFTWARE("prefer_software",
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
    HARDWARE_ONLY("hardware_only",
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

    public static final PlaybackDecoderMode DEFAULT = AUTO;

    private final String id;
    private final int audioExtensionRendererMode;
    private final int videoExtensionRendererMode;

    PlaybackDecoderMode(String id, int audioExtensionRendererMode,
                        int videoExtensionRendererMode) {
        this.id = id;
        this.audioExtensionRendererMode = audioExtensionRendererMode;
        this.videoExtensionRendererMode = videoExtensionRendererMode;
    }

    /** 持久化用的稳定标识，不要随枚举改名而变。 */
    @NonNull
    public String getId() {
        return id;
    }

    /** 音频渲染器对应的 {@code DefaultRenderersFactory.EXTENSION_RENDERER_MODE_*}。 */
    public int getAudioExtensionRendererMode() {
        return audioExtensionRendererMode;
    }

    /** 视频渲染器对应的 {@code DefaultRenderersFactory.EXTENSION_RENDERER_MODE_*}。 */
    public int getVideoExtensionRendererMode() {
        return videoExtensionRendererMode;
    }

    /** 未知或空标识一律回落到 {@link #DEFAULT}，避免老版本或脏数据导致播放不可用。 */
    @NonNull
    public static PlaybackDecoderMode fromId(@Nullable String id) {
        if (id != null) {
            for (PlaybackDecoderMode mode : values()) {
                if (mode.id.equals(id)) {
                    return mode;
                }
            }
        }
        return DEFAULT;
    }
}
