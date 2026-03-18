package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.ui.PlayerView;

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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> {
        if (player != null && !player.isPlaying()) {
            Log.w(TAG, "Source timeout, switching to next");
            switchToNextSource();
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
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
            cancelTimeout();
            String url = currentSourceIndex < currentSources.size()
                    ? currentSources.get(currentSourceIndex).url : "unknown";
            Log.e(TAG, String.format(Locale.US,
                    "Playback error on source %d/%d [%s]: %s",
                    currentSourceIndex + 1, currentSources.size(), url, error.getMessage()));
            switchToNextSource();
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
                        5000,   // minBufferMs
                        30000,  // maxBufferMs
                        1000,   // bufferForPlaybackMs
                        2000    // bufferForPlaybackAfterRebufferMs
                )
                .build();

        player = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build();

        playerView.setPlayer(player);
        player.addListener(playerListener);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void playChannel(List<ChannelSource> sources) {
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

    private void switchToNextSource() {
        isRetrying = true;
        currentSourceIndex++;
        playCurrentSource();
    }

    public void manualSwitchSource(int index) {
        if (index >= 0 && index < currentSources.size()) {
            currentSourceIndex = index;
            isRetrying = false;
            playCurrentSource();
        }
    }

    private void startTimeout() {
        cancelTimeout();
        handler.postDelayed(timeoutRunnable, SOURCE_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        handler.removeCallbacks(timeoutRunnable);
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
