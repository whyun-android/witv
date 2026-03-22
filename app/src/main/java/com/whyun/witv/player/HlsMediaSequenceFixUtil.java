package com.whyun.witv.player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修正部分 IPTV 源错误的 media playlist：将误设为「最后一片序号」的
 * {@code #EXT-X-MEDIA-SEQUENCE} 改回「第一片序号」。仅在检测到典型错误模式时改写。
 */
public final class HlsMediaSequenceFixUtil {

    private static final Pattern MEDIA_SEQUENCE_LINE = Pattern.compile(
            "^#EXT-X-MEDIA-SEQUENCE:(\\d+)\\s*$");
    private static final Pattern KEY2_PARAM = Pattern.compile("[?&]key2=(\\d+)");
    private static final Pattern TS_SUFFIX = Pattern.compile("(\\d+)\\.ts$");

    private HlsMediaSequenceFixUtil() {
    }

    /**
     * 修正 playlist 文本（UTF-8 语义）；无需改写时返回原字符串引用。
     */
    public static String fixPlaylistIfNeeded(String playlist) {
        if (playlist == null || playlist.isEmpty() || !playlist.contains("#EXTM3U")) {
            return playlist;
        }
        String withVersionFix = playlist.replace("##EXT-X-VERSION:", "#EXT-X-VERSION:");
        if (!withVersionFix.contains("#EXT-X-MEDIA-SEQUENCE")) {
            return withVersionFix;
        }

        String[] lines = withVersionFix.split("\\r\\n|\\n|\\r", -1);
        int mediaSeqLineIndex = -1;
        long declaredM = -1L;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            Matcher m = MEDIA_SEQUENCE_LINE.matcher(trimmed);
            if (m.matches()) {
                mediaSeqLineIndex = i;
                declaredM = Long.parseLong(m.group(1));
                break;
            }
        }
        if (mediaSeqLineIndex < 0) {
            return withVersionFix;
        }

        List<Long> segmentIds = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().startsWith("#EXTINF")) {
                String next = lines[i + 1].trim();
                if (!next.isEmpty() && !next.startsWith("#")) {
                    Long id = extractSegmentId(next);
                    if (id != null) {
                        segmentIds.add(id);
                    }
                }
            }
        }

        if (segmentIds.size() < 2) {
            return withVersionFix;
        }

        long s0 = segmentIds.get(0);
        long sLast = segmentIds.get(segmentIds.size() - 1);
        for (int j = 0; j < segmentIds.size(); j++) {
            if (segmentIds.get(j) != s0 + j) {
                return withVersionFix;
            }
        }
        if (declaredM != sLast || declaredM == s0) {
            return withVersionFix;
        }

        lines[mediaSeqLineIndex] = "#EXT-X-MEDIA-SEQUENCE:" + s0;
        return joinLines(lines);
    }

    private static String joinLines(String[] lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static Long extractSegmentId(String uriLine) {
        Matcher key2 = KEY2_PARAM.matcher(uriLine);
        if (key2.find()) {
            return Long.parseLong(key2.group(1));
        }
        int q = uriLine.indexOf('?');
        String pathPart = q >= 0 ? uriLine.substring(0, q) : uriLine;
        Matcher ts = TS_SUFFIX.matcher(pathPart);
        if (ts.find()) {
            return Long.parseLong(ts.group(1));
        }
        return null;
    }
}
