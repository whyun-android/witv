package com.whyun.witv.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 media playlist 规划需要预取的尾部 ts 分片。
 */
public final class HlsSegmentPrefetchPlanner {
    private static final Pattern MEDIA_SEQUENCE_LINE = Pattern.compile(
            "^#EXT-X-MEDIA-SEQUENCE:(\\d+)\\s*$");
    private static final Pattern EXTINF_LINE = Pattern.compile(
            "^#EXTINF:([0-9]+(?:\\.[0-9]+)?).*$");

    static final class SegmentInfo {
        @NonNull
        final Uri uri;
        final long durationMs;

        SegmentInfo(@NonNull Uri uri, long durationMs) {
            this.uri = uri;
            this.durationMs = durationMs;
        }
    }

    private HlsSegmentPrefetchPlanner() {
    }

    @NonNull
    public static List<Uri> planSegmentUris(@NonNull Uri playlistUri,
                                            @NonNull String playlistText,
                                            int maxSegments,
                                            int skipTailSegments) {
        if (maxSegments <= 0 || playlistText.isEmpty()) {
            return Collections.emptyList();
        }

        List<Uri> allSegments = extractSegmentUris(playlistUri, playlistText);
        return planSegmentUris(allSegments, maxSegments, skipTailSegments);
    }

    @NonNull
    static List<Uri> planSegmentUris(@NonNull List<Uri> allSegments,
                                     int maxSegments,
                                     int skipTailSegments) {
        if (allSegments.isEmpty()) {
            return Collections.emptyList();
        }

        int tailExclusive = Math.max(0, allSegments.size() - Math.max(0, skipTailSegments));
        if (tailExclusive <= 0) {
            return Collections.emptyList();
        }
        int fromIndex = Math.max(0, tailExclusive - maxSegments);
        if (fromIndex >= tailExclusive) {
            return Collections.emptyList();
        }
        return new ArrayList<>(allSegments.subList(fromIndex, tailExclusive));
    }

    @NonNull
    static List<Uri> extractSegmentUris(@NonNull Uri playlistUri, @NonNull String playlistText) {
        List<Uri> result = new ArrayList<>();
        for (SegmentInfo segmentInfo : extractSegmentInfos(playlistUri, playlistText)) {
            result.add(segmentInfo.uri);
        }
        return result;
    }

    @NonNull
    static NavigableMap<Long, Uri> extractSegmentUrisByMediaSequence(@NonNull Uri playlistUri,
                                                                     @NonNull String playlistText) {
        NavigableMap<Long, Uri> result = new TreeMap<>();
        for (java.util.Map.Entry<Long, SegmentInfo> entry
                : extractSegmentInfosByMediaSequence(playlistUri, playlistText).entrySet()) {
            result.put(entry.getKey(), entry.getValue().uri);
        }
        return result;
    }

    @NonNull
    static NavigableMap<Long, SegmentInfo> extractSegmentInfosByMediaSequence(@NonNull Uri playlistUri,
                                                                              @NonNull String playlistText) {
        Long mediaSequence = extractMediaSequence(playlistText);
        if (mediaSequence == null) {
            return new TreeMap<>();
        }
        NavigableMap<Long, SegmentInfo> result = new TreeMap<>();
        List<SegmentInfo> segmentInfos = extractSegmentInfos(playlistUri, playlistText);
        long segmentOrdinal = 0L;
        for (SegmentInfo segmentInfo : segmentInfos) {
            long sequence = mediaSequence + segmentOrdinal;
            segmentOrdinal++;
            result.put(sequence, segmentInfo);
        }
        return result;
    }

    @Nullable
    static Long extractMediaSequence(@NonNull String playlistText) {
        String[] lines = playlistText.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            Matcher matcher = MEDIA_SEQUENCE_LINE.matcher(line);
            if (matcher.matches()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        return null;
    }

    @NonNull
    private static List<SegmentInfo> extractSegmentInfos(@NonNull Uri playlistUri,
                                                         @NonNull String playlistText) {
        List<SegmentInfo> result = new ArrayList<>();
        String[] lines = playlistText.split("\\r?\\n");
        boolean expectSegmentUri = false;
        long pendingDurationMs = 0L;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pendingDurationMs = parseExtInfDurationMs(line);
                expectSegmentUri = true;
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (!expectSegmentUri) {
                continue;
            }
            expectSegmentUri = false;
            Uri resolved = resolveUri(playlistUri, line);
            if (resolved != null && isTsSegmentUri(resolved)) {
                result.add(new SegmentInfo(resolved, pendingDurationMs));
            }
            pendingDurationMs = 0L;
        }
        return result;
    }

    private static long parseExtInfDurationMs(@NonNull String extInfLine) {
        Matcher matcher = EXTINF_LINE.matcher(extInfLine);
        if (!matcher.matches()) {
            return 0L;
        }
        try {
            double seconds = Double.parseDouble(matcher.group(1));
            return Math.max(0L, Math.round(seconds * 1000d));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean isTsSegmentUri(@NonNull Uri uri) {
        String value = uri.toString().toLowerCase();
        return value.endsWith(".ts") || value.contains(".ts?");
    }

    private static Uri resolveUri(@NonNull Uri baseUri, @NonNull String relativeOrAbsolute) {
        try {
            java.net.URI resolved = java.net.URI.create(baseUri.toString()).resolve(relativeOrAbsolute);
            return Uri.parse(resolved.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
