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
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.SimpleCache;

import com.whyun.witv.WiTVApp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享播放器缓存，并对直播 playlist 尾部少量 ts 分片做有限并行预取。
 */
@OptIn(markerClass = UnstableApi.class)
public final class HlsSegmentPrefetcher {

    private static final String TAG = "HlsSegmentPrefetcher";
    private static final int PREFETCH_SEGMENT_COUNT = 3;
    private static final int PREFETCH_SKIP_TAIL_SEGMENT_COUNT = 1;
    private static final int PREFETCH_THREAD_COUNT = 2;
    private static final int LIVE_WINDOW_RETENTION_PLAYLIST_COUNT = 2;
    private static final int CACHE_BUFFER_BYTES = 32 * 1024;

    private final SimpleCache cache;
    private final DataSource.Factory upstreamFactory;
    private final CacheKeyFactory cacheKeyFactory = dataSpec -> buildCacheKey(dataSpec.uri);
    private final CacheDataSource.Factory downloadCacheDataSourceFactory;
    private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(
            PREFETCH_THREAD_COUNT,
            PREFETCH_THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());
    private final Set<String> queuedCacheKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> runningCacheKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Future<?>> prefetchFutures = new ConcurrentHashMap<>();
    private final Set<String> trackedLiveSegmentCacheKeys = ConcurrentHashMap.newKeySet();
    private final Object liveWindowCleanupLock = new Object();
    private final ArrayDeque<Set<String>> recentLiveWindowCacheKeys = new ArrayDeque<>();
    private final AtomicInteger playbackSourceGeneration = new AtomicInteger();

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

    public void onPlaybackSourceChanged(@NonNull Uri playbackUri) {
        int generation = playbackSourceGeneration.incrementAndGet();
        resetLiveWindowTracking();
        Log.d(TAG, "Playback source changed, cancel stale prefetch tasks: generation="
                + generation + ", uri=" + playbackUri);
        cancelAllPrefetchTasks();
    }

