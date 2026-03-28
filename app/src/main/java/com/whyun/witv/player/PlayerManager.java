package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.PlayerView;

import com.whyun.witv.WiTVApp;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.entity.ChannelSource;

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
    private PlayerView playerView;
    private Callback callback;

    private List<ChannelSource> currentSources = new ArrayList<>();
    private int currentSourceIndex = 0;
    private boolean isRetrying = false;
    private int playGeneration = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

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
                cancelTimeout();
                isRetrying = false;
                Log.i(TAG, "Playback started, source index: " + currentSourceIndex);
                if (callback != null) {
                    callback.onPlaybackStarted(currentSourceIndex, currentSources.size());
                }
            } else if (playbackState == Player.STATE_ENDED) {
                cancelTimeout();
            } else if (playbackState == Player.STATE_BUFFERING) {
                Log.d(TAG, "Buffering...");
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (player == null || player.getPlayerError() != error) {
                Log.w(TAG, "Ignoring stale error callback from previous channel");
                return;
            }
            cancelTimeout();

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

    public PlayerManager(Context context) {
        this.context = context;
    }

    @OptIn(markerClass = UnstableApi.class)
    public void initialize(PlayerView playerView) {
        this.playerView = playerView;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        35_000,  // minBufferMs
                        90_000,  // maxBufferMs
                        6000,    // bufferForPlaybackMs
                        15_000   // bufferForPlaybackAfterRebufferMs
                )
                .build();

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
                .setUserAgent(HTTP_USER_AGENT);
        DefaultDataSource.Factory innerDataSourceFactory =
                new DefaultDataSource.Factory(context, httpDataSourceFactory);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context,
                new M3u8RewritingDataSource.Factory(innerDataSourceFactory));
        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(dataSourceFactory);

        DefaultBandwidthMeter bandwidthMeter = WiTVApp.getInstance().getOrCreateBandwidthMeter();
        player = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        playerView.setPlayer(player);
        player.addListener(playerListener);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void playChannel(List<ChannelSource> sources) {
        playGeneration++;
        cancelTimeout();
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
            stopPlayer();
            Log.e(TAG, "All sources failed");
            if (callback != null) callback.onAllSourcesFailed();
            return;
        }

        if (isRetrying && callback != null) {
            callback.onSourceSwitching(currentSourceIndex, currentSources.size());
        }

        String url = currentSources.get(currentSourceIndex).url;
        Log.i(TAG, String.format(Locale.US, "Trying source %d/%d: %s",
                currentSourceIndex + 1, currentSources.size(), url));

        player.stop();
        player.clearMediaItems();

        MediaItem mediaItem = buildMediaItem(url);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
        startTimeout();
    }

    private MediaItem buildMediaItem(String url) {
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
            String url = currentSources.get(index).url;
            Log.i(TAG, String.format(Locale.US,
                    "Manual switch source — target: %d/%d | url: %s",
                    index + 1, currentSources.size(), url));
            currentSourceIndex = index;
            isRetrying = false;
            playCurrentSource();
        }
    }

    private void startTimeout() {
        cancelTimeout();
        long timeoutMs = new PreferenceManager(context).getSourceSwitchTimeoutMs();
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

    /** 当前正在尝试播放的线路地址；无有效线路时为 null。 */
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
        }
    }

    public void resume() {
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    public void release() {
        cancelTimeout();
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
    }
}
