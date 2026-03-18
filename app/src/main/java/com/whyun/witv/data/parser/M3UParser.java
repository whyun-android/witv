package com.whyun.witv.data.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for M3U/M3U8 playlist format.
 */
public class M3UParser {

    private static final Pattern EXTINF_ATTR_PATTERN = Pattern.compile(
            "([a-zA-Z0-9-]+)=\"([^\"]*)\""
    );
    private static final Pattern X_TVG_URL_PATTERN = Pattern.compile(
            "x-tvg-url=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Result of parsing an M3U playlist.
     */
    public static class ParseResult {
        public String epgUrl;
        public List<ParsedChannel> channels;

        public ParseResult(String epgUrl, List<ParsedChannel> channels) {
            this.epgUrl = epgUrl;
            this.channels = channels;
        }
    }

    /**
     * A parsed channel with its attributes and source URLs.
     */
    public static class ParsedChannel {
        public String tvgId;
        public String tvgName;
        public String displayName;
        public String logoUrl;
        public String groupTitle;
        public List<String> sourceUrls;

        public ParsedChannel(String tvgId, String tvgName, String displayName,
                             String logoUrl, String groupTitle, List<String> sourceUrls) {
            this.tvgId = tvgId;
            this.tvgName = tvgName;
            this.displayName = displayName;
            this.logoUrl = logoUrl;
            this.groupTitle = groupTitle;
            this.sourceUrls = sourceUrls;
        }
    }

    /**
     * Parses M3U/M3U8 content.
     *
     * @param content The raw M3U content
     * @return ParseResult with epgUrl and aggregated channels
     */
    public ParseResult parse(String content) {
        String epgUrl = null;
        List<ParsedChannel> rawChannels = new ArrayList<>();

        // Normalize line endings to \n
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Parse EXTM3U header for x-tvg-url
            if (line.startsWith("#EXTM3U")) {
                Matcher tvgMatcher = X_TVG_URL_PATTERN.matcher(line);
                if (tvgMatcher.find()) {
                    epgUrl = tvgMatcher.group(1).trim();
                    if (epgUrl.isEmpty()) {
                        epgUrl = null;
                    }
                }
                continue;
            }

            // Parse EXTINF line
            if (line.startsWith("#EXTINF:")) {
                String tvgId = null;
                String tvgName = null;
                String logoUrl = null;
                String groupTitle = null;
                String displayName = null;

                // Extract attributes using regex
                Matcher attrMatcher = EXTINF_ATTR_PATTERN.matcher(line);
                while (attrMatcher.find()) {
                    String key = attrMatcher.group(1).toLowerCase();
                    String value = attrMatcher.group(2);
                    switch (key) {
                        case "tvg-id":
                            tvgId = value;
                            break;
                        case "tvg-name":
                            tvgName = value;
                            break;
                        case "tvg-logo":
                            logoUrl = value;
                            break;
                        case "group-title":
                            groupTitle = value;
                            break;
                    }
                }

                // Display name is after the last comma on the EXTINF line
                int lastComma = line.lastIndexOf(',');
                if (lastComma >= 0 && lastComma < line.length() - 1) {
                    displayName = line.substring(lastComma + 1).trim();
                }
                if (displayName == null || displayName.isEmpty()) {
                    displayName = tvgName != null ? tvgName : "";
                }

                // Find URL on next non-empty, non-comment line
                String url = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String nextLine = lines[j].trim();
                    if (nextLine.isEmpty()) {
                        continue;
                    }
                    if (nextLine.startsWith("#")) {
                        break; // Next EXTINF or other tag, no URL
                    }
                    url = nextLine;
                    break;
                }

                if (url != null && !url.isEmpty()) {
                    ParsedChannel ch = new ParsedChannel(
                            tvgId != null ? tvgId : "",
                            tvgName != null ? tvgName : "",
                            displayName,
                            logoUrl != null ? logoUrl : "",
                            groupTitle != null ? groupTitle : "",
                            new ArrayList<>()
                    );
                    ch.sourceUrls.add(url);
                    rawChannels.add(ch);
                }
            }
        }

        // Aggregate channels by tvg-id (or displayName if tvg-id empty)
        Map<String, ParsedChannel> aggregated = new LinkedHashMap<>();
        for (ParsedChannel ch : rawChannels) {
            String key = (ch.tvgId != null && !ch.tvgId.isEmpty())
                    ? ch.tvgId
                    : ch.displayName;
            if (key == null) {
                key = "";
            }
            ParsedChannel existing = aggregated.get(key);
            if (existing != null) {
                existing.sourceUrls.addAll(ch.sourceUrls);
                // Prefer non-empty attributes from first occurrence
                if (ch.logoUrl != null && !ch.logoUrl.isEmpty() && (existing.logoUrl == null || existing.logoUrl.isEmpty())) {
                    existing.logoUrl = ch.logoUrl;
                }
                if (ch.groupTitle != null && !ch.groupTitle.isEmpty() && (existing.groupTitle == null || existing.groupTitle.isEmpty())) {
                    existing.groupTitle = ch.groupTitle;
                }
            } else {
                aggregated.put(key, ch);
            }
        }

        return new ParseResult(epgUrl, new ArrayList<>(aggregated.values()));
    }
}