    public void prefetchPlaylistSegments(@NonNull Uri playlistUri, @NonNull String playlistText) {
        List<Uri> liveWindowSegmentUris = HlsSegmentPrefetchPlanner.extractSegmentUris(
                playlistUri, playlistText);
        evictStaleSegmentsOutsideLiveWindow(playlistUri, liveWindowSegmentUris);
        List<Uri> segmentUris = HlsSegmentPrefetchPlanner.planSegmentUris(
                liveWindowSegmentUris,
                PREFETCH_SEGMENT_COUNT,
                PREFETCH_SKIP_TAIL_SEGMENT_COUNT);
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

    private void resetLiveWindowTracking() {
        trackedLiveSegmentCacheKeys.clear();
        synchronized (liveWindowCleanupLock) {
            recentLiveWindowCacheKeys.clear();
        }
    }

    private void evictStaleSegmentsOutsideLiveWindow(@NonNull Uri playlistUri,
                                                     @NonNull List<Uri> liveWindowSegmentUris) {
        if (liveWindowSegmentUris.isEmpty()) {
            return;
        }

        Set<String> currentWindowCacheKeys = new LinkedHashSet<>();
        for (Uri liveWindowSegmentUri : liveWindowSegmentUris) {
            String cacheKey = buildCacheKey(liveWindowSegmentUri);
            if (cacheKey.isEmpty()) {
                continue;
            }
            currentWindowCacheKeys.add(cacheKey);
            trackedLiveSegmentCacheKeys.add(cacheKey);
        }
        if (currentWindowCacheKeys.isEmpty()) {
            return;
        }

        Set<String> retainedCacheKeys = new LinkedHashSet<>();
        synchronized (liveWindowCleanupLock) {
            recentLiveWindowCacheKeys.addLast(new LinkedHashSet<>(currentWindowCacheKeys));
            while (recentLiveWindowCacheKeys.size() > LIVE_WINDOW_RETENTION_PLAYLIST_COUNT) {
                recentLiveWindowCacheKeys.removeFirst();
            }
            for (Set<String> recentWindowCacheKeys : recentLiveWindowCacheKeys) {
                retainedCacheKeys.addAll(recentWindowCacheKeys);
            }
        }

        Log.d(TAG, "Live window cleanup scan: playlist=" + playlistUri
                + ", currentWindow=" + summarizeCacheKeys(currentWindowCacheKeys)
                + ", retainedWindow=" + summarizeCacheKeys(retainedCacheKeys)
                + ", currentSeqRange=" + summarizeSequenceRange(currentWindowCacheKeys)
                + ", retainedSeqRange=" + summarizeSequenceRange(retainedCacheKeys)
                + ", tracked=" + trackedLiveSegmentCacheKeys.size());

        int evictedCount = 0;
        List<String> evictedSegments = new ArrayList<>();
        List<String> busySegments = new ArrayList<>();
        Long currentWindowMinSequence = findMinSequence(currentWindowCacheKeys);
        for (String trackedCacheKey : new ArrayList<>(trackedLiveSegmentCacheKeys)) {
            if (retainedCacheKeys.contains(trackedCacheKey)) {
                continue;
            }
            if (queuedCacheKeys.contains(trackedCacheKey) || runningCacheKeys.contains(trackedCacheKey)) {
                busySegments.add(describeSegmentDistance(trackedCacheKey, currentWindowMinSequence));
                continue;
            }
            if (!isAlreadyCached(trackedCacheKey)) {
                trackedLiveSegmentCacheKeys.remove(trackedCacheKey);
                continue;
            }
            try {
                cache.removeResource(trackedCacheKey);
                trackedLiveSegmentCacheKeys.remove(trackedCacheKey);
                evictedCount++;
                String segmentDescription = describeSegmentDistance(trackedCacheKey, currentWindowMinSequence);
                evictedSegments.add(segmentDescription);
                Log.d(TAG, "Evict stale segment outside live window: " + trackedCacheKey
                        + ", " + describeSequenceDelta(trackedCacheKey, currentWindowMinSequence));
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to evict stale live segment: " + trackedCacheKey, e);
            }
        }
        if (!busySegments.isEmpty()) {
            Log.d(TAG, "Skip stale segment eviction because prefetch is busy: count="
                    + busySegments.size() + ", segments=" + summarizeSegmentLabels(busySegments));
        }
        if (evictedCount > 0) {
            Log.d(TAG, "Evicted " + evictedCount + " stale segment(s) outside live window for playlist: "
                    + playlistUri + ", segments=" + summarizeSegmentLabels(evictedSegments));
        }
    }

    @NonNull
    private static String summarizeCacheKeys(@NonNull Set<String> cacheKeys) {
        if (cacheKeys.isEmpty()) {
            return "empty";
        }
        String first = null;
        String last = null;
        int count = 0;
        for (String cacheKey : cacheKeys) {
            if (first == null) {
                first = compactSegmentLabel(cacheKey);
            }
            last = compactSegmentLabel(cacheKey);
            count++;
        }
        if (count == 1) {
            return "count=1 [" + first + "]";
        }
        return "count=" + count + " [" + first + " .. " + last + "]";
    }

    @NonNull
    private static String summarizeSequenceRange(@NonNull Set<String> cacheKeys) {
        Long minSequence = null;
        Long maxSequence = null;
        for (String cacheKey : cacheKeys) {
            Long sequence = extractSegmentSequence(cacheKey);
            if (sequence == null) {
                continue;
            }
            if (minSequence == null || sequence < minSequence) {
                minSequence = sequence;
            }
            if (maxSequence == null || sequence > maxSequence) {
                maxSequence = sequence;
            }
        }
        if (minSequence == null || maxSequence == null) {
            return "unknown";
        }
        if (minSequence.equals(maxSequence)) {
            return String.valueOf(minSequence);
        }
        return minSequence + ".." + maxSequence;
    }

    @NonNull
    private static String summarizeSegmentLabels(@NonNull List<String> segmentLabels) {
        if (segmentLabels.isEmpty()) {
            return "[]";
        }
        int limit = Math.min(4, segmentLabels.size());
        List<String> visible = new ArrayList<>(segmentLabels.subList(0, limit));
        if (segmentLabels.size() > limit) {
            visible.add("... +" + (segmentLabels.size() - limit) + " more");
        }
        return visible.toString();
    }

    @NonNull
    private static String compactSegmentLabel(@NonNull String cacheKey) {
        int slashIndex = cacheKey.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < cacheKey.length()) {
            return cacheKey.substring(slashIndex + 1);
        }
        return cacheKey;
    }

