package com.whyun.witv.player;

import android.content.Context;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ForwardingRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.List;

/**
 * 包住 {@code MediaCodecVideoRenderer}：码流只是 <b>Level</b> 标得比硬解声明的高时，仍然报告「支持」，
 * 让硬解在轨道选择里胜过 FFmpeg 软解。
 *
 * <p>实测：CCTV-4K 组播标的是 {@code hvc1.2.4.L180}（HEVC Main10 Level 6.0），而 S905X 的
 * {@code OMX.amlogic.hevc.decoder.awesome} 只声明到 Level 5.1。硬解渲染器于是报
 * {@code FORMAT_EXCEEDS_CAPABILITIES}，FFmpeg 渲染器报 {@code FORMAT_HANDLED}，轨道选择取支持度更高的，
 * 视频就落到了 CPU 软解 4K 10bit 上，持续大量丢帧。可同一个台在「仅硬解」下播放正常——
 * 编码器的 Level 往往标得保守，实际码率、分辨率仍在硬解能力内。
 *
 * <p>只放宽 Level：mime、Profile 必须被某个硬件解码器声明支持，去掉 codecs 串后的分辨率/帧率也必须在它的
 * 能力内；Profile 不支持（比如解码器根本不支持 10bit）等真正解不了的情况照旧交给 FFmpeg 补位。
 */
@OptIn(markerClass = UnstableApi.class)
final class LevelTolerantVideoRenderer extends ForwardingRenderer {

    private static final String TAG = "LevelTolerantVideo";

    private final RendererCapabilities capabilities;

    LevelTolerantVideoRenderer(@NonNull Renderer renderer, @NonNull Context context,
                               @NonNull MediaCodecSelector mediaCodecSelector) {
        super(renderer);
        capabilities = new Capabilities(renderer.getCapabilities(), context, mediaCodecSelector);
    }

    @NonNull
    @Override
    public RendererCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * 硬解只是因为 Level 不够才被判为超出能力时返回 true。
     */
    @VisibleForTesting
    static boolean exceedsOnlyByLevel(@NonNull Context context,
                                      @NonNull MediaCodecSelector mediaCodecSelector,
                                      @NonNull Format format)
            throws MediaCodecUtil.DecoderQueryException {
        String mimeType = format.sampleMimeType;
        if (mimeType == null || format.codecs == null || format.drmInitData != null) {
            return false;
        }
        Pair<Integer, Integer> profileAndLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
        if (profileAndLevel == null) {
            return false;
        }
        int profile = profileAndLevel.first;
        // 去掉 codecs 串后，isFormatSupported 就不再检查 Profile/Level，只看 mime 和分辨率/帧率
        Format withoutCodecs = format.buildUpon().setCodecs(null).build();
        List<MediaCodecInfo> decoderInfos =
                mediaCodecSelector.getDecoderInfos(mimeType, false, false);
        for (MediaCodecInfo info : decoderInfos) {
            if (info.hardwareAccelerated
                    && supportsProfile(info, profile)
                    && info.isFormatSupported(context, withoutCodecs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsProfile(MediaCodecInfo info, int profile) {
        for (CodecProfileLevel profileLevel : info.getProfileLevels()) {
            if (profileLevel.profile == profile) {
                return true;
            }
        }
        return false;
    }

    private static final class Capabilities implements RendererCapabilities {

        private final RendererCapabilities delegate;
        private final Context context;
        private final MediaCodecSelector mediaCodecSelector;

        Capabilities(RendererCapabilities delegate, Context context,
                     MediaCodecSelector mediaCodecSelector) {
            this.delegate = delegate;
            this.context = context;
            this.mediaCodecSelector = mediaCodecSelector;
        }

        @NonNull
        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public int getTrackType() {
            return delegate.getTrackType();
        }

        @Override
        public int supportsFormat(@NonNull Format format) throws ExoPlaybackException {
            int support = delegate.supportsFormat(format);
            if (RendererCapabilities.getFormatSupport(support) != C.FORMAT_EXCEEDS_CAPABILITIES) {
                return support;
            }
            try {
                if (!exceedsOnlyByLevel(context, mediaCodecSelector, format)) {
                    return support;
                }
            } catch (MediaCodecUtil.DecoderQueryException e) {
                Log.w(TAG, "Decoder query failed, keep original support", e);
                return support;
            }
            Log.i(TAG, "Only codec level exceeds hardware decoder, treat as handled: "
                    + format.codecs);
            return (support & ~FORMAT_SUPPORT_MASK) | C.FORMAT_HANDLED;
        }

        @Override
        public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
            return delegate.supportsMixedMimeTypeAdaptation();
        }

        @Override
        public void setListener(@NonNull Listener listener) {
            delegate.setListener(listener);
        }

        @Override
        public void clearListener() {
            delegate.clearListener();
        }
    }
}
