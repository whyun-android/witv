package com.whyun.witv.ui;

import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.whyun.witv.R;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends FragmentActivity {

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

        updateWebAddress();
        if (savedInstanceState == null) {
            tryAutoPlayLastChannel();
        }
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
        if (!preferenceManager.isAutoPlayLastEnabled() || !preferenceManager.hasLastChannel()) {
            return;
        }

        long channelId = preferenceManager.getLastChannelId();
        long sourceId = preferenceManager.getLastSourceId();

        if (channelId == -1 || sourceId == -1) return;

        executor.execute(() -> {
            Channel channel = AppDatabase.getInstance(this).channelDao().getById(channelId);
            if (channel != null && !autoPlayAttempted) {
                autoPlayAttempted = true;
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channelId);
                    intent.putExtra(PlayerActivity.EXTRA_SOURCE_ID, sourceId);
                    startActivity(intent);
                });
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
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