    @NonNull
    private static String describeSegmentDistance(@NonNull String cacheKey, Long currentWindowMinSequence) {
        return compactSegmentLabel(cacheKey) + " (" + describeSequenceDelta(cacheKey, currentWindowMinSequence) + ")";
    }

    @NonNull
    private static String describeSequenceDelta(@NonNull String cacheKey, Long currentWindowMinSequence) {
        Long sequence = extractSegmentSequence(cacheKey);
        if (sequence == null || currentWindowMinSequence == null) {
            return "olderBy=unknown";
        }
        long delta = currentWindowMinSequence - sequence;
        if (delta > 0) {
            return "olderBy=" + delta;
        }
        if (delta == 0) {
            return "olderBy=0";
        }
        return "aheadBy=" + (-delta);
    }

    private static Long findMinSequence(@NonNull Set<String> cacheKeys) {
        Long minSequence = null;
        for (String cacheKey : cacheKeys) {
            Long sequence = extractSegmentSequence(cacheKey);
            if (sequence == null) {
                continue;
            }
            if (minSequence == null || sequence < minSequence) {
                minSequence = sequence;
            }
        }
        return minSequence;
    }

    private static Long extractSegmentSequence(@NonNull String cacheKey) {
        String label = compactSegmentLabel(cacheKey);
        int queryIndex = label.indexOf('?');
        String pathPart = queryIndex >= 0 ? label.substring(0, queryIndex) : label;
        int dotTsIndex = pathPart.toLowerCase().lastIndexOf(".ts");
        String withoutSuffix = dotTsIndex >= 0 ? pathPart.substring(0, dotTsIndex) : pathPart;
        int underscoreIndex = withoutSuffix.lastIndexOf('_');
        if (underscoreIndex < 0 || underscoreIndex + 1 >= withoutSuffix.length()) {
            return null;
        }
        String numericPart = withoutSuffix.substring(underscoreIndex + 1);
        try {
            return Long.parseLong(numericPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void schedulePrefetch(@NonNull Uri segmentUri) {
        String cacheKey = buildCacheKey(segmentUri);
        int generation = playbackSourceGeneration.get();
        if (cacheKey.isEmpty()) {
            Log.d(TAG, "Skip prefetch because cache key is empty: " + segmentUri);
            return;
        }
        if (isAlreadyCached(cacheKey)) {
            Log.d(TAG, "Skip prefetch because segment already cached: " + segmentUri);
            return;
        }
        if (runningCacheKeys.contains(cacheKey)) {
            Log.d(TAG, "Skip prefetch because segment already downloading: " + segmentUri);
            return;
        }
        if (!queuedCacheKeys.add(cacheKey)) {
            Log.d(TAG, "Skip prefetch because segment already queued: " + segmentUri);
            return;
        }
        Future<?> future = executorService.submit(() -> runPrefetch(cacheKey, segmentUri, generation));
        prefetchFutures.put(cacheKey, future);
    }

    private void runPrefetch(@NonNull String cacheKey, @NonNull Uri segmentUri, int generation) {
        try {
            if (!queuedCacheKeys.remove(cacheKey)) {
                Log.d(TAG, "Prefetch aborted before start because queue entry was cleared: "
                        + segmentUri);
                return;
            }
            if (generation != playbackSourceGeneration.get()) {
                Log.d(TAG, "Skip queued prefetch after source changed: " + segmentUri);
                return;
            }
            if (!runningCacheKeys.add(cacheKey)) {
                Log.d(TAG, "Skip prefetch because segment already downloading: " + segmentUri);
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.d(TAG, "Prefetch cancelled before start: " + segmentUri);
                return;
            }
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
            if (generation != playbackSourceGeneration.get()) {
                Log.d(TAG, "Prefetch finished for stale source, ignore result: " + segmentUri);
                return;
            }
            Log.d(TAG, "Prefetch success: " + segmentUri);
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || isCancellationException(e)) {
                Log.d(TAG, "Prefetch cancelled: " + segmentUri + ", reason="
                        + cancellationReasonName(e));
                return;
            }
            Log.d(TAG, "Prefetch failed: " + segmentUri, e);
        } finally {
            queuedCacheKeys.remove(cacheKey);
            runningCacheKeys.remove(cacheKey);
            prefetchFutures.remove(cacheKey);
        }
    }

    private boolean cancelQueuedPrefetch(@NonNull String cacheKey, @NonNull Uri segmentUri) {
        if (!queuedCacheKeys.remove(cacheKey)) {
            return false;
        }
        Future<?> future = prefetchFutures.get(cacheKey);
        boolean cancelled = future != null && future.cancel(false);
        if (cancelled) {
            Log.d(TAG, "Cancel queued prefetch because playback needs segment now: " + segmentUri);
            prefetchFutures.remove(cacheKey, future);
        } else {
            Log.d(TAG, "Playback claimed queued segment before prefetch thread started: "
                    + segmentUri);
        }
        return true;
    }

    private static boolean isCancellationException(@NonNull Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InterruptedIOException
                    || cursor instanceof InterruptedException
                    || cursor instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    @NonNull
    private static String cancellationReasonName(@NonNull Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InterruptedIOException) {
                return "INTERRUPTED_IO";
            }
            if (cursor instanceof InterruptedException) {
                return "INTERRUPTED";
            }
            if (cursor instanceof java.util.concurrent.CancellationException) {
                return "FUTURE_CANCELLED";
            }
            cursor = cursor.getCause();
        }
        return "INTERRUPTED";
    }

    private void cancelAllPrefetchTasks() {
        for (Future<?> future : prefetchFutures.values()) {
            future.cancel(true);
        }
        prefetchFutures.clear();
        queuedCacheKeys.clear();
        runningCacheKeys.clear();
        executorService.getQueue().clear();
        executorService.purge();
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
        CacheDataSource cacheDataSource = new CacheDataSource(
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
        return new LoggingPlaybackDataSource(cacheDataSource);
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
        resetLiveWindowTracking();
        cancelAllPrefetchTasks();
        executorService.shutdownNow();
    }

    private final class LoggingPlaybackDataSource implements DataSource {

        private final CacheDataSource delegate;

        private LoggingPlaybackDataSource(@NonNull CacheDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            delegate.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            String cacheKey = buildCacheKey(dataSpec.uri);
            boolean cachedBeforeOpen = !cacheKey.isEmpty() && isAlreadyCached(cacheKey);
            boolean prefetchedQueued = !cacheKey.isEmpty() && queuedCacheKeys.contains(cacheKey);
            boolean prefetchedRunning = !cacheKey.isEmpty() && runningCacheKeys.contains(cacheKey);
            if (cachedBeforeOpen) {
                Log.d(TAG, "Playback open with cached span ready: " + dataSpec.uri);
            } else if (prefetchedRunning) {
                Log.d(TAG, "Playback open while prefetch still in-flight, fallback may use upstream: "
                        + dataSpec.uri);
            } else if (prefetchedQueued) {
                boolean cancelledQueuedPrefetch = cancelQueuedPrefetch(cacheKey, dataSpec.uri);
                if (cancelledQueuedPrefetch) {
                    Log.d(TAG, "Playback open before queued prefetch started, cancelled queued prefetch and use upstream: "
                            + dataSpec.uri);
                } else if (runningCacheKeys.contains(cacheKey)) {
                    Log.d(TAG, "Playback open raced queued->running prefetch, fallback may use upstream: "
                            + dataSpec.uri);
                } else {
                    Log.d(TAG, "Playback open before queued prefetch started, queue state already changed: "
                            + dataSpec.uri);
                }
            } else {
                Log.d(TAG, "Playback open without cache hit: " + dataSpec.uri);
            }
            return delegate.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return delegate.read(buffer, offset, readLength);
        }

        @Override
        public Uri getUri() {
            return delegate.getUri();
        }

        @Override
        @NonNull
        public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
