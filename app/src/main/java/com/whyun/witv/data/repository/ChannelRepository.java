package com.whyun.witv.data.repository;

import android.content.Context;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.dao.ChannelDao;
import com.whyun.witv.data.db.dao.ChannelSourceDao;
import com.whyun.witv.data.db.dao.M3USourceDao;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.parser.M3UParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Repository that coordinates M3U parsing and database operations.
 */
public class ChannelRepository {

    private final ChannelDao channelDao;
    private final ChannelSourceDao channelSourceDao;
    private final M3USourceDao m3uSourceDao;
    private final OkHttpClient httpClient;

    public ChannelRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.channelDao = db.channelDao();
        this.channelSourceDao = db.channelSourceDao();
        this.m3uSourceDao = db.m3uSourceDao();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Loads M3U content from the source URL, parses it, and saves to database.
     *
     * @param source The M3U source to load
     * @return The parse result
     * @throws IOException if network request fails
     */
    public M3UParser.ParseResult loadSource(M3USource source) throws IOException {
        Request request = new Request.Builder()
                .url(source.url)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to fetch M3U: " + response.code());
            }
            String content = response.body().string();
            M3UParser.ParseResult result = new M3UParser().parse(content);

            long sourceId = source.id;

            // Delete old channel sources first, then channels
            channelSourceDao.deleteBySource(sourceId);
            channelDao.deleteBySource(sourceId);

            // Insert new channels with incrementing sortOrder
            int sortOrder = 0;
            for (M3UParser.ParsedChannel parsed : result.channels) {
                Channel channel = new Channel(
                        sourceId,
                        parsed.tvgId,
                        parsed.tvgName,
                        parsed.displayName,
                        parsed.logoUrl,
                        parsed.groupTitle,
                        sortOrder++
                );
                long channelId = channelDao.insert(channel);

                // Insert channel sources (urls) with priority by appearance order
                List<ChannelSource> sources = new ArrayList<>();
                for (int i = 0; i < parsed.sourceUrls.size(); i++) {
                    sources.add(new ChannelSource(channelId, parsed.sourceUrls.get(i), i));
                }
                if (!sources.isEmpty()) {
                    channelSourceDao.insertAll(sources);
                }
            }

            // Update source EPG URL if found in parse result
            if (result.epgUrl != null && !result.epgUrl.isEmpty()) {
                source.epgUrl = result.epgUrl;
                m3uSourceDao.update(source);
            }

            return result;
        }
    }

    /**
     * Gets distinct group titles for a source.
     */
    public List<String> getGroups(long sourceId) {
        return channelDao.getGroups(sourceId);
    }

    /**
     * Gets channels in a specific group.
     */
    public List<Channel> getChannelsByGroup(long sourceId, String group) {
        return channelDao.getByGroup(sourceId, group);
    }

    /**
     * Gets all source URLs for a channel (ordered by priority).
     */
    public List<ChannelSource> getChannelSources(long channelId) {
        return channelSourceDao.getByChannel(channelId);
    }
}
