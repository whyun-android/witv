package com.whyun.witv.data.db.entity;

import org.junit.Test;

import static org.junit.Assert.*;

public class EntityTest {

    // --- Channel ---

    @Test
    public void channelDefaultConstructor() {
        Channel ch = new Channel();
        assertEquals(0, ch.id);
        assertEquals(0, ch.sourceId);
        assertNull(ch.tvgId);
        assertNull(ch.displayName);
        assertEquals(0, ch.sortOrder);
    }

    @Test
    public void channelParameterizedConstructor() {
        Channel ch = new Channel(10L, "cctv1", "CCTV-1", "CCTV-1 综合",
                "http://logo.com/1.png", "央视", 1);

        assertEquals(10L, ch.sourceId);
        assertEquals("cctv1", ch.tvgId);
        assertEquals("CCTV-1", ch.tvgName);
        assertEquals("CCTV-1 综合", ch.displayName);
        assertEquals("http://logo.com/1.png", ch.logoUrl);
        assertEquals("央视", ch.groupTitle);
        assertEquals(1, ch.sortOrder);
    }

    @Test
    public void channelFieldAssignment() {
        Channel ch = new Channel();
        ch.id = 99;
        ch.sourceId = 5;
        ch.tvgId = "test-id";
        ch.displayName = "Test Channel";

        assertEquals(99, ch.id);
        assertEquals(5, ch.sourceId);
        assertEquals("test-id", ch.tvgId);
        assertEquals("Test Channel", ch.displayName);
    }

    // --- M3USource ---

    @Test
    public void m3uSourceDefaultConstructor() {
        M3USource src = new M3USource();
        assertEquals(0, src.id);
        assertNull(src.name);
        assertNull(src.url);
        assertNull(src.epgUrl);
        assertFalse(src.isActive);
    }

    @Test
    public void m3uSourceParameterizedConstructor() {
        long now = System.currentTimeMillis();
        M3USource src = new M3USource("My Source", "http://example.com/live.m3u",
                "http://epg.example.com/epg.xml", now, true);

        assertEquals("My Source", src.name);
        assertEquals("http://example.com/live.m3u", src.url);
        assertEquals("http://epg.example.com/epg.xml", src.epgUrl);
        assertEquals(now, src.addedAt);
        assertTrue(src.isActive);
    }

    @Test
    public void m3uSourceNullEpgUrl() {
        M3USource src = new M3USource("Test", "http://test.com/live.m3u", null, 0, false);
        assertNull(src.epgUrl);
    }

    // --- ChannelSource ---

    @Test
    public void channelSourceDefaultConstructor() {
        ChannelSource cs = new ChannelSource();
        assertEquals(0, cs.id);
        assertEquals(0, cs.channelId);
        assertNull(cs.url);
        assertEquals(0, cs.priority);
    }

    @Test
    public void channelSourceParameterizedConstructor() {
        ChannelSource cs = new ChannelSource(42L, "http://stream.example.com/ch.m3u8", 1);

        assertEquals(42L, cs.channelId);
        assertEquals("http://stream.example.com/ch.m3u8", cs.url);
        assertEquals(1, cs.priority);
    }

    @Test
    public void channelSourcePriorityOrder() {
        ChannelSource high = new ChannelSource(1L, "http://a.com/1.m3u8", 0);
        ChannelSource low = new ChannelSource(1L, "http://b.com/1.m3u8", 5);

        assertTrue(high.priority < low.priority);
    }

    // --- EpgProgram ---

    @Test
    public void epgProgramDefaultConstructor() {
        EpgProgram ep = new EpgProgram();
        assertEquals(0, ep.id);
        assertNull(ep.channelTvgId);
        assertNull(ep.title);
        assertEquals(0, ep.startTime);
        assertEquals(0, ep.endTime);
    }

    @Test
    public void epgProgramParameterizedConstructor() {
        EpgProgram ep = new EpgProgram("cctv1", "新闻联播", "每日新闻",
                1710835200000L, 1710837000000L);

        assertEquals("cctv1", ep.channelTvgId);
        assertEquals("新闻联播", ep.title);
        assertEquals("每日新闻", ep.description);
        assertEquals(1710835200000L, ep.startTime);
        assertEquals(1710837000000L, ep.endTime);
    }

    @Test
    public void epgProgramTimeRange() {
        EpgProgram ep = new EpgProgram("ch1", "Show", "",
                1000L, 2000L);
        assertTrue(ep.endTime > ep.startTime);
    }

    // --- EpgChannel ---

    @Test
    public void epgChannelDefaultConstructor() {
        EpgChannel ec = new EpgChannel();
        assertEquals("", ec.channelId);
        assertNull(ec.displayName);
    }

    @Test
    public void epgChannelParameterizedConstructor() {
        EpgChannel ec = new EpgChannel("cctv1", "CCTV-1 综合");
        assertEquals("cctv1", ec.channelId);
        assertEquals("CCTV-1 综合", ec.displayName);
    }

    // --- FavoriteChannel ---

    @Test
    public void favoriteChannelDefaultConstructor() {
        FavoriteChannel fav = new FavoriteChannel();
        assertEquals(0, fav.id);
        assertEquals(0, fav.channelId);
        assertEquals(0, fav.addedAt);
    }

    @Test
    public void favoriteChannelParameterizedConstructor() {
        long now = System.currentTimeMillis();
        FavoriteChannel fav = new FavoriteChannel(100L, now);

        assertEquals(100L, fav.channelId);
        assertEquals(now, fav.addedAt);
    }
}
