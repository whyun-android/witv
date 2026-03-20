package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.whyun.witv.WiTVApp;
import com.whyun.witv.data.db.entity.ChannelSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerManager {

    private static final String TAG = "PlayerManager";

    public interface Callback {
        void onSourceSwitching(int newIndex, int total);
        void onAllSourcesFailed();
        void onPlaybackStarted(int sourceIndex, int total);
        void onError(String message);
    }

    private static final long SOURCE_TIMEOUT_MS = 15_000;

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

    private Runnable createTimeoutRunnable(int generation) {
        return () -> {
            if (generation != playGeneration) return;
            if (player != null && !player.isPlaying()) {
                int state = player.getPlaybackState();
                String stateName = playbackStateName(state);
                switchToNextSource(String.format(Locale.US,
                        "source_timeout: %d ms without playing (playbackState=%s, playWhenReady=%s)",
                        SOURCE_TIMEOUT_MS, stateName, player.getPlayWhenReady()));
            }
        };
    }

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
                        15_000,  // minBufferMs
                        50_000,  // maxBufferMs
                        2500,    // bufferForPlaybackMs
                        5000     // bufferForPlaybackAfterRebufferMs
                )
                .build();

        DefaultBandwidthMeter bandwidthMeter = WiTVApp.getInstance().getOrCreateBandwidthMeter();
        player = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
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

        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);

        if (lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/")
                || lowerUrl.contains("type=m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lowerUrl.contains(".mpd") || lowerUrl.contains("/dash/")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD);
        } else if (lowerUrl.endsWith(".ts") || lowerUrl.contains(".ts?")) {
            builder.setMimeType(MimeTypes.VIDEO_MP2T);
        }
        // Otherwise let ExoPlayer auto-detect

        return builder.build();
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
        timeoutRunnable = createTimeoutRunnable(playGeneration);
        handler.postDelayed(timeoutRunnable, SOURCE_TIMEOUT_MS);
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
