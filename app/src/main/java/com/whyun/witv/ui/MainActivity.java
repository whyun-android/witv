package com.whyun.witv.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.whyun.witv.R;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.player.PlayerManager;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends FragmentActivity implements SettingsPanelHost {

    private static final String TAG = "MainActivity";
    private static final String TAG_SETTINGS_FRAGMENT = "settings_drawer_main";

    private View emptyState;
    private Button refreshWebHintButton;
    private TextView webAddress;
    private ProgressBar loadingProgress;
    private View settingsPanelOverlay;
    private PreferenceManager preferenceManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean autoPlayAttempted = false;
    private AlertDialog exitDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        emptyState = findViewById(R.id.empty_state);
        refreshWebHintButton = findViewById(R.id.btn_refresh_web_hint);
        webAddress = findViewById(R.id.tv_web_address);
        loadingProgress = findViewById(R.id.loading_progress);
        settingsPanelOverlay = findViewById(R.id.settings_panel_overlay);
        preferenceManager = new PreferenceManager(this);

        findViewById(R.id.settings_scrim).setOnClickListener(v -> hideSettingsPanel());
        refreshWebHintButton.setOnClickListener(v -> refreshWebSetupAndChannels());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_panel_content, new SettingsCollapsibleFragment(), TAG_SETTINGS_FRAGMENT)
                    .commitNow();
        }

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
        Fragment browse = getSupportFragmentManager().findFragmentById(R.id.main_browse_fragment);
        if (browse != null && browse.getView() != null) {
            // 空状态盖在 Leanback 上时，浏览区仍会抢焦点；隐藏根视图才能把焦点留给「刷新」等控件
            browse.getView().setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (empty) {
            updateWebAddress();
            if (refreshWebHintButton != null) {
                refreshWebHintButton.post(() -> {
                    if (refreshWebHintButton != null && emptyState.getVisibility() == View.VISIBLE) {
                        refreshWebHintButton.requestFocus();
                    }
                });
            }
        }
    }

    public void showLoading(boolean loading) {
        loadingProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    public void showSettingsPanel() {
        if (settingsPanelOverlay == null) {
            return;
        }
        settingsPanelOverlay.setVisibility(View.VISIBLE);
        SettingsCollapsibleFragment f = (SettingsCollapsibleFragment) getSupportFragmentManager()
                .findFragmentByTag(TAG_SETTINGS_FRAGMENT);
        if (f != null) {
            f.refreshAndFocus();
        }
    }

    private void hideSettingsPanel() {
        SettingsCollapsibleFragment f = (SettingsCollapsibleFragment) getSupportFragmentManager()
                .findFragmentByTag(TAG_SETTINGS_FRAGMENT);
        if (f != null) {
            f.onSettingsDrawerDismiss();
        }
        if (settingsPanelOverlay != null) {
            settingsPanelOverlay.setVisibility(View.GONE);
        }
        Fragment browse = getSupportFragmentManager().findFragmentById(R.id.main_browse_fragment);
        if (emptyState != null && emptyState.getVisibility() == View.VISIBLE) {
            if (refreshWebHintButton != null) {
                refreshWebHintButton.requestFocus();
            }
        } else if (browse != null && browse.getView() != null) {
            browse.getView().requestFocus();
        }
    }

    public boolean isSettingsPanelVisible() {
        return settingsPanelOverlay != null && settingsPanelOverlay.getVisibility() == View.VISIBLE;
    }

    public void refreshWebSetupAndChannels() {
        updateWebAddress();
        Fragment frag = getSupportFragmentManager().findFragmentById(R.id.main_browse_fragment);
        if (frag instanceof MainFragment) {
            ((MainFragment) frag).loadChannels();
        }
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
        if (isSettingsPanelVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleSettingsBack();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_F6) {
                hideSettingsPanel();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                SettingsCollapsibleFragment navFragment = (SettingsCollapsibleFragment) getSupportFragmentManager()
                        .findFragmentByTag(TAG_SETTINGS_FRAGMENT);
                if (navFragment != null) {
                    navFragment.dispatchDrawerKey(keyCode, event);
                }
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showExitDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_F6) {
            showSettingsPanel();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (isSettingsPanelVisible()) {
            handleSettingsBack();
            return;
        }
        showExitDialog();
    }

    private void handleSettingsBack() {
        SettingsCollapsibleFragment f = (SettingsCollapsibleFragment) getSupportFragmentManager()
                .findFragmentByTag(TAG_SETTINGS_FRAGMENT);
        if (f != null && f.handleBack()) {
            return;
        }
        hideSettingsPanel();
    }

    private void showExitDialog() {
        if (isFinishing()) {
            return;
        }
        if (exitDialog != null && exitDialog.isShowing()) {
            return;
        }
        exitDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.exit_dialog_title)
                .setMessage(R.string.exit_dialog_message)
                .setNegativeButton(R.string.settings, (dialog, which) -> showSettingsPanel())
                .setPositiveButton(R.string.exit_dialog_rest, (dialog, which) -> finish())
                .create();
        exitDialog.setOnDismissListener(dialog -> exitDialog = null);
        exitDialog.show();
        Button restButton = exitDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (restButton != null) {
            restButton.post(restButton::requestFocus);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        hideSettingsPanel();
        if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    @Override
    public boolean shouldShowStreamSwitchGroup() {
        return false;
    }

    @Override
    public boolean shouldShowSourceTimeoutGroup() {
        return true;
    }

    @Override
    public boolean shouldShowPlaybackMediaInfoHelp() {
        return false;
    }

    @Override
    public PlayerManager getPlayerManagerOrNull() {
        return null;
    }

    @Override
    public void onManualStreamSwitch(int index) {
    }

    @Override
    public void onPlaybackOverlayPreferenceChanged() {
    }
}
