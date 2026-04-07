package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.SimpleCache;

import com.whyun.witv.WiTVApp;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 共享播放器缓存，并对直播 playlist 尾部少量 ts 分片做有限并行预取。
 */
@OptIn(markerClass = UnstableApi.class)
public final class HlsSegmentPrefetcher {

    private static final String TAG = "HlsSegmentPrefetcher";
    private static final int PREFETCH_SEGMENT_COUNT = 2;
    private static final int PREFETCH_THREAD_COUNT = 2;
    private static final int CACHE_BUFFER_BYTES = 32 * 1024;

    private final SimpleCache cache;
    private final DataSource.Factory upstreamFactory;
    private final CacheKeyFactory cacheKeyFactory = dataSpec -> buildCacheKey(dataSpec.uri);
    private final CacheDataSource.Factory downloadCacheDataSourceFactory;
    private final ExecutorService executorService =
            Executors.newFixedThreadPool(PREFETCH_THREAD_COUNT);
    private final Set<String> inFlightCacheKeys = ConcurrentHashMap.newKeySet();

    public HlsSegmentPrefetcher(@NonNull Context context, @NonNull DataSource.Factory upstreamFactory) {
        WiTVApp app = (WiTVApp) context.getApplicationContext();
        cache = app.getOrCreateMediaCache();
        this.upstreamFactory = upstreamFactory;
        downloadCacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory(cacheKeyFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    @NonNull
    public DataSource.Factory getPlaybackDataSourceFactory() {
        return this::createPlaybackCacheDataSource;
    }

    public void prefetchPlaylistSegments(@NonNull Uri playlistUri, @NonNull String playlistText) {
        List<Uri> segmentUris = HlsSegmentPrefetchPlanner.planSegmentUris(
                playlistUri, playlistText, PREFETCH_SEGMENT_COUNT);
        if (segmentUris.isEmpty()) {
            Log.d(TAG, "No ts segments planned for playlist: " + playlistUri);
            return;
        }
        Log.d(TAG, "Planned " + segmentUris.size() + " segment(s) for playlist: "
                + playlistUri + " -> " + segmentUris);
        for (Uri segmentUri : segmentUris) {
            schedulePrefetch(segmentUri);
        }
    }

    private void schedulePrefetch(@NonNull Uri segmentUri) {
        String cacheKey = buildCacheKey(segmentUri);
        if (cacheKey.isEmpty()) {
            Log.d(TAG, "Skip prefetch because cache key is empty: " + segmentUri);
            return;
        }
        if (isAlreadyCached(cacheKey)) {
            Log.d(TAG, "Skip prefetch because segment already cached: " + segmentUri);
            return;
        }
        if (!inFlightCacheKeys.add(cacheKey)) {
            Log.d(TAG, "Skip prefetch because segment already in-flight: " + segmentUri);
            return;
        }
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Prefetch start: " + segmentUri);
                DataSpec dataSpec = new DataSpec.Builder()
                        .setUri(segmentUri)
                        .setKey(cacheKey)
                        .build();
                CacheWriter cacheWriter = new CacheWriter(
                        downloadCacheDataSourceFactory.createDataSourceForDownloading(),
                        dataSpec,
                        new byte[CACHE_BUFFER_BYTES],
                        null);
                cacheWriter.cache();
                Log.d(TAG, "Prefetch success: " + segmentUri);
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "Prefetch failed: " + segmentUri, e);
            } finally {
                inFlightCacheKeys.remove(cacheKey);
            }
        });
    }

    private boolean isAlreadyCached(@NonNull String cacheKey) {
        return !cache.getCachedSpans(cacheKey).isEmpty();
    }

    @NonNull
    private static String buildCacheKey(@NonNull Uri uri) {
        return uri.toString();
    }

    @NonNull
    private DataSource createPlaybackCacheDataSource() {
        return new CacheDataSource(
                cache,
                upstreamFactory.createDataSource(),
                new FileDataSource(),
                new CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE),
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
                new CacheDataSource.EventListener() {
                    @Override
                    public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
                        if (cachedBytesRead > 0) {
                            Log.d(TAG, "Playback cache hit: read " + cachedBytesRead
                                    + " byte(s) from cache, cacheSize=" + cacheSizeBytes);
                        }
                    }

                    @Override
                    public void onCacheIgnored(int reason) {
                        Log.d(TAG, "Playback cache ignored, reason=" + cacheIgnoredReasonName(reason));
                    }
                },
                cacheKeyFactory);
    }

    @NonNull
    private static String cacheIgnoredReasonName(int reason) {
        switch (reason) {
            case CacheDataSource.CACHE_IGNORED_REASON_ERROR:
                return "CACHE_ERROR";
            case CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH:
                return "UNSET_LENGTH";
            default:
                return "UNKNOWN(" + reason + ")";
        }
    }

    public void release() {
        executorService.shutdownNow();
        inFlightCacheKeys.clear();
    }
}
