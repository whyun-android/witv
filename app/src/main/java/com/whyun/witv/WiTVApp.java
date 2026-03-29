package com.whyun.witv;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.player.PlayerManager;
import com.whyun.witv.server.WebServer;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class WiTVApp extends Application {

    private static WiTVApp instance;
    private WebServer webServer;
    private DefaultBandwidthMeter bandwidthMeter;
    private volatile PlayerManager activePlayerManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<SourceChangeListener> sourceChangeListeners = new CopyOnWriteArraySet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppDatabase.getInstance(this);
        startWebServer();
    }

    public static WiTVApp getInstance() {
        return instance;
    }

    /**
     * 全局带宽估算器，供 ExoPlayer 与播放页「视频加载速度」浮层共用。
     */
    @OptIn(markerClass = UnstableApi.class)
    public synchronized DefaultBandwidthMeter getOrCreateBandwidthMeter() {
        if (bandwidthMeter == null) {
            bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();
        }
        return bandwidthMeter;
    }

    /** ExoPlayer 根据近期下载估算的码率（bit/s），无数据时可能为 0。 */
    public long getPlaybackBitrateEstimate() {
        DefaultBandwidthMeter meter = bandwidthMeter;
        return meter != null ? meter.getBitrateEstimate() : 0L;
    }

    /** 当前前台播放页持有的 {@link PlayerManager}，用于判断是否在播放。 */
    public void setActivePlayerManager(PlayerManager manager) {
        activePlayerManager = manager;
    }

    public PlayerManager getActivePlayerManager() {
        return activePlayerManager;
    }

    public void addSourceChangeListener(SourceChangeListener listener) {
        if (listener != null) {
            sourceChangeListeners.add(listener);
        }
    }

    public void removeSourceChangeListener(SourceChangeListener listener) {
        if (listener != null) {
            sourceChangeListeners.remove(listener);
        }
    }

    public void notifyActiveSourceChanged(long sourceId) {
        mainHandler.post(() -> {
            for (SourceChangeListener listener : sourceChangeListeners) {
                listener.onActiveSourceChanged(sourceId);
            }
        });
    }

    private void startWebServer() {
        try {
            webServer = new WebServer(this, 9978);
            webServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public WebServer getWebServer() {
        return webServer;
    }

    public interface SourceChangeListener {
        void onActiveSourceChanged(long sourceId);
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (webServer != null && webServer.isAlive()) {
            webServer.stop();
        }
    }
}
