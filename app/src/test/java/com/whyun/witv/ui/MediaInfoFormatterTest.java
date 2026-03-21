package com.whyun.witv.ui;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaInfoFormatterTest {

    @Test
    public void videoCodecLabel_avc1High41_isFriendlyChinese() {
        Format f = new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setCodecs("avc1.640029")
                .build();
        assertEquals("H.264 · 高级档次 · 4.1 等级", MediaInfoFormatter.videoCodecLabel(f));
    }

    @Test
    public void videoCodecLabel_avc1Baseline30() {
        Format f = new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setCodecs("avc1.42E01E")
                .build();
        assertEquals("H.264 · 基准档次 · 3.0 等级", MediaInfoFormatter.videoCodecLabel(f));
    }

    @Test
    public void audioCodecLabel_mp4a40_2_isAacLc() {
        Format f = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setCodecs("mp4a.40.2")
                .build();
        assertEquals("AAC（常用，LC）", MediaInfoFormatter.audioCodecLabel(f));
    }
}
