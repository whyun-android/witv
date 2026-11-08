package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.annotation.VisibleForTesting;
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

        /**
         * 内部重建了 {@code ExoPlayer} 实例。
         *
         * <p>调用方自己加在旧播放器上的监听器会随之失效，必须在这里重新挂到
         * {@link PlayerManager#getPlayer()} 返回的新实例上。
         */
        void onPlayerRebuilt();

        /**
         * 播放引擎在本进程内已经救不回来了（播放线程卡死在厂商解码器里，重建播放器后仍不能出画）。
         *
         * <p>卡住的是 mediaserver 那头的硬件解码器调用，Java 层没有任何办法把那条线程拉出来，
         * 实测只有重启进程才能恢复。调用方应当重启进程并续播当前频道。
         */
        default void onPlaybackEngineUnrecoverable() {
        }
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

    /**
     * 解码器初始化失败后，重建 Surface 再试几次。
     *
     * <p>一次就够：这个失败的根因是 Surface 还被上一个解码器占着
     * （{@code native_window_api_connect returned an error: Invalid argument (-22)}），
     * 重建 SurfaceView 能拆掉那条连接；如果拆完还失败，那就是真的解不了，继续重试没有意义。
     */
    static final int MAX_DECODER_SURFACE_RECOVERIES = 1;

    /**
     * 把 PlayerView 收起来到再放出来之间等多久，让 SurfaceView 真正走完
     * {@code surfaceDestroyed} / {@code surfaceCreated}。
     *
     * <p>这两个回调是跟着 WindowManager 的事务走的，不是同步的，所以必须隔帧而不是连着调。
     */
    private static final long SURFACE_RECREATE_DELAY_MS = 200L;

    /** 当前频道已经为解码器失败重建过几次 Surface */
    private int decoderSurfaceRecoveries;

    /** 播放线程看门狗：每隔多久往播放线程投一次探针 */
    static final long PLAYBACK_THREAD_PING_INTERVAL_MS = 2_000L;

    /**
     * 探针多久没被播放线程处理就算卡死。
     *
     * <p>正常情况下播放线程每 10ms 一轮 doSomeWork，探针几毫秒内就会被处理；
     * 创建/释放解码器偶尔会占住它一两秒。10 秒没反应只可能是卡在了不会返回的调用里
     * （实测 Amlogic 盒子上 {@code MediaCodec.flush()} 会永久阻塞）。
     */
    static final long PLAYBACK_THREAD_HANG_THRESHOLD_MS = 10_000L;

    /** 播放线程卡死后，进程内重建播放器最多试几次；再卡就交给调用方重启进程 */
    static final int MAX_HANG_REBUILDS = 1;

    private Runnable hangWatchdogRunnable;
    /** 当前在途探针的投递时刻（elapsedRealtime），0 表示没有在途探针。播放线程处理后清零。 */
    private volatile long pendingPingSentAtMs;
    /** 已经因为播放线程卡死重建过几次播放器；稳定播放一段时间后清零 */
    private int hangRebuilds;
    /** 因卡死重建之后还没成功出过画：这期间再失败就不是源的问题，而是引擎没恢复 */
    private boolean awaitingRecoveryAfterHang;

    /**
     * 播放页不可见期间播放器整个释放掉了（见 {@link #suspendForBackground()}）。
     * 这期间换台只记下线路，不起播。
     */
    private boolean suspendedInBackground;

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

            if (isDecoderInitFailure(error) && recoverBySurfaceRecreation()) {
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
        awaitingRecoveryAfterHang = false;
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
        startHangWatchdog();
    }

    /**
     * 盯住 ExoPlayer 的播放线程有没有卡死。
     *
     * <p>ExoPlayer 的一切内部动作（prepare / stop / 换源 / 释放）都排在这一条线程上。
     * 它一旦卡在不返回的解码器调用里，后面所有指令都只是在排队：界面上看是「一直缓冲 → 超时换源
     * → 全部线路失败」，换台、重试都没用，像是网络断了。这里每隔
     * {@link #PLAYBACK_THREAD_PING_INTERVAL_MS} 往播放线程投一个探针，超过
     * {@link #PLAYBACK_THREAD_HANG_THRESHOLD_MS} 没被处理就判定卡死。
     *
     * <p>计时从探针投出去那一刻算，而不是从上次应答算：主线程自己卡住时探针根本投不出去，
     * 不能因此误判播放线程卡死。
     */
    private void startHangWatchdog() {
        stopHangWatchdog();
        final ExoPlayer watched = player;
        if (watched == null) {
            return;
        }
        final Handler playbackThreadHandler = new Handler(watched.getPlaybackLooper());
        hangWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != watched || hangWatchdogRunnable != this) {
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                long sentAt = pendingPingSentAtMs;
                if (sentAt != 0L) {
                    if (now - sentAt >= PLAYBACK_THREAD_HANG_THRESHOLD_MS) {
                        onPlaybackThreadHung(now - sentAt);
                        return;
                    }
                } else {
                    pendingPingSentAtMs = now;
                    playbackThreadHandler.post(() -> pendingPingSentAtMs = 0L);
                }
                if (hangRebuilds > 0 && hasStartedPlayback && player.isPlaying()
                        && now - lastReadyAtMs >= STALL_RECOVERY_RESET_AFTER_MS) {
                    // 重建后已经稳定播了一阵，之后再卡死算新的一次，仍先在进程内重建
                    hangRebuilds = 0;
                }
                handler.postDelayed(this, PLAYBACK_THREAD_PING_INTERVAL_MS);
            }
        };
        pendingPingSentAtMs = 0L;
        handler.post(hangWatchdogRunnable);
    }

    private void stopHangWatchdog() {
        if (hangWatchdogRunnable != null) {
            handler.removeCallbacks(hangWatchdogRunnable);
            hangWatchdogRunnable = null;
        }
        pendingPingSentAtMs = 0L;
    }

    /**
     * 播放线程卡死：丢掉整个播放器，换一块新 Surface 原地重开当前线路；
     * 重建过还卡，就交给调用方重启进程。
     */
    private void onPlaybackThreadHung(long unresponsiveMs) {
        stopHangWatchdog();
        if (hangRebuilds >= MAX_HANG_REBUILDS || playerView == null) {
            Log.e(TAG, String.format(Locale.US,
                    "Playback thread unresponsive for %dms again after %d rebuild(s) — engine unrecoverable in-process",
                    unresponsiveMs, hangRebuilds));
            escalateEngineUnrecoverable();
            return;
        }
        hangRebuilds++;
        Log.e(TAG, String.format(Locale.US,
                "Playback thread unresponsive for %dms (stuck in a decoder call) — abandoning the player and rebuilding (%d/%d)",
                unresponsiveMs, hangRebuilds, MAX_HANG_REBUILDS));
        awaitingRecoveryAfterHang = true;
        rebuildPlayerWithFreshSurface();
    }

    private void escalateEngineUnrecoverable() {
        cancelTimeout();
        cancelRebufferWatchdog();
        stopHangWatchdog();
        playGeneration++;
        awaitingRecoveryAfterHang = false;
        if (callback != null) {
            callback.onPlaybackEngineUnrecoverable();
        }
    }

    /**
     * 释放 prefetcher 与播放器，并先把播放器从 PlayerView 上摘下来
     * （原因见 {@link #reinitializeForDecoderModeChange()} 里的注释）。
     *
     * <p>播放线程卡死时 {@code release()} 等不到它应答，会在超时（默认 500ms）后放弃返回，
     * 卡住的那条线程连同它占着的旧 Surface 一起被丢下，所以重建时必须换一块新 Surface。
     */
    private void releasePlayerInstance() {
        stopHangWatchdog();
        if (hlsSegmentPrefetcher != null) {
            hlsSegmentPrefetcher.release();
            hlsSegmentPrefetcher = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            player.release();
            player = null;
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
                "Decoder mode: %s (audio=%d, video=%d), ffmpeg=%s",
                mode.getId(), mode.getAudioExtensionRendererMode(),
                mode.getVideoExtensionRendererMode(), ffmpegLibrarySummary()));
        return new WiTVRenderersFactory(context, mode)
                // 某个 MediaCodec 解码器 configure 失败时，依次尝试同一渲染器里的其它
                // MediaCodec 解码器（例如 c2.android.avc.decoder 失败后试 c2.qti.avc.decoder）。
                // 注意它**不会**退到 FFmpeg 渲染器——渲染器早在 supportsFormat 阶段就选定了；
                // 硬解整体不可用时请改用软解相关档位。
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
    /**
     * 确保有可用的播放器实例，没有就就地补建。
     *
     * <p>{@link #recoverBySurfaceRecreation()} 重建 Surface 的那几百毫秒里 {@link #player}
     * 是空的，而这期间用户完全可能换台——换台会走 {@code playChannel}，它 bump 掉
     * {@code playGeneration} 之后，重建流程的延迟回调就会放弃，没人再把播放器建回来。
     * 不在这里补建的话，{@code playCurrentSource} 会对着 null 调 {@code stop()} 直接崩。
     */
    private void ensurePlayerReady() {
        if (player != null || playerView == null || suspendedInBackground) {
            return;
        }
        Log.i(TAG, "Player was released (surface rebuild in flight) — rebuilding it now");
        playerView.setVisibility(View.VISIBLE);
        initialize(playerView);
        if (callback != null) {
            callback.onPlayerRebuilt();
        }
    }

    /**
     * 这个错误是不是「解码器起不来」。
     *
     * <p>和「这路流本身坏了」要分开处理：换源对它没用——盒子上所有频道都会一样失败。
     */
    @VisibleForTesting
    static boolean isDecoderInitFailure(@Nullable PlaybackException error) {
        return error != null
                && error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED;
    }

    /**
     * 重建 SurfaceView 后原地重试当前这一路。
     *
     * <p>在 Amlogic 盒子上，4K 频道之间换台时常见这样一串：
     * <pre>
     * E MediaCodec: native_window_api_connect returned an error: Invalid argument (-22)
     * W MediaCodecRenderer: Failed to initialize decoder: OMX.amlogic.hevc.decoder.awesome
     * </pre>
     * 上一个解码器（尤其 4K sideband 那种）释放是异步的，SurfaceView 的 BufferQueue 还停在
     * 它配置的格式上没断开，新建的 MediaCodec 就连不上这块 Surface。一旦撞上，之后每一次
     * {@code configure} 都会失败——换源没用，重建 ExoPlayer 也没用（PlayerView 复用的是
     * 同一个 SurfaceView），实测只有重启进程才能恢复。
     *
     * <p>所以这里把 {@code PlayerView} 收起来再放出来，逼 SurfaceView 走一遍
     * {@code surfaceDestroyed} / {@code surfaceCreated}，把那条残留连接连同 BufferQueue
     * 一起拆掉，然后用新播放器重试。
     *
     * @return 是否已接管这次失败；false 表示预算用完了，交回常规的换源流程
     */
    private boolean recoverBySurfaceRecreation() {
        if (playerView == null || currentSources.isEmpty()) {
            return false;
        }
        if (decoderSurfaceRecoveries >= MAX_DECODER_SURFACE_RECOVERIES) {
            Log.w(TAG, String.format(Locale.US,
                    "Decoder init still failing after %d surface rebuild(s) — giving up on this source",
                    decoderSurfaceRecoveries));
            return false;
        }
        decoderSurfaceRecoveries++;
        Log.w(TAG, String.format(Locale.US,
                "Decoder init failed — rebuilding the surface and retrying in place (%d/%d)",
                decoderSurfaceRecoveries, MAX_DECODER_SURFACE_RECOVERIES));
        rebuildPlayerWithFreshSurface();
        return true;
    }

    /**
     * 丢掉当前播放器，收起再放出 PlayerView 换一块新 Surface，然后用新播放器原地重开当前线路。
     */
    private void rebuildPlayerWithFreshSurface() {
        if (playerView == null) {
            return;
        }
        final PlayerView view = playerView;
        final List<ChannelSource> sources = new ArrayList<>(currentSources);
        final int index = currentSourceIndex;

        cancelTimeout();
        cancelRebufferWatchdog();
        // 让旧播放器的残留回调失效，否则下面重建期间它还会往回调里灌错误
        playGeneration++;
        final int generation = playGeneration;

        releasePlayerInstance();
        view.setVisibility(View.GONE);

        handler.postDelayed(() -> {
            if (generation != playGeneration) {
                // 这期间用户已经换台了，那边会自己起播，这里不能再抢
                view.setVisibility(View.VISIBLE);
                return;
            }
            view.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> {
                if (generation != playGeneration) {
                    return;
                }
                initialize(view);
                if (callback != null) {
                    callback.onPlayerRebuilt();
                }
                currentSources = sources;
                currentSourceIndex = Math.max(0, Math.min(index, sources.size() - 1));
                isRetrying = false;
                if (!sources.isEmpty()) {
                    playCurrentSource();
                }
            }, SURFACE_RECREATE_DELAY_MS);
        }, SURFACE_RECREATE_DELAY_MS);
    }

    /**
     * 播放页不可见（onStop）时整个释放播放器和解码器，回来时（{@link #resumeFromBackground()}）
     * 重新建播放器续播当前频道。
     *
     * <p>只暂停的话，解码器会一直挂着：切到别的应用时 SurfaceView 的 Surface 被销毁，
     * ExoPlayer 把输出换到占位 Surface，回来再换回去。Amlogic 硬解在这样来回换几次之后
     * 状态会乱掉（{@code dequeueBuffer: attempting to exceed the max dequeued buffer count}），
     * 下一次 {@code MediaCodec.flush()} 永久阻塞，把播放线程整个卡死。直播停在后台本来也没有意义。
     */
    public void suspendForBackground() {
        if (suspendedInBackground) {
            return;
        }
        suspendedInBackground = true;
        playGeneration++;
        cancelTimeout();
        cancelRebufferWatchdog();
        awaitingRecoveryAfterHang = false;
        releasePlayerInstance();
        Log.i(TAG, "Player released while in background");
    }

    /** 回到前台：重建播放器并续播（见 {@link #suspendForBackground()}）。 */
    public void resumeFromBackground() {
        if (!suspendedInBackground) {
            return;
        }
        suspendedInBackground = false;
        if (playerView == null) {
            return;
        }
        if (playerView.getVisibility() != View.VISIBLE) {
            // 后台前正在为解码器失败重建 Surface，那条流程已被 playGeneration 作废
            playerView.setVisibility(View.VISIBLE);
        }
        initialize(playerView);
        if (callback != null) {
            callback.onPlayerRebuilt();
        }
        if (currentSources.isEmpty()) {
            return;
        }
        Log.i(TAG, "Back in foreground — rebuilding the player and resuming the channel");
        playGeneration++;
        consecutiveStallRecoveries = 0;
        decoderSurfaceRecoveries = 0;
        // 走之前已经全部失败的话，回来从第一条线路重新试一遍
        if (currentSourceIndex < 0 || currentSourceIndex >= currentSources.size()) {
            currentSourceIndex = 0;
        }
        isRetrying = false;
        playCurrentSource();
    }

    public boolean reinitializeForDecoderModeChange() {
        if (playerView == null) {
            return false;
        }
        List<ChannelSource> sources = new ArrayList<>(currentSources);
        int index = currentSourceIndex;

        if (suspendedInBackground) {
            // 回到前台时会按新的解码方式重建
            return true;
        }
        cancelTimeout();
        stopHangWatchdog();
        // 让旧播放器的残留回调失效
        playGeneration++;
        if (hlsSegmentPrefetcher != null) {
            hlsSegmentPrefetcher.release();
            hlsSegmentPrefetcher = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            // 必须在 release 之前把播放器从 PlayerView 上摘下来。PlayerView.setPlayer() 会对
            // 旧播放器调 clearVideoSurfaceView() 来解绑 Surface，而对已释放的播放器下发消息只会
            // 被丢弃（"Ignoring messages sent after release"），Surface 就永远停在 connected 状态，
            // 导致新建的 MediaCodec 连不上：native_window_api_connect returned an error (-22)，
            // 继而 ERROR_CODE_DECODER_INIT_FAILED。
            playerView.setPlayer(null);
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
        decoderSurfaceRecoveries = 0;
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
            if (awaitingRecoveryAfterHang) {
                // 播放线程卡死重建之后一次都没出过画：新解码器多半被卡住的旧实例占着，换源没用
                Log.e(TAG, "All sources failed after a playback-thread hang rebuild — engine unrecoverable in-process");
                escalateEngineUnrecoverable();
                return;
            }
            Log.e(TAG, "All sources failed");
            if (callback != null) callback.onAllSourcesFailed();
            return;
        }

        if (suspendedInBackground) {
            // 后台期间只记下线路，回到前台时由 resumeFromBackground 起播
            Log.i(TAG, "In background — deferring playback until the activity is visible again");
            return;
        }
        ensurePlayerReady();
        if (player == null) {
            Log.w(TAG, "No player instance — cannot start playback");
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
        // 与 reinitializeForDecoderModeChange 同理：先解绑 Surface 再释放，
        // 否则 Surface 会停在 connected 状态
        releasePlayerInstance();
    }
}
