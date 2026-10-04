package com.whyun.witv.player;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

/**
 * 允许音频与视频各自选择硬解/软解优先级的渲染器工厂。
 *
 * <p>{@code DefaultRenderersFactory} 只有一个全局的 {@code extensionRendererMode}，音视频共用。
 * 但这两者的最佳取舍常常相反，实测遇到过的典型情况是：
 *
 * <ul>
 *   <li><b>音频需要软解</b>——盒子声称支持 AC-3/E-AC-3 直通，于是 {@code MediaCodecAudioRenderer}
 *       以直通方式胜出、压根不解码，而 HDMI 下游实际解不了，表现为杜比声道完全没声音；</li>
 *   <li><b>视频需要硬解</b>——老盒子的 CPU 软解 1080p H.264 跟不上实时，一旦让
 *       {@code FfmpegVideoRenderer} 抢先就会持续掉帧卡顿。</li>
 * </ul>
 *
 * <p>所以这里分别覆写两个 {@code build*Renderers}，各自传入
 * {@link PlaybackDecoderMode} 给出的模式，忽略框架传进来的那个全局值。
 */
@OptIn(markerClass = UnstableApi.class)
final class WiTVRenderersFactory extends NextRenderersFactory {

    private final PlaybackDecoderMode decoderMode;

    WiTVRenderersFactory(Context context, PlaybackDecoderMode decoderMode) {
        super(context);
        this.decoderMode = decoderMode;
        // 作为未覆写路径的基线；音视频的实际取值在下面两个方法里各自指定
        setExtensionRendererMode(decoderMode.getVideoExtensionRendererMode());
    }

    @Override
    protected void buildAudioRenderers(@NonNull Context context,
                                       int extensionRendererMode,
                                       @NonNull MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback,
                                       @NonNull AudioSink audioSink,
                                       @NonNull Handler eventHandler,
                                       @NonNull AudioRendererEventListener eventListener,
                                       @NonNull ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, decoderMode.getAudioExtensionRendererMode(),
                mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener,
                out);
    }

    @Override
    protected void buildVideoRenderers(@NonNull Context context,
                                       int extensionRendererMode,
                                       @NonNull MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback,
                                       @NonNull Handler eventHandler,
                                       @NonNull VideoRendererEventListener eventListener,
                                       long allowedVideoJoiningTimeMs,
                                       @NonNull ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, decoderMode.getVideoExtensionRendererMode(),
                mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
                allowedVideoJoiningTimeMs, out);
    }
}
