package com.whyun.witv.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.MediaCodecInfoBuilder;

import java.util.Collections;

/**
 * 用实测的 CCTV-4K 码流参数（{@code hvc1.2.4.L180.90}，Main10 Level 6.0）对 S905X 那种
 * 「Main10 只声明到 Level 5.1」的硬解做判断。
 */
@RunWith(RobolectricTestRunner.class)
public class LevelTolerantVideoRendererTest {

    private static final String CCTV_4K_CODECS = "hvc1.2.4.L180.90";

    private static Format hevc4k(String codecs) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(codecs)
                .setWidth(3840)
                .setHeight(2160)
                .build();
    }

    private static MediaCodecSelector decoder(boolean hardware, int... profiles) {
        CodecProfileLevel[] profileLevels = new CodecProfileLevel[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            CodecProfileLevel profileLevel = new CodecProfileLevel();
            profileLevel.profile = profiles[i];
            profileLevel.level = CodecProfileLevel.HEVCMainTierLevel51;
            profileLevels[i] = profileLevel;
        }
        CodecCapabilities capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MimeTypes.VIDEO_H265, 3840, 2160))
                .setProfileLevels(profileLevels)
                .setColorFormats(new int[] {CodecCapabilities.COLOR_FormatYUV420Flexible})
                .build();
        String name = hardware ? "OMX.amlogic.hevc.decoder.awesome" : "OMX.google.hevc.decoder";
        MediaCodecInfo info = MediaCodecInfo.newInstance(name, MimeTypes.VIDEO_H265,
                MimeTypes.VIDEO_H265, capabilities, hardware, !hardware, hardware, false, false);
        return (mimeType, secure, tunneling) -> Collections.singletonList(info);
    }

    private static boolean exceedsOnlyByLevel(MediaCodecSelector selector, Format format)
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        return LevelTolerantVideoRenderer.exceedsOnlyByLevel(context, selector, format);
    }

    @Test
    public void hardwareMain10DecoderAcceptsLevelSixStream() throws Exception {
        MediaCodecSelector selector = decoder(true, CodecProfileLevel.HEVCProfileMain10);
        assertTrue(exceedsOnlyByLevel(selector, hevc4k(CCTV_4K_CODECS)));
    }

    /** 解码器不支持 10bit 是真解不了，必须照旧交给 FFmpeg。 */
    @Test
    public void unsupportedProfileIsNotRelaxed() throws Exception {
        MediaCodecSelector selector = decoder(true, CodecProfileLevel.HEVCProfileMain);
        assertFalse(exceedsOnlyByLevel(selector, hevc4k(CCTV_4K_CODECS)));
    }

    @Test
    public void softwareDecoderIsNotRelaxed() throws Exception {
        MediaCodecSelector selector = decoder(false, CodecProfileLevel.HEVCProfileMain10);
        assertFalse(exceedsOnlyByLevel(selector, hevc4k(CCTV_4K_CODECS)));
    }

    @Test
    public void formatWithoutCodecsStringIsNotRelaxed() throws Exception {
        MediaCodecSelector selector = decoder(true, CodecProfileLevel.HEVCProfileMain10);
        assertFalse(exceedsOnlyByLevel(selector, hevc4k(null)));
    }
}
