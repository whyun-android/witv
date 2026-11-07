package com.whyun.witv.data.repository;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.whyun.witv.data.BomAwareText;
import com.whyun.witv.data.GzipAwareStreams;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Repository that coordinates M3U parsing and database operations.
 */
public class ChannelRepository {

    private final AppDatabase db;
    private final ChannelDao channelDao;
    private final ChannelSourceDao channelSourceDao;
    private final M3USourceDao m3uSourceDao;
    private final FavoriteChannelDao favoriteChannelDao;
    private final OkHttpClient httpClient;
    /**
     * M3U 加载成功后用它按节流策略后台刷新 EPG。只有生产构造器会赋值；
     * 测试用的 {@code ChannelRepository(AppDatabase, OkHttpClient)} 传 null，跳过自动刷新。
     */
    private final EpgRepository epgRepository;

    public ChannelRepository(Context context) {
        this(AppDatabase.getInstance(context), new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(), new EpgRepository(context));
    }

    @VisibleForTesting
    ChannelRepository(AppDatabase db, OkHttpClient httpClient) {
        this(db, httpClient, null);
    }

    @VisibleForTesting
    ChannelRepository(AppDatabase db, OkHttpClient httpClient, EpgRepository epgRepository) {
        this.db = db;
        this.channelDao = db.channelDao();
        this.channelSourceDao = db.channelSourceDao();
        this.m3uSourceDao = db.m3uSourceDao();
        this.favoriteChannelDao = db.favoriteChannelDao();
        this.httpClient = httpClient;
        this.epgRepository = epgRepository;
    }

    /**
     * Loads M3U content from the source URL, parses it, and saves to database.
     *
     * @param source The M3U source to load
     * @return The parse result
     * @throws IOException if network request fails
     */
    private static byte[] readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public M3UParser.ParseResult loadSource(M3USource source) throws IOException {
        Request request = new Request.Builder()
                .url(source.url)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to fetch M3U: " + response.code());
            }
            // 同样支持 .m3u.gz：按魔数判断，不依赖 URL 后缀或 Content-Type
            byte[] raw;
            try (InputStream inputStream =
                         GzipAwareStreams.maybeDecompress(response.body().byteStream())) {
                raw = readFully(inputStream);
            }
            // 编码按「BOM > 响应声明 > UTF-8」判断，和原来 ResponseBody.string() 的行为一致。
            // 国内直播源有不少是 GBK 的，硬编码 UTF-8 会让频道名整片变成乱码
            MediaType contentType = response.body().contentType();
            String content = BomAwareText.decode(
                    raw, contentType != null ? contentType.charset() : null);
            M3UParser.ParseResult result = new M3UParser().parse(content);
            // 一个频道都没解析出来，多半是源站临时返回了空页面或错误页。照常写库会把本地缓存的频道
            // 全部删光，而播放页是先用缓存起播的，所以按刷新失败处理，保留原有频道
            if (result.channels == null || result.channels.isEmpty()) {
                throw new IOException("M3U contains no channels: " + source.url);
            }
            // channel_sources 是先删后插的：不放进事务的话，播放页刷新途中换台会读到空的播放源列表
            db.runInTransaction(() -> saveParsedSource(source, result));
            // M3U 头里的 x-tvg-url 已写回 source.epgUrl（或沿用了库里已有的地址），
            // 按节流策略后台刷新 EPG。下载很慢、且调用方可能在 UI 线程上，必须另起线程
            triggerEpgAutoRefresh(source.epgUrl);
            return result;
        }
    }

    /**
     * 按节流策略后台刷新 EPG，不阻塞调用方。
     *
     * <p>{@link EpgRepository#refreshIfStale(String, boolean)} 内部会判断 URL 是否变化、
     * 距上次成功是否满一小时，这里不再重复判断；无 EPG 地址或测试实例（epgRepository 为 null）时直接跳过。
     */
    private void triggerEpgAutoRefresh(String epgUrl) {
        if (epgRepository == null || epgUrl == null || epgUrl.isEmpty()) {
            return;
        }
        final String url = epgUrl;
        new Thread(() -> epgRepository.refreshIfStale(url, false), "witv-epg-auto-refresh").start();
    }

    private void saveParsedSource(M3USource source, M3UParser.ParseResult result) {
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

        // 只更新「直播源内」的 EPG 地址，用户自定义的地址和切换开关不动，再按开关重算生效地址。
        // 调用方手里的 source 可能是很久以前读的（播放页启动时缓存），直接整行 update 会把用户
        // 在 Web 管理页刚改的自定义地址冲掉，所以以库里的最新一行为准
        M3USource fresh = m3uSourceDao.getById(sourceId);
        M3USource target = fresh != null ? fresh : source;
        target.m3uEpgUrl = (result.epgUrl != null && !result.epgUrl.isEmpty()) ? result.epgUrl : null;
        target.applyEffectiveEpgUrl();
        m3uSourceDao.update(target);
        if (target != source) {
            source.m3uEpgUrl = target.m3uEpgUrl;
            source.customEpgUrl = target.customEpgUrl;
            source.useCustomEpg = target.useCustomEpg;
            source.epgUrl = target.epgUrl;
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
