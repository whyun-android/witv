package com.whyun.witv.data.parser;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class M3UParserTest {

    private M3UParser parser;

    @Before
    public void setUp() {
        parser = new M3UParser();
    }

    @Test
    public void parseBasicM3U() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" tvg-name=\"CCTV-1\" tvg-logo=\"http://logo.com/1.png\" group-title=\"央视\",CCTV-1 综合\n"
                + "http://stream.example.com/cctv1.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertNull(result.epgUrl);
        assertEquals(1, result.channels.size());

        M3UParser.ParsedChannel ch = result.channels.get(0);
        assertEquals("cctv1", ch.tvgId);
        assertEquals("CCTV-1", ch.tvgName);
        assertEquals("CCTV-1 综合", ch.displayName);
        assertEquals("http://logo.com/1.png", ch.logoUrl);
        assertEquals("央视", ch.groupTitle);
        assertEquals(1, ch.sourceUrls.size());
        assertEquals("http://stream.example.com/cctv1.m3u8", ch.sourceUrls.get(0));
    }

    @Test
    public void parseEpgUrl() {
        String content = "#EXTM3U x-tvg-url=\"http://epg.example.com/epg.xml\"\n"
                + "#EXTINF:-1,TestChannel\n"
                + "http://stream.example.com/test.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals("http://epg.example.com/epg.xml", result.epgUrl);
        assertEquals(1, result.channels.size());
    }

    @Test
    public void parseEpgUrlCaseInsensitive() {
        String content = "#EXTM3U X-TVG-URL=\"http://epg.example.com/epg.xml\"\n"
                + "#EXTINF:-1,TestChannel\n"
                + "http://stream.example.com/test.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);
        assertEquals("http://epg.example.com/epg.xml", result.epgUrl);
    }

    @Test
    public void parseEmptyEpgUrlReturnsNull() {
        String content = "#EXTM3U x-tvg-url=\"\"\n"
                + "#EXTINF:-1,TestChannel\n"
                + "http://stream.example.com/test.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);
        assertNull(result.epgUrl);
    }

    @Test
    public void parseMultipleChannels() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"ch1\" group-title=\"Group A\",Channel 1\n"
                + "http://stream.example.com/ch1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"ch2\" group-title=\"Group B\",Channel 2\n"
                + "http://stream.example.com/ch2.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"ch3\" group-title=\"Group A\",Channel 3\n"
                + "http://stream.example.com/ch3.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(3, result.channels.size());
        assertEquals("ch1", result.channels.get(0).tvgId);
        assertEquals("ch2", result.channels.get(1).tvgId);
        assertEquals("ch3", result.channels.get(2).tvgId);
    }

    @Test
    public void aggregateChannelsBySameTvgId() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" tvg-logo=\"http://logo.com/1.png\" group-title=\"央视\",CCTV-1\n"
                + "http://stream1.example.com/cctv1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\",CCTV-1\n"
                + "http://stream2.example.com/cctv1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\",CCTV-1\n"
                + "http://stream3.example.com/cctv1.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        M3UParser.ParsedChannel ch = result.channels.get(0);
        assertEquals("cctv1", ch.tvgId);
        assertEquals(3, ch.sourceUrls.size());
        assertEquals("http://stream1.example.com/cctv1.m3u8", ch.sourceUrls.get(0));
        assertEquals("http://stream2.example.com/cctv1.m3u8", ch.sourceUrls.get(1));
        assertEquals("http://stream3.example.com/cctv1.m3u8", ch.sourceUrls.get(2));
    }

    @Test
    public void aggregatePreservesFirstNonEmptyAttributes() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"ch1\",Channel 1\n"
                + "http://stream1.example.com/ch1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"ch1\" tvg-logo=\"http://logo.com/ch1.png\" group-title=\"MyGroup\",Channel 1\n"
                + "http://stream2.example.com/ch1.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        M3UParser.ParsedChannel ch = result.channels.get(0);
        assertEquals("http://logo.com/ch1.png", ch.logoUrl);
        assertEquals("MyGroup", ch.groupTitle);
    }

    @Test
    public void aggregateByDisplayNameWhenTvgIdEmpty() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1,Same Channel\n"
                + "http://stream1.example.com/sc.m3u8\n"
                + "#EXTINF:-1,Same Channel\n"
                + "http://stream2.example.com/sc.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        assertEquals(2, result.channels.get(0).sourceUrls.size());
    }

    @Test
    public void parseEmptyContent() {
        M3UParser.ParseResult result = parser.parse("");

        assertNull(result.epgUrl);
        assertTrue(result.channels.isEmpty());
    }

    @Test
    public void parseHeaderOnly() {
        M3UParser.ParseResult result = parser.parse("#EXTM3U\n");

        assertNull(result.epgUrl);
        assertTrue(result.channels.isEmpty());
    }

    @Test
    public void skipExtinfWithoutUrl() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"ch1\",Channel 1\n"
                + "#EXTINF:-1 tvg-id=\"ch2\",Channel 2\n"
                + "http://stream.example.com/ch2.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        assertEquals("ch2", result.channels.get(0).tvgId);
    }

    @Test
    public void displayNameFallsBackToTvgName() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"ch1\" tvg-name=\"FallbackName\"\n"
                + "http://stream.example.com/ch1.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        assertEquals("FallbackName", result.channels.get(0).displayName);
    }

    @Test
    public void handleWindowsLineEndings() {
        String content = "#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-id=\"ch1\",Channel 1\r\n"
                + "http://stream.example.com/ch1.m3u8\r\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        assertEquals("Channel 1", result.channels.get(0).displayName);
    }

    @Test
    public void handleOldMacLineEndings() {
        String content = "#EXTM3U\r"
                + "#EXTINF:-1 tvg-id=\"ch1\",Channel 1\r"
                + "http://stream.example.com/ch1.m3u8\r";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
    }

    @Test
    public void skipBlankLinesBetweenExtinfAndUrl() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"ch1\",Channel 1\n"
                + "\n"
                + "\n"
                + "http://stream.example.com/ch1.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        assertEquals("http://stream.example.com/ch1.m3u8", result.channels.get(0).sourceUrls.get(0));
    }

    @Test
    public void missingAttributesDefaultToEmptyString() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1,SimpleChannel\n"
                + "http://stream.example.com/simple.m3u8\n";

        M3UParser.ParseResult result = parser.parse(content);

        assertEquals(1, result.channels.size());
        M3UParser.ParsedChannel ch = result.channels.get(0);
        assertEquals("", ch.tvgId);
        assertEquals("", ch.tvgName);
        assertEquals("", ch.logoUrl);
        assertEquals("", ch.groupTitle);
        assertEquals("SimpleChannel", ch.displayName);
    }
}
