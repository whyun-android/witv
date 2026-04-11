package com.whyun.witv.player;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class HlsSegmentPrefetchPlannerTest {

    @Test
    public void planSegmentUrisSkipsNewestSegmentToAvoidRacingPlayback() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXTINF:10,\n"
                + "seg_100.ts?token=a\n"
                + "#EXTINF:10,\n"
                + "seg_101.ts?token=b\n"
                + "#EXTINF:10,\n"
                + "seg_102.ts?token=c\n";

        List<Uri> result = HlsSegmentPrefetchPlanner.planSegmentUris(
                Uri.parse("http://example.com/live/index.m3u8"),
                playlist,
                2,
                1);

        assertEquals(2, result.size());
        assertEquals("http://example.com/live/seg_100.ts?token=a", result.get(0).toString());
        assertEquals("http://example.com/live/seg_101.ts?token=b", result.get(1).toString());
    }

    @Test
    public void planSegmentUrisIgnoresMasterPlaylistVariantUris() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=500000\n"
                + "low/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000\n"
                + "high/index.m3u8\n";

        List<Uri> result = HlsSegmentPrefetchPlanner.planSegmentUris(
                Uri.parse("http://example.com/master.m3u8"),
                playlist,
                2,
                1);

        assertTrue(result.isEmpty());
    }

    @Test
    public void planSegmentUrisReturnsEmptyWhenSkippedTailConsumesAllSegments() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:10,\n"
                + "seg_100.ts\n";

        List<Uri> result = HlsSegmentPrefetchPlanner.planSegmentUris(
                Uri.parse("http://example.com/live/index.m3u8"),
                playlist,
                2,
                1);

        assertTrue(result.isEmpty());
    }

    @Test
    public void extractSegmentUrisReturnsEntireLiveWindow() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXTINF:10,\n"
                + "seg_100.ts?token=a\n"
                + "#EXTINF:10,\n"
                + "seg_101.ts?token=b\n"
                + "#EXTINF:10,\n"
                + "seg_102.ts?token=c\n";

        List<Uri> result = HlsSegmentPrefetchPlanner.extractSegmentUris(
                Uri.parse("http://example.com/live/index.m3u8"),
                playlist);

        assertEquals(3, result.size());
        assertEquals("http://example.com/live/seg_100.ts?token=a", result.get(0).toString());
        assertEquals("http://example.com/live/seg_101.ts?token=b", result.get(1).toString());
        assertEquals("http://example.com/live/seg_102.ts?token=c", result.get(2).toString());
    }
}
