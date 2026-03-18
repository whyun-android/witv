package com.whyun.witv.data.parser;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for XMLTV format EPG data.
 */
public class EpgParser {

    private static final String CHARSET_UTF8 = "UTF-8";

    /**
     * Parsed EPG program data.
     */
    public static class EpgProgramData {
        public String channelId;
        public String title;
        public String description;
        public long startTime;
        public long endTime;

        public EpgProgramData(String channelId, String title, String description,
                              long startTime, long endTime) {
            this.channelId = channelId;
            this.title = title;
            this.description = description;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }


    /**
     * Parses XMLTV EPG data from an input stream.
     *
     * @param inputStream The XML input stream
     * @return List of EpgProgramData
     * @throws XmlPullParserException if XML parsing fails
     * @throws IOException if reading fails
     */
    public List<EpgProgramData> parse(InputStream inputStream)
            throws XmlPullParserException, IOException {
        List<EpgProgramData> programs = new ArrayList<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(inputStream, CHARSET_UTF8);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if ("programme".equals(parser.getName())) {
                    EpgProgramData program = parseProgramme(parser);
                    if (program != null) {
                        programs.add(program);
                    }
                }
            }
            eventType = parser.next();
        }

        return programs;
    }

    private EpgProgramData parseProgramme(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String channel = parser.getAttributeValue(null, "channel");
        String start = parser.getAttributeValue(null, "start");
        String stop = parser.getAttributeValue(null, "stop");

        if (channel == null || start == null || stop == null) {
            skipToEndTag(parser, "programme");
            return null;
        }

        long startTime;
        long endTime;
        try {
            startTime = parseXmltvTime(start);
            endTime = parseXmltvTime(stop);
        } catch (ParseException e) {
            skipToEndTag(parser, "programme");
            return null;
        }

        String title = "";
        String description = "";

        int eventType = parser.next();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                if ("title".equals(tagName)) {
                    title = readText(parser);
                } else if ("desc".equals(tagName)) {
                    description = readText(parser);
                }
            } else if (eventType == XmlPullParser.END_TAG && "programme".equals(parser.getName())) {
                break;
            }
            eventType = parser.next();
        }

        return new EpgProgramData(channel, title, description, startTime, endTime);
    }

    private String readText(XmlPullParser parser) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder();
        int eventType = parser.next();
        while (eventType != XmlPullParser.END_TAG) {
            if (eventType == XmlPullParser.TEXT) {
                sb.append(parser.getText());
            }
            eventType = parser.next();
        }
        return sb.toString().trim();
    }

    private void skipToEndTag(XmlPullParser parser, String tagName)
            throws XmlPullParserException, IOException {
        int depth = 1;
        int eventType = parser.next();
        while (depth > 0 && eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                depth++;
            } else if (eventType == XmlPullParser.END_TAG) {
                depth--;
            }
            eventType = parser.next();
        }
    }

    /**
     * Parses XMLTV time format: "yyyyMMddHHmmss +ZZZZ" e.g. "20240101120000 +0800"
     */
    private long parseXmltvTime(String timeStr) throws ParseException {
        if (timeStr == null || timeStr.isEmpty()) {
            throw new ParseException("Empty time string", 0);
        }
        // Normalize: ensure space before timezone
        String normalized = timeStr.trim();
        if (normalized.length() < 15) {
            throw new ParseException("Invalid time format: " + timeStr, 0);
        }
        // Format: yyyyMMddHHmmss followed by optional timezone
        String datePart = normalized.substring(0, 14);
        String tzPart = normalized.length() > 14 ? normalized.substring(14).trim() : "+0000";
        if (tzPart.isEmpty() || (!tzPart.startsWith("+") && !tzPart.startsWith("-"))) {
            tzPart = "+0000";
        }
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        return df.parse(datePart + " " + tzPart).getTime();
    }
}
