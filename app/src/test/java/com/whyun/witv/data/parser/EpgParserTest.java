package com.whyun.witv.data.parser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class EpgParserTest {

    private EpgParser parser;

    @Before
    public void setUp() {
        parser = new EpgParser();
    }

    private InputStream toStream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void parseSingleProgramme() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101120000 +0800\" stop=\"20240101130000 +0800\" channel=\"cctv1\">\n"
                + "    <title>新闻联播</title>\n"
                + "    <desc>每日新闻节目</desc>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(1, programs.size());
        EpgParser.EpgProgramData p = programs.get(0);
        assertEquals("cctv1", p.channelId);
        assertEquals("新闻联播", p.title);
        assertEquals("每日新闻节目", p.description);
        assertTrue(p.startTime > 0);
        assertTrue(p.endTime > p.startTime);
    }

    @Test
    public void parseMultipleProgrammes() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101120000 +0800\" stop=\"20240101130000 +0800\" channel=\"cctv1\">\n"
                + "    <title>节目A</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20240101130000 +0800\" stop=\"20240101140000 +0800\" channel=\"cctv1\">\n"
                + "    <title>节目B</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20240101120000 +0800\" stop=\"20240101133000 +0800\" channel=\"cctv2\">\n"
                + "    <title>节目C</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(3, programs.size());
        assertEquals("节目A", programs.get(0).title);
        assertEquals("节目B", programs.get(1).title);
        assertEquals("节目C", programs.get(2).title);
        assertEquals("cctv1", programs.get(0).channelId);
        assertEquals("cctv2", programs.get(2).channelId);
    }

    @Test
    public void parseTimeWithTimezone() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101040000 +0000\" stop=\"20240101050000 +0000\" channel=\"bbc1\">\n"
                + "    <title>Morning News</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(1, programs.size());
        EpgParser.EpgProgramData p = programs.get(0);
        assertEquals("bbc1", p.channelId);
        long duration = p.endTime - p.startTime;
        assertEquals(3600_000, duration); // 1 hour
    }

    @Test
    public void parseProgrammeWithoutDescription() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101100000 +0800\" stop=\"20240101110000 +0800\" channel=\"ch1\">\n"
                + "    <title>仅标题</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(1, programs.size());
        assertEquals("仅标题", programs.get(0).title);
        assertEquals("", programs.get(0).description);
    }

    @Test
    public void skipProgrammeWithMissingChannel() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101100000 +0800\" stop=\"20240101110000 +0800\">\n"
                + "    <title>无频道ID</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20240101100000 +0800\" stop=\"20240101110000 +0800\" channel=\"valid\">\n"
                + "    <title>有效节目</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(1, programs.size());
        assertEquals("valid", programs.get(0).channelId);
    }

    @Test
    public void skipProgrammeWithMissingStartTime() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme stop=\"20240101110000 +0800\" channel=\"ch1\">\n"
                + "    <title>缺少开始时间</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));
        assertTrue(programs.isEmpty());
    }

    @Test
    public void skipProgrammeWithMissingStopTime() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101100000 +0800\" channel=\"ch1\">\n"
                + "    <title>缺少结束时间</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));
        assertTrue(programs.isEmpty());
    }

    @Test
    public void parseEmptyTvElement() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv></tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));
        assertTrue(programs.isEmpty());
    }

    @Test
    public void parseProgrammeWithNegativeTimezone() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101120000 -0500\" stop=\"20240101130000 -0500\" channel=\"est\">\n"
                + "    <title>Eastern Time Show</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(1, programs.size());
        assertEquals("Eastern Time Show", programs.get(0).title);
        assertTrue(programs.get(0).endTime > programs.get(0).startTime);
    }

    @Test
    public void differentChannelsAreParsedSeparately() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <programme start=\"20240101080000 +0800\" stop=\"20240101090000 +0800\" channel=\"ch_a\">\n"
                + "    <title>频道A节目</title>\n"
                + "  </programme>\n"
                + "  <programme start=\"20240101080000 +0800\" stop=\"20240101093000 +0800\" channel=\"ch_b\">\n"
                + "    <title>频道B节目</title>\n"
                + "  </programme>\n"
                + "</tv>";

        List<EpgParser.EpgProgramData> programs = parser.parse(toStream(xml));

        assertEquals(2, programs.size());
        assertEquals("ch_a", programs.get(0).channelId);
        assertEquals("ch_b", programs.get(1).channelId);
    }
}
