package com.whyun.witv.data.repository;

import android.content.Context;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.dao.ChannelDao;
import com.whyun.witv.data.db.dao.ChannelSourceDao;
import com.whyun.witv.data.db.dao.FavoriteChannelDao;
import com.whyun.witv.data.db.dao.M3USourceDao;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.FavoriteChannel;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.parser.M3UParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final FavoriteChannelDao favoriteChannelDao;
    private final OkHttpClient httpClient;

    public ChannelRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.channelDao = db.channelDao();
        this.channelSourceDao = db.channelSourceDao();
        this.m3uSourceDao = db.m3uSourceDao();
        this.favoriteChannelDao = db.favoriteChannelDao();
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

            // Build map of existing channels keyed by natural key
            // (tvgId if non-empty, else displayName — same key M3UParser uses for aggregation)
            List<Channel> existingChannels = channelDao.getBySource(sourceId);
            Map<String, Channel> existingMap = new LinkedHashMap<>();
            for (Channel ch : existingChannels) {
                String key = (ch.tvgId != null && !ch.tvgId.isEmpty()) ? ch.tvgId : ch.displayName;
                existingMap.put(key, ch);
            }

            // Delete all channel_sources; they will be re-created with fresh URLs
            channelSourceDao.deleteBySource(sourceId);

            Set<String> matchedKeys = new HashSet<>();
            int sortOrder = 0;
            for (M3UParser.ParsedChannel parsed : result.channels) {
                String key = (parsed.tvgId != null && !parsed.tvgId.isEmpty())
                        ? parsed.tvgId : parsed.displayName;

                Channel existing = existingMap.get(key);
                long channelId;

                if (existing != null) {
                    matchedKeys.add(key);
                    existing.tvgId = parsed.tvgId;
                    existing.tvgName = parsed.tvgName;
                    existing.displayName = parsed.displayName;
                    existing.logoUrl = parsed.logoUrl;
                    existing.groupTitle = parsed.groupTitle;
                    existing.sortOrder = sortOrder++;
                    channelDao.update(existing);
                    channelId = existing.id;
                } else {
                    Channel channel = new Channel(
                            sourceId,
                            parsed.tvgId,
                            parsed.tvgName,
                            parsed.displayName,
                            parsed.logoUrl,
                            parsed.groupTitle,
                            sortOrder++
                    );
                    channelId = channelDao.insert(channel);
                }

                List<ChannelSource> sources = new ArrayList<>();
                for (int i = 0; i < parsed.sourceUrls.size(); i++) {
                    sources.add(new ChannelSource(channelId, parsed.sourceUrls.get(i), i));
                }
                if (!sources.isEmpty()) {
                    channelSourceDao.insertAll(sources);
                }
            }

            // Remove channels that no longer exist in the source
            List<Long> toDelete = new ArrayList<>();
            for (Map.Entry<String, Channel> entry : existingMap.entrySet()) {
                if (!matchedKeys.contains(entry.getKey())) {
                    toDelete.add(entry.getValue().id);
                }
            }
            if (!toDelete.isEmpty()) {
                channelDao.deleteByIds(toDelete);
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

    public boolean isFavorite(long channelId) {
        return favoriteChannelDao.isFavorite(channelId);
    }

    public void toggleFavorite(long channelId) {
        if (favoriteChannelDao.isFavorite(channelId)) {
            favoriteChannelDao.deleteByChannelId(channelId);
        } else {
            favoriteChannelDao.insert(new FavoriteChannel(channelId, System.currentTimeMillis()));
        }
    }

    public void addFavorite(long channelId) {
        if (!favoriteChannelDao.isFavorite(channelId)) {
            favoriteChannelDao.insert(new FavoriteChannel(channelId, System.currentTimeMillis()));
        }
    }

    public void removeFavorite(long channelId) {
        favoriteChannelDao.deleteByChannelId(channelId);
    }

    public List<Channel> getFavoriteChannels(long sourceId) {
        return favoriteChannelDao.getFavoriteChannels(sourceId);
    }

    public List<Channel> getAllFavoriteChannels() {
        return favoriteChannelDao.getAllFavoriteChannels();
    }

    public List<Long> getAllFavoriteChannelIds() {
        return favoriteChannelDao.getAllFavoriteChannelIds();
    }
}
