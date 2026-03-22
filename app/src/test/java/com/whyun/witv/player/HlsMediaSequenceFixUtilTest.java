package com.whyun.witv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HlsMediaSequenceFixUtilTest {

    private static final String BROKEN_PLAYLIST = "#EXTM3U\n"
            + "##EXT-X-VERSION:3\n"
            + "#EXT-X-ALLOW-CACHE:YES\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:21446001\n"
            + "#EXTINF:5, no desc\n"
            + "live_1_1_21445997.ts?key=txiptv&key2=21445997\n"
            + "#EXTINF:5, no desc\n"
            + "live_1_1_21445998.ts?key=txiptv&key2=21445998\n"
            + "#EXTINF:5, no desc\n"
            + "live_1_1_21445999.ts?key=txiptv&key2=21445999\n"
            + "#EXTINF:5, no desc\n"
            + "live_1_1_21446000.ts?key=txiptv&key2=21446000\n"
            + "#EXTINF:5, no desc\n"
            + "live_1_1_21446001.ts?key=txiptv&key2=21446001\n";

    @Test
    public void fixesMediaSequenceWhenItMatchesLastSegmentOnly() {
        String fixed = HlsMediaSequenceFixUtil.fixPlaylistIfNeeded(BROKEN_PLAYLIST);
        assertTrue(fixed.contains("#EXT-X-VERSION:3"));
        assertEquals(-1, fixed.indexOf("##EXT-X-VERSION"));
        boolean seenSeq = false;
        for (String line : fixed.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                assertEquals("#EXT-X-MEDIA-SEQUENCE:21445997", t);
                seenSeq = true;
            }
        }
        assertTrue(seenSeq);
    }

    @Test
    public void leavesCorrectPlaylistUnchanged() {
        String correct = BROKEN_PLAYLIST.replace(
                "#EXT-X-MEDIA-SEQUENCE:21446001", "#EXT-X-MEDIA-SEQUENCE:21445997");
        correct = correct.replace("##EXT-X-VERSION:", "#EXT-X-VERSION:");
        String out = HlsMediaSequenceFixUtil.fixPlaylistIfNeeded(correct);
        assertEquals(correct, out);
    }

    @Test
    public void leavesMasterPlaylistUnchanged() {
        String master = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1280000\n"
                + "chunklist.m3u8\n";
        assertSame(master, HlsMediaSequenceFixUtil.fixPlaylistIfNeeded(master));
    }

    @Test
    public void leavesSingleSegmentUnchanged() {
        String one = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:100\n"
                + "#EXTINF:5,\n"
                + "seg_100.ts?key2=100\n";
        assertEquals(one, HlsMediaSequenceFixUtil.fixPlaylistIfNeeded(one));
    }
}
