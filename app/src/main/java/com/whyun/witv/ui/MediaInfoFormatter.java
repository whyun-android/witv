package com.whyun.witv.ui;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a short multi-line summary of current A/V stream properties for the EPG overlay.
 */
final class MediaInfoFormatter {

    private static final Pattern AVC1_CODECS = Pattern.compile(
            "(?i)avc1\\.([0-9a-f]{6})");
    /** 如 hvc1.1.6.L120.90 / hev1.x.x.x.x */
    private static final Pattern HEV1_CODECS = Pattern.compile("(?i)(hvc1|hev1)\\.");

    /**
     * 编码描述是否输出中文。描述在多处静态拼接、拿不到 Context，
     * 由 PlayerActivity 在 onCreate 中按当前界面语言设置（切换语言会重建 Activity）。
     */
    private static volatile boolean chinese = true;

    private MediaInfoFormatter() {
    }

    static void setChinese(boolean value) {
        chinese = value;
    }

    private static String t(String zh, String en) {
        return chinese ? zh : en;
    }

    /** 标签与值之间的分隔符：中文全角冒号，英文半角冒号加空格 */
    private static String sep() {
        return chinese ? "：" : ": ";
    }

    static Format[] getSelectedVideoAudioFormats(ExoPlayer player) {
        Format video = null;
        Format audio = null;
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            @C.TrackType int type = group.getType();
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    Format f = group.getTrackFormat(i);
                    if (type == C.TRACK_TYPE_VIDEO && video == null) {
                        video = f;
                    } else if (type == C.TRACK_TYPE_AUDIO && audio == null) {
                        audio = f;
                    }
                }
            }
        }
        return new Format[]{video, audio};
    }

    static String videoCodecLabel(Format f) {
        if (f == null) {
            return "—";
        }
        String friendly = friendlyVideoCodecsString(f.codecs);
        if (friendly != null) {
            return friendly;
        }
        return mimeToShortName(f.sampleMimeType, true);
    }

    static String audioCodecLabel(Format f) {
        if (f == null) {
            return "—";
        }
        String friendly = friendlyAudioCodecsString(f.codecs, f.sampleMimeType);
        if (friendly != null) {
            return friendly;
        }
        return mimeToShortName(f.sampleMimeType, false);
    }

    /**
     * 将 avc1.xxxxxx（RFC 6381）解析为通俗中文，如 H.264 · 高级档次 · 4.1 级。
     */
    private static String friendlyVideoCodecsString(String codecs) {
        if (codecs == null || codecs.isEmpty()) {
            return null;
        }
        String trimmed = codecs.trim();
        Matcher m = AVC1_CODECS.matcher(trimmed);
        if (m.find()) {
            return parseAvc1HexToLabel(m.group(1));
        }
        if (HEV1_CODECS.matcher(trimmed).find()) {
            return "H.265 / HEVC";
        }
        if (trimmed.regionMatches(true, 0, "vp09", 0, 4)) {
            return "VP9";
        }
        if (trimmed.regionMatches(true, 0, "vp08", 0, 4)) {
            return "VP8";
        }
        if (trimmed.regionMatches(true, 0, "av01", 0, 4)) {
            return "AV1";
        }
        if (trimmed.regionMatches(true, 0, "mp4v", 0, 4)) {
            return t("MPEG-4 Part 2 视频", "MPEG-4 Part 2 Video");
        }
        return null;
    }

    private static String parseAvc1HexToLabel(String sixHex) {
        if (sixHex == null || sixHex.length() != 6) {
            return "H.264";
        }
        int profileIdc;
        int levelIdc;
        try {
            profileIdc = Integer.parseInt(sixHex.substring(0, 2), 16);
            // constraint byte ignored for display
            levelIdc = Integer.parseInt(sixHex.substring(4, 6), 16);
        } catch (NumberFormatException e) {
            return "H.264";
        }
        String profile = h264ProfileName(profileIdc);
        String level = h264LevelName(levelIdc);
        if (level != null) {
            return "H.264 · " + profile + " · " + level;
        }
        return "H.264 · " + profile;
    }

    private static String h264ProfileName(int profileIdc) {
        switch (profileIdc) {
            case 66:
                return t("基准档次", "Baseline");
            case 77:
                return t("主要档次", "Main");
            case 88:
                return t("扩展档次", "Extended");
            case 100:
                return t("高级档次", "High");
            case 110:
                return t("高级 10 位", "High 10");
            case 122:
                return t("高级 4:2:2", "High 4:2:2");
            case 144:
                return t("高级 4:4:4", "High 4:4:4");
            case 244:
                return t("高级 4:4:4（预测）", "High 4:4:4 Predictive");
            default:
                return t("档次 ", "Profile ") + profileIdc;
        }
    }

    /** level_idc 如 31→3.1、41→4.1 */
    private static String h264LevelName(int levelIdc) {
        if (levelIdc <= 0) {
            return null;
        }
        // 常见 1b 等为特例，IPTV 少见，略过
        int major = levelIdc / 10;
        int minor = levelIdc % 10;
        if (major <= 0) {
            return null;
        }
        return chinese ? major + "." + minor + " 等级" : "Level " + major + "." + minor;
    }

    private static String friendlyAudioCodecsString(String codecs, String mime) {
        if (codecs == null || codecs.isEmpty()) {
            return null;
        }
        String lower = codecs.toLowerCase(Locale.US);
        if (lower.startsWith("mp4a.")) {
            String[] parts = lower.split("\\.");
            if (parts.length >= 3 && "40".equals(parts[1])) {
                try {
                    // RFC 6381 中常见为十进制 object type（如 2 = AAC-LC）
                    int obj = Integer.parseInt(parts[2], 10);
                    switch (obj) {
                        case 1:
                            return t("AAC（主档次）", "AAC Main");
                        case 2:
                            return t("AAC（常用，LC）", "AAC-LC");
                        case 3:
                            return t("AAC（SSR）", "AAC SSR");
                        case 4:
                            return t("AAC（LTP）", "AAC LTP");
                        case 5:
                            return t("AAC（HE / 高效）", "HE-AAC");
                        case 6:
                            return t("AAC（可扩展）", "AAC Scalable");
                        case 17:
                            return t("AAC（低延迟）", "AAC-LD");
                        case 32:
                            return t("MPEG-4 音频", "MPEG-4 Audio");
                        default:
                            return "AAC";
                    }
                } catch (NumberFormatException ignored) {
                    return "AAC";
                }
            }
            return "AAC";
        }
        if (lower.contains("ac-3") || lower.contains("ac3")) {
            return t("杜比数字（AC-3）", "Dolby Digital (AC-3)");
        }
        if (lower.contains("ec-3") || lower.contains("eac3")) {
            return t("杜比数字 Plus（E-AC-3）", "Dolby Digital Plus (E-AC-3)");
        }
        if (lower.contains("opus")) {
            return "Opus";
        }
        if (lower.contains("flac")) {
            return "FLAC";
        }
        if (lower.contains("vorbis")) {
            return "Vorbis";
        }
        if (MimeTypes.AUDIO_MPEG.equals(mime) || lower.contains("mp3") || lower.contains("mp4a.6b")) {
            return "MP3";
        }
        return null;
    }

    private static String mimeToShortName(String mime, boolean video) {
        if (mime == null || mime.isEmpty()) {
            return "—";
        }
        if (MimeTypes.VIDEO_H264.equals(mime) || mime.contains("avc")) {
            return "H.264";
        }
        if (MimeTypes.VIDEO_H265.equals(mime) || mime.contains("hevc")) {
            return "H.265 / HEVC";
        }
        if (MimeTypes.VIDEO_AV1.equals(mime)) {
            return "AV1";
        }
        if (MimeTypes.VIDEO_VP9.equals(mime)) {
            return "VP9";
        }
        if (MimeTypes.VIDEO_VP8.equals(mime)) {
            return "VP8";
        }
        if (MimeTypes.VIDEO_MPEG2.equals(mime)) {
            return t("MPEG-2 视频", "MPEG-2 Video");
        }
        if (!video) {
            if (MimeTypes.AUDIO_AAC.equals(mime) || mime.contains("aac")) {
                return "AAC";
            }
            if (MimeTypes.AUDIO_MPEG.equals(mime) || mime.contains("mp3")) {
                return "MP3";
            }
            if (MimeTypes.AUDIO_AC3.equals(mime) || MimeTypes.AUDIO_E_AC3.equals(mime)) {
                return t("杜比数字", "Dolby Digital");
            }
            if (MimeTypes.AUDIO_RAW.equals(mime) || mime.contains("pcm")) {
                return t("PCM 未压缩", "PCM (uncompressed)");
            }
        }
        int slash = mime.indexOf('/');
        return slash >= 0 ? mime.substring(slash + 1) : mime;
    }

    static final class MediaInfoColumns {
        final String videoColumn;
        final String audioColumn;

        MediaInfoColumns(String videoColumn, String audioColumn) {
            this.videoColumn = videoColumn;
            this.audioColumn = audioColumn;
        }
    }

    /**
     * Left column: video (resolution, codec).
     * Right column: audio (codec, sample rate, channels).
     */
    static MediaInfoColumns buildTwoColumns(ExoPlayer player,
                                            String labelResolution,
                                            String labelVideoCodec,
                                            String labelAudioCodec,
                                            String labelSampleRate,
                                            String labelChannels,
                                            String labelWaiting) {
        if (player == null) {
            return new MediaInfoColumns("", "");
        }
        int state = player.getPlaybackState();
        if (state == androidx.media3.common.Player.STATE_IDLE
                || state == androidx.media3.common.Player.STATE_ENDED) {
            return new MediaInfoColumns(labelWaiting, labelWaiting);
        }

        VideoSize vs = player.getVideoSize();
        Format[] va = getSelectedVideoAudioFormats(player);
        Format vf = va[0];
        Format af = va[1];

        StringBuilder videoSb = new StringBuilder();

        int vw = vs.width;
        int vh = vs.height;
        videoSb.append(labelResolution).append(sep());
        if (vw > 0 && vh > 0) {
            videoSb.append(vw).append('×').append(vh);
            if (vs.pixelWidthHeightRatio != 1.0f
                    && vs.pixelWidthHeightRatio > 0
                    && Math.abs(vs.pixelWidthHeightRatio - 1f) > 0.02f) {
                videoSb.append(" (").append(String.format(Locale.US, "%.2f:1", vs.pixelWidthHeightRatio)).append(')');
            }
        } else if (vf != null && vf.width > 0 && vf.height > 0) {
            videoSb.append(vf.width).append('×').append(vf.height);
        } else {
            videoSb.append('—');
        }
        videoSb.append('\n');

        videoSb.append(labelVideoCodec).append(sep()).append(videoCodecLabel(vf));

        StringBuilder audioSb = new StringBuilder();
        audioSb.append(labelAudioCodec).append(sep()).append(audioCodecLabel(af)).append('\n');

        audioSb.append(labelSampleRate).append(sep());
        if (af != null && af.sampleRate != Format.NO_VALUE && af.sampleRate > 0) {
            audioSb.append(af.sampleRate).append(" Hz");
        } else {
            audioSb.append('—');
        }
        audioSb.append('\n');

        audioSb.append(labelChannels).append(sep());
        if (af != null && af.channelCount != Format.NO_VALUE && af.channelCount > 0) {
            audioSb.append(af.channelCount);
        } else {
            audioSb.append('—');
        }

        return new MediaInfoColumns(videoSb.toString(), audioSb.toString());
    }
}
