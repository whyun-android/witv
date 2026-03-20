package com.whyun.witv.ui;

import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.whyun.witv.R;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.M3USource;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends FragmentActivity {

    private static final String TAG = "MainActivity";

    private View emptyState;
    private TextView webAddress;
    private ProgressBar loadingProgress;
    private PreferenceManager preferenceManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean autoPlayAttempted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        emptyState = findViewById(R.id.empty_state);
        webAddress = findViewById(R.id.tv_web_address);
        loadingProgress = findViewById(R.id.loading_progress);
        preferenceManager = new PreferenceManager(this);

        logLastPlaybackAtStartup();

        updateWebAddress();
        if (savedInstanceState == null) {
            tryAutoPlayLastChannel();
        }
    }

    /**
     * 冷/热启动进入首页时记录上次成功播放的频道、M3U 源 id、频道内线路索引（0-based）。
     */
    private void logLastPlaybackAtStartup() {
        long channelId = preferenceManager.getLastChannelId();
        long m3uSourceId = preferenceManager.getLastSourceId();
        int streamIndex = preferenceManager.getLastPlayStreamIndex();
        if (channelId == -1) {
            Log.i(TAG, "上次播放：未记录（无 last_channel_id）");
            return;
        }
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(MainActivity.this);
            Channel channel = db.channelDao().getById(channelId);
            String name = channel != null ? channel.displayName : "(频道已不存在或 id=" + channelId + ")";
            String idxPart = streamIndex >= 0
                    ? String.format(Locale.US, "%d（第 %d 条线路）", streamIndex, streamIndex + 1)
                    : "未知（尚未在本频道成功起播或已换台未起播）";
            Log.i(TAG, String.format(Locale.US,
                    "上次播放：channelId=%d, name=%s, m3uSourceId=%d, 线路索引(0-based)=%s",
                    channelId, name, m3uSourceId, idxPart));
        });
    }

    public void showEmptyState(boolean empty) {
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            updateWebAddress();
        }
    }

    public void showLoading(boolean loading) {
        loadingProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void updateWebAddress() {
        String ip = getDeviceIp();
        webAddress.setText(String.format(Locale.getDefault(), "http://%s:9978", ip));
    }

    private String getDeviceIp() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                if (ipInt != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private void tryAutoPlayLastChannel() {
        if (!preferenceManager.isAutoPlayLastEnabled()) {
            return;
        }

        executor.execute(() -> {
            if (autoPlayAttempted) return;

            AppDatabase db = AppDatabase.getInstance(this);
            long channelId = preferenceManager.getLastChannelId();
            long sourceId = preferenceManager.getLastSourceId();

            Channel channel = null;
            if (channelId != -1 && sourceId != -1) {
                channel = db.channelDao().getById(channelId);
            }

            if (channel == null) {
                M3USource activeSource = db.m3uSourceDao().getActive();
                if (activeSource != null) {
                    channel = db.channelDao().getFirstBySource(activeSource.id);
                    if (channel != null) {
                        sourceId = activeSource.id;
                    }
                }
            }

            if (channel != null) {
                autoPlayAttempted = true;
                final long playChannelId = channel.id;
                final long playSourceId = sourceId;
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, playChannelId);
                    intent.putExtra(PlayerActivity.EXTRA_SOURCE_ID, playSourceId);
                    startActivity(intent);
                });
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_F6) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
