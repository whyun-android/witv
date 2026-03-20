package com.whyun.witv.ui;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.Locale;

/**
 * Builds a short multi-line summary of current A/V stream properties for the EPG overlay.
 */
final class MediaInfoFormatter {

    private MediaInfoFormatter() {
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

    static String formatBitrate(int bitrate, String notProvidedLabel) {
        if (bitrate == Format.NO_VALUE || bitrate <= 0) {
            return notProvidedLabel;
        }
        if (bitrate >= 1_000_000) {
            return String.format(Locale.US, "%.2f Mbps", bitrate / 1_000_000.0);
        }
        if (bitrate >= 1_000) {
            return String.format(Locale.US, "%d kbps", bitrate / 1000);
        }
        return bitrate + " bps";
    }

    static String formatFrameRate(float frameRate, String notProvidedLabel) {
        if (frameRate == Format.NO_VALUE || frameRate <= 0) {
            return notProvidedLabel;
        }
        if (Math.abs(frameRate - Math.rint(frameRate)) < 0.01f) {
            return String.format(Locale.US, "%d fps", Math.round(frameRate));
        }
        return String.format(Locale.US, "%.2f fps", frameRate);
    }

    static String videoCodecLabel(Format f) {
        if (f == null) {
            return "—";
        }
        if (f.codecs != null && !f.codecs.isEmpty()) {
            return f.codecs;
        }
        return mimeToShortName(f.sampleMimeType);
    }

    static String audioCodecLabel(Format f) {
        if (f == null) {
            return "—";
        }
        if (f.codecs != null && !f.codecs.isEmpty()) {
            return f.codecs;
        }
        return mimeToShortName(f.sampleMimeType);
    }

    private static String mimeToShortName(String mime) {
        if (mime == null || mime.isEmpty()) {
            return "—";
        }
        if (MimeTypes.VIDEO_H264.equals(mime) || mime.contains("avc")) {
            return "H.264/AVC";
        }
        if (MimeTypes.VIDEO_H265.equals(mime) || mime.contains("hevc")) {
            return "H.265/HEVC";
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
            return "MPEG-2";
        }
        if (MimeTypes.AUDIO_AAC.equals(mime) || mime.contains("aac")) {
            return "AAC";
        }
        if (MimeTypes.AUDIO_MPEG.equals(mime) || mime.contains("mp3")) {
            return "MP3";
        }
        if (MimeTypes.AUDIO_AC3.equals(mime) || MimeTypes.AUDIO_E_AC3.equals(mime)) {
            return "AC-3";
        }
        if (MimeTypes.AUDIO_RAW.equals(mime) || mime.contains("pcm")) {
            return "PCM";
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
     * Left column: video (resolution, codec, bitrate, frame rate).
     * Right column: audio (codec, bitrate, sample rate, channels).
     *
     * @param notProvidedLabel 清单/轨道未声明码率、帧率等时的展示文案（如「未提供」）
     */
    static MediaInfoColumns buildTwoColumns(ExoPlayer player,
                                            String labelResolution,
                                            String labelVideoCodec,
                                            String labelVideoBitrate,
                                            String labelFrameRate,
                                            String labelAudioCodec,
                                            String labelAudioBitrate,
                                            String labelSampleRate,
                                            String labelChannels,
                                            String labelWaiting,
                                            String notProvidedLabel) {
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
        videoSb.append(labelResolution).append('：');
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

        videoSb.append(labelVideoCodec).append('：').append(videoCodecLabel(vf)).append('\n');

        int vBitrate = vf != null ? vf.bitrate : Format.NO_VALUE;
        videoSb.append(labelVideoBitrate).append('：')
                .append(formatBitrate(vBitrate, notProvidedLabel)).append('\n');

        float fr = vf != null ? vf.frameRate : Format.NO_VALUE;
        videoSb.append(labelFrameRate).append('：')
                .append(formatFrameRate(fr, notProvidedLabel));

        StringBuilder audioSb = new StringBuilder();
        audioSb.append(labelAudioCodec).append('：').append(audioCodecLabel(af)).append('\n');

        int aBitrate = af != null ? af.bitrate : Format.NO_VALUE;
        audioSb.append(labelAudioBitrate).append('：')
                .append(formatBitrate(aBitrate, notProvidedLabel)).append('\n');

        audioSb.append(labelSampleRate).append('：');
        if (af != null && af.sampleRate != Format.NO_VALUE && af.sampleRate > 0) {
            audioSb.append(af.sampleRate).append(" Hz");
        } else {
            audioSb.append('—');
        }
        audioSb.append('\n');

        audioSb.append(labelChannels).append('：');
        if (af != null && af.channelCount != Format.NO_VALUE && af.channelCount > 0) {
            audioSb.append(af.channelCount);
        } else {
            audioSb.append('—');
        }

        return new MediaInfoColumns(videoSb.toString(), audioSb.toString());
    }
}
