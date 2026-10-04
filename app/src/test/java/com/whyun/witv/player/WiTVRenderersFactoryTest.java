package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染器顺序决定了 ExoPlayer 实际用谁解码：它选第一个 {@code supportsFormat} 通过的渲染器。
 *
 * <p>顺序由 NextLib 的插入逻辑决定（第三方代码），升级 NextLib 时可能变化，
 * 所以这里直接断言产出的顺序，而不是只断言传进去的模式值。
 */
@RunWith(RobolectricTestRunner.class)
public class WiTVRenderersFactoryTest {

    private static List<String> rendererNames(PlaybackDecoderMode mode, int trackType) {
        Context context = ApplicationProvider.getApplicationContext();
        Renderer[] renderers = new WiTVRenderersFactory(context, mode).createRenderers(
                new Handler(Looper.getMainLooper()),
                new VideoRendererEventListener() {
                },
                new AudioRendererEventListener() {
                },
                cues -> {
                },
                metadata -> {
                });
        List<String> names = new ArrayList<>();
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == trackType) {
                names.add(renderer.getClass().getSimpleName());
            }
        }
        return names;
    }

    private static int indexOf(List<String> names, String simpleName) {
        return names.indexOf(simpleName);
    }

    /**
     * 这次修复的核心断言：同一次播放里，音频让 FFmpeg 排在 MediaCodec **之前**
     * （绕开杜比直通），视频让 MediaCodec 排在 FFmpeg **之前**（避免软解 1080p 卡顿）。
     */
    @Test
    public void softwareAudioPutsFfmpegFirstForAudioButLastForVideo() {
        List<String> audio = rendererNames(PlaybackDecoderMode.SOFTWARE_AUDIO, C.TRACK_TYPE_AUDIO);
        List<String> video = rendererNames(PlaybackDecoderMode.SOFTWARE_AUDIO, C.TRACK_TYPE_VIDEO);

        int ffmpegAudio = indexOf(audio, "FfmpegAudioRenderer");
        int codecAudio = indexOf(audio, "MediaCodecAudioRenderer");
        assertTrue("音频渲染器缺失: " + audio, ffmpegAudio >= 0 && codecAudio >= 0);
        assertTrue("音频应当 FFmpeg 优先，实际: " + audio, ffmpegAudio < codecAudio);

        int ffmpegVideo = indexOf(video, "FfmpegVideoRenderer");
        int codecVideo = indexOf(video, "MediaCodecVideoRenderer");
        assertTrue("视频渲染器缺失: " + video, ffmpegVideo >= 0 && codecVideo >= 0);
        assertTrue("视频应当硬解优先，实际: " + video, codecVideo < ffmpegVideo);
    }

    @Test
    public void autoPutsHardwareFirstForBothTracks() {
        List<String> audio = rendererNames(PlaybackDecoderMode.AUTO, C.TRACK_TYPE_AUDIO);
        List<String> video = rendererNames(PlaybackDecoderMode.AUTO, C.TRACK_TYPE_VIDEO);

        assertTrue("音频应当硬解优先，实际: " + audio,
                indexOf(audio, "MediaCodecAudioRenderer") < indexOf(audio, "FfmpegAudioRenderer"));
        assertTrue("视频应当硬解优先，实际: " + video,
                indexOf(video, "MediaCodecVideoRenderer") < indexOf(video, "FfmpegVideoRenderer"));
    }

    @Test
    public void preferSoftwarePutsFfmpegFirstForBothTracks() {
        List<String> audio = rendererNames(PlaybackDecoderMode.PREFER_SOFTWARE, C.TRACK_TYPE_AUDIO);
        List<String> video = rendererNames(PlaybackDecoderMode.PREFER_SOFTWARE, C.TRACK_TYPE_VIDEO);

        assertTrue("音频应当软解优先，实际: " + audio,
                indexOf(audio, "FfmpegAudioRenderer") < indexOf(audio, "MediaCodecAudioRenderer"));
        assertTrue("视频应当软解优先，实际: " + video,
                indexOf(video, "FfmpegVideoRenderer") < indexOf(video, "MediaCodecVideoRenderer"));
    }

    @Test
    public void hardwareOnlyLoadsNoFfmpegRenderers() {
        List<String> audio = rendererNames(PlaybackDecoderMode.HARDWARE_ONLY, C.TRACK_TYPE_AUDIO);
        List<String> video = rendererNames(PlaybackDecoderMode.HARDWARE_ONLY, C.TRACK_TYPE_VIDEO);

        assertEquals("不应加载 FFmpeg 音频渲染器: " + audio, -1, indexOf(audio, "FfmpegAudioRenderer"));
        assertEquals("不应加载 FFmpeg 视频渲染器: " + video, -1, indexOf(video, "FfmpegVideoRenderer"));
        assertTrue(indexOf(audio, "MediaCodecAudioRenderer") >= 0);
        assertTrue(indexOf(video, "MediaCodecVideoRenderer") >= 0);
    }
}
