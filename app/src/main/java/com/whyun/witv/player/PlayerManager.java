package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.PlayerView;

import androidx.media3.exoplayer.util.EventLogger;

import com.whyun.witv.BuildConfig;
import com.whyun.witv.WiTVApp;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.entity.ChannelSource;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class PlayerManager {

    private static final String TAG = "PlayerManager";

    public interface Callback {
        void onSourceSwitching(int newIndex, int total);
        void onAllSourcesFailed();
        void onPlaybackStarted(int sourceIndex, int total);
        void onError(String message);
    }

    /**
     * 直播 HLS：主动离 live edge 更远一些，用额外延迟换取更高的抗抖动能力。
     */
    private static final long LIVE_TARGET_OFFSET_MS = 18_000L;
    /** 不允许贴边过近（与 target 配合，减少 BehindLiveWindow 与轻微丢包导致的卡顿）。 */
    private static final long LIVE_MIN_OFFSET_MS = 12_000L;
    /** 允许播放器在网络更差时继续后退，优先保证不断流。 */
    private static final long LIVE_MAX_OFFSET_MS = 30_000L;

    private static final int HTTP_CONNECT_TIMEOUT_MS = 12_000;
    private static final int HTTP_READ_TIMEOUT_MS = 45_000;
    private static final int PREFETCH_HTTP_CONNECT_TIMEOUT_MS = 4_000;
    private static final int PREFETCH_HTTP_READ_TIMEOUT_MS = 8_000;

    /** 固定 UA，避免部分 IPTV 源对默认 ExoPlayer/Media3 特征敏感。 */
    private static final String HTTP_USER_AGENT = "stagefright/1.2 (Linux;Android 7.1.2)";

    /**
     * Microsoft Smooth Streaming 发布点路径形态（与 {@code androidx.media3.common.util.Util} 中
     * ISM 规则一致），用于在无 {@code .mpd}/{@code .m3u8} 后缀时识别 SS Manifest。
     */
    private static final Pattern SMOOTH_STREAMING_PATH_PATTERN = Pattern.compile(
            "(?:.*\\.)?isml?(?:/(manifest(.*))?)?", Pattern.CASE_INSENSITIVE);

    /**
     * 是否为「落后于直播窗口」类错误（见 Media3 直播文档：应 seekToDefaultPosition 而非换源）。
     */
    static boolean isBehindLiveWindowError(@NonNull PlaybackException error) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            return true;
        }
        Throwable c = error.getCause();
        while (c != null) {
            if (c instanceof BehindLiveWindowException) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    private final Context context;
    private ExoPlayer player;
    @Nullable
    private HlsSegmentPrefetcher hlsSegmentPrefetcher;
    @Nullable
    private SwitchableLoadControl loadControl;
    @Nullable
    private MulticastLockHolder multicastLockHolder;
    @Nullable
    private PreferenceManager preferenceManager;
    /** 当前 ExoPlayer 实例构建时采用的解码方式 */
    @Nullable
    private PlaybackDecoderMode activeDecoderMode;
    private PlayerView playerView;
    private Callback callback;

    private List<ChannelSource> currentSources = new ArrayList<>();
    private int currentSourceIndex = 0;
    /** 当前线路经规范化/udpxy 改写后真正交给播放器的地址 */
    @Nullable
    private String currentResolvedUrl;
    private boolean isRetrying = false;
    private int playGeneration = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    /** 起播成功后又卡在缓冲的看门狗（见 {@link #onRebufferStall}） */
    private Runnable rebufferWatchdogRunnable;
    /** 连续「卡住→原地重开」的次数，用尽后才换源 */
    private int consecutiveStallRecoveries;
    /**
     * 当前线路是否已经成功起播过。没起播过的缓冲由起播超时（{@link #startTimeout}）看管，
     * 两者不能同时武装，否则同一时刻会各做一次动作。
     */
    private boolean hasStartedPlayback;
    /** 当前线路起播后又回到缓冲的次数 */
    private int rebufferCount;
    /** 最近一次进入 READY 的时刻，用于算「撑了多久就又卡了」 */
    private long lastReadyAtMs;

    /**
     * 原地重开几次仍救不回来才换源。组播只有一条线路时换源等于放弃，所以优先原地恢复。
     */
    static final int MAX_STALL_RECOVERIES = 2;

    /**
     * 播放持续这么久才算「真正恢复」，恢复预算才会重置。
     *
     * <p>不能在收到 READY 时就无条件重置：坏源常见的形态正是「重开→READY 几十毫秒→又卡」，
     * 那样每次看门狗都被当成第一次尝试，{@link #MAX_STALL_RECOVERIES} 永远到不了，
     * 播放器会无限重开同一条坏线路而不换源。
     */
    static final long STALL_RECOVERY_RESET_AFTER_MS = 30_000L;

    private static String playbackStateName(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "IDLE";
            case Player.STATE_BUFFERING:
                return "BUFFERING";
            case Player.STATE_READY:
                return "READY";
            case Player.STATE_ENDED:
                return "ENDED";
            default:
                return String.valueOf(state);
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (player == null || player.getPlaybackState() != playbackState) {
                Log.w(TAG, "Ignoring stale state callback");
                return;
            }
            if (playbackState == Player.STATE_READY) {
                onPlaybackReady();
            } else if (playbackState == Player.STATE_ENDED) {
                cancelTimeout();
                cancelRebufferWatchdog();
            } else if (playbackState == Player.STATE_BUFFERING) {
                onEnteredBuffering();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (player == null || player.getPlayerError() != error) {
                Log.w(TAG, "Ignoring stale error callback from previous channel");
                return;
            }
            cancelTimeout();
            cancelRebufferWatchdog();

            if (isBehindLiveWindowError(error)) {
                String url = currentSourceIndex < currentSources.size()
                        ? currentSources.get(currentSourceIndex).url : "unknown";
                Log.w(TAG, String.format(Locale.US,
                        "Behind live window — seekToDefaultPosition + prepare (same source %d/%d): %s",
                        currentSourceIndex + 1, currentSources.size(), url));
                player.seekToDefaultPosition();
                player.prepare();
                player.setPlayWhenReady(true);
                startTimeout();
                return;
            }

            String url = currentSourceIndex < currentSources.size()
                    ? currentSources.get(currentSourceIndex).url : "unknown";
            Log.e(TAG, String.format(Locale.US,
                    "Playback error on source %d/%d [%s]: %s",
                    currentSourceIndex + 1, currentSources.size(), url, error.getMessage()));
            String reason = String.format(Locale.US,
                    "playback_error: %s | %s",
                    error.getErrorCodeName(),
                    error.getMessage() != null ? error.getMessage() : "(no message)");
            Throwable cause = error.getCause();
            if (cause != null) {
                reason += " | cause: " + cause.getClass().getSimpleName()
                        + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
            }
            switchToNextSource(reason);
        }
    };

    /** 进入 {@code STATE_READY}。注意这里**不**重置恢复预算，见 {@link #STALL_RECOVERY_RESET_AFTER_MS}。 */
    void onPlaybackReady() {
        cancelTimeout();
        cancelRebufferWatchdog();
        hasStartedPlayback = true;
        lastReadyAtMs = SystemClock.elapsedRealtime();
        isRetrying = false;
        Log.i(TAG, "Playback started, source index: " + currentSourceIndex);
        if (callback != null) {
            callback.onPlaybackStarted(currentSourceIndex, currentSources.size());
        }
    }

    /** 进入 {@code STATE_BUFFERING}。 */
    void onEnteredBuffering() {
        if (hasStartedPlayback) {
            long playedMs = SystemClock.elapsedRealtime() - lastReadyAtMs;
            rebufferCount++;
            if (playedMs >= STALL_RECOVERY_RESET_AFTER_MS) {
                // 稳定播放了足够久，这次卡顿与之前的无关，重新给满恢复预算
                consecutiveStallRecoveries = 0;
            }
            // 「起播后几十毫秒就又卡」说明不是数据不够，而是渲染器 ready 不了
            // （解码跟不上实时等），和「缓冲慢慢耗干」是两种完全不同的故障。
            Log.w(TAG, String.format(Locale.US,
                    "Rebuffer #%d on source %d/%d after only %dms of playback",
                    rebufferCount, currentSourceIndex + 1, currentSources.size(), playedMs));
        } else {
            Log.d(TAG, "Buffering...");
        }
        // 起播超时在 READY 时就被取消了，之后再卡住本来无人看管：
        // 数据还在进但播放器消化不动（TS 时间戳错乱等）会无限停在这里。
        startRebufferWatchdog();
    }

    public PlayerManager(Context context) {
        this.context = context;
    }

    /** 懒初始化：测试会绕过 {@link #initialize} 直接注入播放器。 */
    private PreferenceManager preferenceManager() {
        if (preferenceManager == null) {
            preferenceManager = new PreferenceManager(context);
        }
        return preferenceManager;
    }

    @OptIn(markerClass = UnstableApi.class)
    public void initialize(PlayerView playerView) {
        this.playerView = playerView;
        PreferenceManager preferenceManager = preferenceManager();

        // HLS 直播与 UDP/RTP 组播的缓冲取舍相反，用一个可切换的 LoadControl 承载两套参数，
        // 换源时按地址切换，无需重建 ExoPlayer。
        SwitchableLoadControl loadControl = new SwitchableLoadControl();
        this.loadControl = loadControl;

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
                .setUserAgent(HTTP_USER_AGENT);
        DefaultDataSource.Factory networkDataSourceFactory =
                new DefaultDataSource.Factory(context, httpDataSourceFactory);
        DefaultHttpDataSource.Factory prefetchHttpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(PREFETCH_HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(PREFETCH_HTTP_READ_TIMEOUT_MS)
                .setUserAgent(HTTP_USER_AGENT);
        DefaultDataSource.Factory prefetchNetworkDataSourceFactory =
                new DefaultDataSource.Factory(context, prefetchHttpDataSourceFactory);
        boolean useDiskCacheForLiveTs = preferenceManager.isUseDiskCacheForLiveTsEnabled();
        hlsSegmentPrefetcher = useDiskCacheForLiveTs
                ? new HlsSegmentPrefetcher(context, prefetchNetworkDataSourceFactory)
                : null;
        if (useDiskCacheForLiveTs) {
            Log.i(TAG, "Live ts disk cache is enabled");
        } else {
            Log.i(TAG, "Live ts disk cache is disabled; keep m3u8 rewrite only");
        }
        DataSource.Factory mediaDataSourceFactory = hlsSegmentPrefetcher != null
                ? hlsSegmentPrefetcher.getPlaybackDataSourceFactory()
                : networkDataSourceFactory;
        DefaultDataSource.Factory httpChainFactory = new DefaultDataSource.Factory(context,
                new M3u8RewritingDataSource.Factory(
                        networkDataSourceFactory,
                        mediaDataSourceFactory,
                        hlsSegmentPrefetcher));

        // udp:// / rtp:// 由自建组播数据源接管（可配 SO_RCVBUF、按活动网卡 join、RTP 剥头重排），
        // 其余 scheme 仍走上面的 HTTP/HLS 链路。
        multicastLockHolder = new MulticastLockHolder(context);
        DataSource.Factory dataSourceFactory = new MulticastAwareDataSourceFactory(
                httpChainFactory,
                new MulticastDataSource.Factory(context, /* rtpMode= */ false, multicastLockHolder),
                new MulticastDataSource.Factory(context, /* rtpMode= */ true, multicastLockHolder));

        // 直播 TS 调参只给 udp:// / rtp:// 用：单节目模式起播更快、允许非 IDR 关键帧，
        // 但 MODE_SINGLE_PMT 的「只有一个 PMT」假定对 HTTP 上来源不明的 TS 并不安全。
        // HLS 走的是 DefaultHlsExtractorFactory，两档都不影响它。
        ExtractorsFactory extractorsFactory = new MulticastAwareExtractorsFactory(
                new DefaultExtractorsFactory()
                        .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                        .setTsExtractorFlags(
                                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES),
                new DefaultExtractorsFactory());

        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);

        DefaultBandwidthMeter bandwidthMeter = WiTVApp.getInstance().getOrCreateBandwidthMeter();
        player = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(buildRenderersFactory(preferenceManager))
                .build();

        playerView.setPlayer(player);
        player.addListener(playerListener);
        if (BuildConfig.DEBUG) {
            // 掉帧数、解码器初始化、状态变化原因、带宽——排查「解码跟不上实时」所需的信息
            // 只有官方 EventLogger 有。日志很吵，所以只在 debug 构建挂。
            player.addAnalyticsListener(new EventLogger(TAG + "-ev"));
        }
    }

    /**
     * 组装渲染器工厂：在平台 MediaCodec 之外挂上 NextLib 的 FFmpeg 软解。
     *
     * <p>组播 TS 常见的 MPEG-2 视频与 MP2/AC3 音频，不少电视盒子的硬解不支持或实现有问题，
     * 没有软解兜底就是黑屏或无声。
     */
    @OptIn(markerClass = UnstableApi.class)
    private RenderersFactory buildRenderersFactory(PreferenceManager preferenceManager) {
        PlaybackDecoderMode mode = preferenceManager.getPlaybackDecoderMode();
        activeDecoderMode = mode;
        Log.i(TAG, String.format(Locale.US,
                "Decoder mode: %s (extensionRendererMode=%d), ffmpeg=%s",
                mode.getId(), mode.getExtensionRendererMode(), ffmpegLibrarySummary()));
        return new NextRenderersFactory(context)
                .setExtensionRendererMode(mode.getExtensionRendererMode())
                // 某个 MediaCodec 解码器 configure 失败时，依次尝试同一渲染器里的其它
                // MediaCodec 解码器（例如 c2.android.avc.decoder 失败后试 c2.qti.avc.decoder）。
                // 注意它**不会**退到 FFmpeg 渲染器——渲染器早在 supportsFormat 阶段就选定了；
                // 硬解整体不可用时请用「软解优先」档。
                .setEnableDecoderFallback(true);
    }

    private static String ffmpegLibrarySummary() {
        try {
            if (!FfmpegLibrary.isAvailable()) {
                return "unavailable";
            }
            String version = FfmpegLibrary.getVersion();
            return version != null ? version : "available";
        } catch (Throwable t) {
            // 原生库缺失/ABI 不匹配时不应让播放器构建失败
            return "load-failed: " + t.getClass().getSimpleName();
        }
    }

    /** 构建播放器时生效的解码方式；未初始化时为 null。 */
    @Nullable
    public PlaybackDecoderMode getActiveDecoderMode() {
        return activeDecoderMode;
    }

    /**
     * 解码方式变更后重建播放器并续播当前频道。
     *
     * <p>{@code RenderersFactory} 只能在 {@code ExoPlayer} 构建时指定，之后改不了，所以必须重建。
     * 调用方需要在前后重新挂载自己加在 {@code ExoPlayer} 上的监听器
     * （见 {@code PlayerActivity.onPlaybackDecoderModeChanged}）。
     *
     * @return 是否真的重建了（未初始化过则返回 false）
     */
    public boolean reinitializeForDecoderModeChange() {
        if (playerView == null) {
            return false;
        }
        List<ChannelSource> sources = new ArrayList<>(currentSources);
        int index = currentSourceIndex;

        cancelTimeout();
        // 让旧播放器的残留回调失效
        playGeneration++;
        if (hlsSegmentPrefetcher != null) {
            hlsSegmentPrefetcher.release();
            hlsSegmentPrefetcher = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }

        initialize(playerView);

        if (!sources.isEmpty()) {
            currentSources = sources;
            currentSourceIndex = Math.max(0, Math.min(index, sources.size() - 1));
            isRetrying = false;
            playCurrentSource();
        }
        return true;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void playChannel(List<ChannelSource> sources) {
        playGeneration++;
        cancelTimeout();
        cancelRebufferWatchdog();
        consecutiveStallRecoveries = 0;
        hasStartedPlayback = false;
        rebufferCount = 0;
        stopPlayer();

        if (sources == null || sources.isEmpty()) {
            Log.w(TAG, "No sources available for channel");
            if (callback != null) callback.onAllSourcesFailed();
            return;
        }
        Log.i(TAG, "Playing channel with " + sources.size() + " source(s)");
        currentSources = new ArrayList<>(sources);
        currentSourceIndex = 0;
        isRetrying = false;
        playCurrentSource();
    }

    private void playCurrentSource() {
        if (currentSourceIndex >= currentSources.size()) {
            isRetrying = false;
            currentResolvedUrl = null;
            if (hlsSegmentPrefetcher != null) {
                hlsSegmentPrefetcher.onPlaybackSourceChanged(Uri.EMPTY);
            }
            stopPlayer();
            Log.e(TAG, "All sources failed");
            if (callback != null) callback.onAllSourcesFailed();
            return;
        }

        if (isRetrying && callback != null) {
            callback.onSourceSwitching(currentSourceIndex, currentSources.size());
        }

        String rawUrl = currentSources.get(currentSourceIndex).url;
        // 组播地址先规范化（去掉 VLC 风格的 @ / SSM 源地址段），配了 udpxy 则改写成 HTTP 单播
        boolean multicastOrigin = MulticastUrlUtil.isMulticastStreamUrl(rawUrl);
        String url = MulticastUrlUtil.resolvePlaybackUrl(rawUrl, preferenceManager().getUdpxyProxyBase());
        currentResolvedUrl = url;
        if (multicastOrigin && !url.equals(rawUrl)) {
            Log.i(TAG, String.format(Locale.US, "Trying source %d/%d: %s (via udpxy: %s)",
                    currentSourceIndex + 1, currentSources.size(), rawUrl, url));
        } else {
            Log.i(TAG, String.format(Locale.US, "Trying source %d/%d: %s",
                    currentSourceIndex + 1, currentSources.size(), url));
        }
        Uri uri = Uri.parse(url);
        if (hlsSegmentPrefetcher != null) {
            hlsSegmentPrefetcher.onPlaybackSourceChanged(uri);
        }

        hasStartedPlayback = false;
        rebufferCount = 0;
        cancelRebufferWatchdog();
        player.stop();
        player.clearMediaItems();

        // 组播（含经 udpxy 代理的）是实时流，没有可回拉的服务端缓冲，必须用低延迟缓冲档；
        // 必须在 prepare() 之前切换。
        if (loadControl != null) {
            loadControl.setProfile(multicastOrigin
                    ? SwitchableLoadControl.Profile.MULTICAST
                    : SwitchableLoadControl.Profile.STREAMING);
        }

        MediaItem mediaItem = buildMediaItem(url, multicastOrigin);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
        startTimeout();
    }

    /**
     * @param url             已规范化（可能已改写为 udpxy 地址）的播放地址
     * @param multicastOrigin 原始地址是否为 {@code udp://}/{@code rtp://}；经 udpxy 代理后 scheme
     *                        变成 http 但载荷仍是裸 MPEG-TS，必须据此显式指定容器类型
     */
    private MediaItem buildMediaItem(String url, boolean multicastOrigin) {
        Uri uri = Uri.parse(url);
        String lowerUrl = url.toLowerCase(Locale.US);
        @Nullable String scheme = uri.getScheme();
        String lowerScheme = scheme != null ? scheme.toLowerCase(Locale.US) : "";

        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);

        // RTSP：由 media3-exoplayer-rtsp 处理（scheme 推断为 TYPE_RTSP）。
        if ("rtsp".equals(lowerScheme)) {
            return builder.build();
        }
        // RTMP：RtmpDataSource（media3-datasource-rtmp）+ 渐进式容器（常见为 FLV）。
        if ("rtmp".equals(lowerScheme)) {
            return builder.build();
        }
        // UDP/RTP 组播：地址没有扩展名，靠嗅探识别 TS 会拖慢起播，直接指定 MPEG-TS。
        if (multicastOrigin) {
            builder.setMimeType(MimeTypes.VIDEO_MP2T);
            return builder.build();
        }

        if (lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/")
                || lowerUrl.contains("type=m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
            MediaItem.LiveConfiguration liveConfig = new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                    .build();
            builder.setLiveConfiguration(liveConfig);
        } else if (lowerUrl.contains(".mpd") || lowerUrl.contains("/dash/")
                || lowerUrl.contains("type=mpd")
                || lowerUrl.contains("application/dash+xml")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        } else if (isSmoothStreamingUrl(uri, lowerUrl)) {
            builder.setMimeType(MimeTypes.APPLICATION_SS);
        } else if (lowerUrl.endsWith(".ts") || lowerUrl.contains(".ts?")) {
            builder.setMimeType(MimeTypes.VIDEO_MP2T);
        }
        // Otherwise let ExoPlayer auto-detect

        return builder.build();
    }

    /**
     * 是否为经典 Smooth Streaming（.ism / .isml）Manifest；排除同一发布点上 HLS/DASH 别名
     * （{@code format=m3u8-aapl}、{@code format=mpd-time-csf}）。
     */
    private static boolean isSmoothStreamingUrl(Uri uri, String lowerUrl) {
        if (lowerUrl.contains("format=m3u8-aapl") || lowerUrl.contains("format=mpd-time-csf")) {
            return false;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return SMOOTH_STREAMING_PATH_PATTERN.matcher(path).matches();
    }

    /**
     * 放弃当前源并尝试下一个。调用前须已确定需要换源（错误、超时等）。
     *
     * @param reason 换源原因，写入日志便于排查
     */
    private void switchToNextSource(@NonNull String reason) {
        int failedIndex = currentSourceIndex;
        String failedUrl = failedIndex >= 0 && failedIndex < currentSources.size()
                ? currentSources.get(failedIndex).url
                : "unknown";
        Log.w(TAG, String.format(Locale.US,
                "Switching source — reason: %s | failedSource: %d/%d | url: %s",
                reason, failedIndex + 1, currentSources.size(), failedUrl));
        isRetrying = true;
        // 换到另一条线路，恢复预算重新给满
        consecutiveStallRecoveries = 0;
        currentSourceIndex++;
        playCurrentSource();
    }

    private void stopPlayer() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }

    public void manualSwitchSource(int index) {
        if (index >= 0 && index < currentSources.size()) {
            consecutiveStallRecoveries = 0;
            String url = currentSources.get(index).url;
            Log.i(TAG, String.format(Locale.US,
                    "Manual switch source — target: %d/%d | url: %s",
                    index + 1, currentSources.size(), url));
            currentSourceIndex = index;
            isRetrying = false;
            playCurrentSource();
        }
    }

    /**
     * 起播成功后重新进入缓冲时武装看门狗。已武装则不重复计时，避免缓冲状态反复进出把计时无限推后。
     */
    private void startRebufferWatchdog() {
        // 还没起播过：这段缓冲归起播超时管，不要重复武装
        if (!hasStartedPlayback || rebufferWatchdogRunnable != null) {
            return;
        }
        long timeoutMs = preferenceManager().getSourceSwitchTimeoutMs();
        final int generation = playGeneration;
        rebufferWatchdogRunnable = () -> {
            rebufferWatchdogRunnable = null;
            if (generation != playGeneration) {
                return;
            }
            if (player == null || player.getPlaybackState() != Player.STATE_BUFFERING) {
                return;
            }
            if (!player.getPlayWhenReady()) {
                // 用户已暂停或退到后台：恢复动作会把 playWhenReady 重新置真，
                // 等于在后台偷偷续播。此处只继续看管，不动作。
                return;
            }
            onRebufferStall(timeoutMs);
        };
        handler.postDelayed(rebufferWatchdogRunnable, timeoutMs);
    }

    private void cancelRebufferWatchdog() {
        if (rebufferWatchdogRunnable != null) {
            handler.removeCallbacks(rebufferWatchdogRunnable);
            rebufferWatchdogRunnable = null;
        }
    }

    /**
     * 卡在缓冲超时后的恢复：先原地重开当前线路，多次无效再换源。
     *
     * <p>这里刻意不直接换源。实测遇到过「组播数据以满码率持续进来、播放器却永远停在 BUFFERING」
     * （TS 突发丢包导致时间戳不连续，缓冲时长算不出来），这种情况重新 prepare 一次即可恢复：
     * 新的解复用器、新的采样队列、时间戳重新对齐。而且单线路频道换源就等于放弃播放。
     * 真正死掉的流会在重开后被起播超时接住，照常换源。
     */
    void onRebufferStall(long timeoutMs) {
        if (consecutiveStallRecoveries >= MAX_STALL_RECOVERIES) {
            switchToNextSource(String.format(Locale.US,
                    "rebuffer_stall: still buffering after %d recovery attempt(s)",
                    consecutiveStallRecoveries));
            return;
        }
        consecutiveStallRecoveries++;
        Log.w(TAG, String.format(Locale.US,
                "Stalled in BUFFERING for %dms with source %d/%d - restarting playback (%d/%d)",
                timeoutMs, currentSourceIndex + 1, currentSources.size(),
                consecutiveStallRecoveries, MAX_STALL_RECOVERIES));
        playCurrentSource();
    }

    private void startTimeout() {
        cancelTimeout();
        long timeoutMs = preferenceManager().getSourceSwitchTimeoutMs();
        final int generation = playGeneration;
        timeoutRunnable = () -> {
            if (generation != playGeneration) {
                return;
            }
            if (player != null && !player.isPlaying()) {
                int state = player.getPlaybackState();
                String stateName = playbackStateName(state);
                switchToNextSource(String.format(Locale.US,
                        "source_timeout: %d ms without playing (playbackState=%s, playWhenReady=%s)",
                        timeoutMs, stateName, player.getPlayWhenReady()));
            }
        };
        handler.postDelayed(timeoutRunnable, timeoutMs);
    }

    /** 设置中修改「超时换源」后调用，按新时长重新计时（若当前仍在等待起播）。 */
    public void rescheduleSourceTimeoutFromPreferences() {
        if (player == null || currentSources == null || currentSources.isEmpty()) {
            return;
        }
        startTimeout();
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
        }
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    public int getCurrentSourceIndex() {
        return currentSourceIndex;
    }

    public int getSourceCount() {
        return currentSources.size();
    }

    /**
     * 当前实际交给播放器的地址：组播已规范化，配了 udpxy 时为改写后的 HTTP 地址。
     * 与 {@link #getCurrentPlaybackUrl()}（信号源里配置的原始地址）区分，便于排查组播问题。
     */
    @Nullable
    public String getCurrentResolvedPlaybackUrl() {
        return currentResolvedUrl;
    }

    /** 当前正在尝试播放的线路地址（信号源中配置的原始写法）；无有效线路时为 null。 */
    @Nullable
    public String getCurrentPlaybackUrl() {
        if (currentSources == null || currentSources.isEmpty()) {
            return null;
        }
        if (currentSourceIndex < 0 || currentSourceIndex >= currentSources.size()) {
            return null;
        }
        return currentSources.get(currentSourceIndex).url;
    }

    public void pause() {
        if (player != null) {
            player.setPlayWhenReady(false);
            // 暂停期间不看管卡顿，避免看门狗把播放重新拉起来
            cancelRebufferWatchdog();
        }
    }

    public void resume() {
        if (player != null) {
            player.setPlayWhenReady(true);
            if (hasStartedPlayback && player.getPlaybackState() == Player.STATE_BUFFERING) {
                // 暂停时取消过，恢复后若仍卡着就重新看管
                startRebufferWatchdog();
            }
        }
    }

    public void release() {
        cancelTimeout();
        cancelRebufferWatchdog();
        if (hlsSegmentPrefetcher != null) {
            hlsSegmentPrefetcher.release();
            hlsSegmentPrefetcher = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
    }
}
