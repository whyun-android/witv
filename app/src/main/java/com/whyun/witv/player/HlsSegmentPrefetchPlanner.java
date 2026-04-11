package com.whyun.witv.player;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 media playlist 规划需要预取的尾部 ts 分片。
 */
public final class HlsSegmentPrefetchPlanner {

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
        String[] lines = playlistText.split("\\r?\\n");
        boolean expectSegmentUri = false;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
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
                result.add(resolved);
            }
        }
        return result;
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
