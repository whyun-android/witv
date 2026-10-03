package com.whyun.witv.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;

/**
 * 解码方式：硬解（MediaCodec）与 FFmpeg 软解（NextLib）之间的优先级。
 *
 * <p>IPTV 直播里硬解覆盖不全是常态——组播 TS 常见的 MPEG-2 视频、MP2/AC3 音频，很多电视盒子
 * 的 MediaCodec 要么不支持，要么声称支持但解出来黑屏/无声。带上 FFmpeg 软解后：
 *
 * <ul>
 *   <li>{@link #AUTO}：硬解优先，硬解不支持该编码时自动用软解补位。默认，绝大多数情况选它。</li>
 *   <li>{@link #PREFER_SOFTWARE}：软解优先。用于「硬解声称支持但实际解不出来」的问题盒子。</li>
 *   <li>{@link #HARDWARE_ONLY}：完全不加载 FFmpeg 渲染器，行为与未引入软解时一致。
 *       用于排查软解本身引入的问题，或在极低端 CPU 上避免误用软解。</li>
 * </ul>
 */
@OptIn(markerClass = UnstableApi.class)
public enum PlaybackDecoderMode {

    AUTO("auto", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    PREFER_SOFTWARE("prefer_software", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
    HARDWARE_ONLY("hardware_only", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

    public static final PlaybackDecoderMode DEFAULT = AUTO;

    private final String id;
    private final int extensionRendererMode;

    PlaybackDecoderMode(String id, int extensionRendererMode) {
        this.id = id;
        this.extensionRendererMode = extensionRendererMode;
    }

    /** 持久化用的稳定标识，不要随枚举改名而变。 */
    @NonNull
    public String getId() {
        return id;
    }

    /** 对应的 {@code DefaultRenderersFactory.EXTENSION_RENDERER_MODE_*}。 */
    public int getExtensionRendererMode() {
        return extensionRendererMode;
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
