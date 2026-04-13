package com.whyun.witv.player;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.NavigableMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    @Test
    public void extractMediaSequenceReturnsDeclaredValue() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:21446001\n"
                + "#EXTINF:10,\n"
                + "seg_21446001.ts\n";

        assertEquals(Long.valueOf(21446001L),
                HlsSegmentPrefetchPlanner.extractMediaSequence(playlist));
    }

    @Test
    public void extractMediaSequenceReturnsNullWhenMissing() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:10,\n"
                + "seg_100.ts\n";

        assertNull(HlsSegmentPrefetchPlanner.extractMediaSequence(playlist));
    }

    @Test
    public void extractSegmentUrisByMediaSequenceUsesPlaylistSequenceNumbers() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:21446001\n"
                + "#EXTINF:10,\n"
                + "seg_21446001.ts?token=a\n"
                + "#EXTINF:10,\n"
                + "seg_21446002.ts?token=b\n"
                + "#EXTINF:10,\n"
                + "seg_21446003.ts?token=c\n";

        NavigableMap<Long, Uri> result = HlsSegmentPrefetchPlanner.extractSegmentUrisByMediaSequence(
                Uri.parse("http://example.com/live/index.m3u8"),
                playlist);

        assertEquals(3, result.size());
        assertEquals("http://example.com/live/seg_21446001.ts?token=a",
                result.get(21446001L).toString());
        assertEquals("http://example.com/live/seg_21446002.ts?token=b",
                result.get(21446002L).toString());
        assertEquals("http://example.com/live/seg_21446003.ts?token=c",
                result.get(21446003L).toString());
    }

    @Test
    public void extractSegmentInfosByMediaSequenceIncludesExtInfDuration() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:21446001\n"
                + "#EXTINF:5.432,\n"
                + "seg_21446001.ts?token=a\n"
                + "#EXTINF:6,\n"
                + "seg_21446002.ts?token=b\n";

        NavigableMap<Long, HlsSegmentPrefetchPlanner.SegmentInfo> result =
                HlsSegmentPrefetchPlanner.extractSegmentInfosByMediaSequence(
                        Uri.parse("http://example.com/live/index.m3u8"),
                        playlist);

        assertEquals(2, result.size());
        assertEquals(5432L, result.get(21446001L).durationMs);
        assertEquals(6000L, result.get(21446002L).durationMs);
    }

    @Test
    public void extractSegmentUrisByMediaSequenceReturnsEmptyWhenMissingSequenceHeader() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:10,\n"
                + "seg_100.ts\n";

        NavigableMap<Long, Uri> result = HlsSegmentPrefetchPlanner.extractSegmentUrisByMediaSequence(
                Uri.parse("http://example.com/live/index.m3u8"),
                playlist);

        assertTrue(result.isEmpty());
    }
}
