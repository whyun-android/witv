package com.whyun.witv.ui;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.whyun.witv.R;
import com.whyun.witv.WiTVApp;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.EpgProgram;
import com.whyun.witv.data.repository.ChannelRepository;
import com.whyun.witv.data.repository.EpgRepository;
import com.whyun.witv.player.PlayerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends FragmentActivity implements PlayerManager.Callback, SettingsPanelHost {

    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_SOURCE_ID = "source_id";

    private PlayerManager playerManager;
    private ChannelRepository channelRepository;
    private EpgRepository epgRepository;
    private PreferenceManager preferenceManager;

    private View epgOverlay;
    private TextView channelNameView;
    private TextView sourceInfoView;
    private ImageView channelLogoView;
    private ImageView favoriteIcon;
    private TextView currentProgramView;
    private TextView nextProgramView;
    private TextView mediaInfoVideoColumn;
    private TextView mediaInfoAudioColumn;
    private TextView switchingToast;
    private TextView loadSpeedOverlay;
    private View channelListPanel;
    private RecyclerView channelListOverlay;
    private TextView channelListEpgChannelName;
    private TextView channelListEpgContent;
    private int channelListEpgLoadSeq = 0;
    private View settingsPanelOverlay;
    private PlayerView playerView;

    private boolean isFavorite = false;

    private long currentChannelId;
    private long sourceId;
    private Channel currentChannel;
    private List<Channel> allChannels;
    private int currentChannelIndex = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private boolean overlayVisible = false;
    private final Runnable hideOverlayRunnable = () -> hideOverlay();

    private static final long CHANNEL_LIST_HIDE_IDLE_MS = 10_000L;
    private final Runnable hideChannelListIdleRunnable = () -> {
        if (channelListPanel != null) {
            channelListPanel.setVisibility(View.GONE);
        }
    };

    private final Runnable loadSpeedRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateLoadSpeedOverlayText();
            if (preferenceManager != null && preferenceManager.isShowLoadSpeedOverlay()) {
                handler.postDelayed(this, 500);
            }
        }
    };

    private final Player.Listener mediaInfoListener = new Player.Listener() {
        @Override
        public void onTracksChanged(Tracks tracks) {
            postUpdateMediaInfo();
        }

        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            postUpdateMediaInfo();
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            postUpdateLoadSpeedOverlay();
        }
    };

    // Number input for direct channel switching
    private StringBuilder numberInput = new StringBuilder();
    private final Runnable numberInputRunnable = () -> {
        if (numberInput.length() > 0) {
            int num = Integer.parseInt(numberInput.toString()) - 1;
            if (allChannels != null && num >= 0 && num < allChannels.size()) {
                currentChannelIndex = num;
                playChannel(allChannels.get(currentChannelIndex));
            }
            numberInput.setLength(0);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        currentChannelId = getIntent().getLongExtra(EXTRA_CHANNEL_ID, -1);
        sourceId = getIntent().getLongExtra(EXTRA_SOURCE_ID, -1);

        initViews();
        initPlayer();
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_panel_content, new SettingsCollapsibleFragment(), "settings_drawer")
                    .commitNow();
        }
        applyLoadSpeedOverlayPreference();
        loadAndPlay();
    }

    private void initViews() {
        epgOverlay = findViewById(R.id.epg_overlay);
        channelNameView = findViewById(R.id.channel_name);
        sourceInfoView = findViewById(R.id.source_info);
        channelLogoView = findViewById(R.id.channel_logo);
        favoriteIcon = findViewById(R.id.favorite_icon);
        currentProgramView = findViewById(R.id.current_program);
        nextProgramView = findViewById(R.id.next_program);
        mediaInfoVideoColumn = findViewById(R.id.media_info_video_column);
        mediaInfoAudioColumn = findViewById(R.id.media_info_audio_column);
        switchingToast = findViewById(R.id.switching_toast);
        loadSpeedOverlay = findViewById(R.id.load_speed_overlay);
        channelListPanel = findViewById(R.id.channel_list_panel);
        channelListOverlay = findViewById(R.id.channel_list_overlay);
        channelListEpgChannelName = findViewById(R.id.channel_list_epg_channel_name);
        channelListEpgContent = findViewById(R.id.channel_list_epg_content);
        channelListOverlay.setLayoutManager(new LinearLayoutManager(this));

        settingsPanelOverlay = findViewById(R.id.settings_panel_overlay);
        findViewById(R.id.settings_scrim).setOnClickListener(v -> hideSettingsPanel());
    }

    private void initPlayer() {
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);

        playerManager = new PlayerManager(this);
        playerManager.initialize(playerView);
        playerManager.setCallback(this);

        channelRepository = new ChannelRepository(this);
        epgRepository = new EpgRepository(this);
        preferenceManager = new PreferenceManager(this);

        ExoPlayer exo = playerManager.getPlayer();
        if (exo != null) {
            exo.addListener(mediaInfoListener);
        }
        WiTVApp.getInstance().setActivePlayerManager(playerManager);
    }

    private void postUpdateLoadSpeedOverlay() {
        runOnUiThread(this::updateLoadSpeedOverlayText);
    }

    /** 根据设置显示/隐藏右上角加载速度，并刷新文案 */
    private void applyLoadSpeedOverlayPreference() {
        if (loadSpeedOverlay == null || preferenceManager == null) {
            return;
        }
        boolean show = preferenceManager.isShowLoadSpeedOverlay();
        loadSpeedOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            updateLoadSpeedOverlayText();
        }
    }

    private void updateLoadSpeedOverlayText() {
        if (loadSpeedOverlay == null || preferenceManager == null
                || !preferenceManager.isShowLoadSpeedOverlay()) {
            return;
        }
        ExoPlayer exo = playerManager != null ? playerManager.getPlayer() : null;
        if (exo == null) {
            loadSpeedOverlay.setText("—");
            return;
        }
        int state = exo.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            loadSpeedOverlay.setText(getString(R.string.load_speed_overlay_idle));
            return;
        }
        long bps = WiTVApp.getInstance().getPlaybackBitrateEstimate();
        if (bps <= 0) {
            loadSpeedOverlay.setText(getString(R.string.load_speed_overlay_collecting));
            return;
        }
        loadSpeedOverlay.setText(formatBitsPerSecond(bps));
    }

    private static String formatBitsPerSecond(long bps) {
        if (bps <= 0) {
            return "—";
        }
        if (bps >= 1_000_000) {
            return String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0);
        }
        if (bps >= 1_000) {
            return String.format(Locale.US, "%d kbps", bps / 1000);
        }
        return bps + " bps";
    }

    private void startLoadSpeedRefreshIfNeeded() {
        handler.removeCallbacks(loadSpeedRefreshRunnable);
        if (preferenceManager != null && preferenceManager.isShowLoadSpeedOverlay()) {
            handler.post(loadSpeedRefreshRunnable);
        }
    }

    private void postUpdateMediaInfo() {
        runOnUiThread(this::updateMediaInfoText);
    }

    private void updateMediaInfoText() {
        if (mediaInfoVideoColumn == null || mediaInfoAudioColumn == null) {
            return;
        }
        ExoPlayer exo = playerManager.getPlayer();
        String waiting = getString(R.string.media_info_waiting);
        MediaInfoFormatter.MediaInfoColumns cols = MediaInfoFormatter.buildTwoColumns(exo,
                getString(R.string.media_info_resolution),
                getString(R.string.media_info_video_codec),
                getString(R.string.media_info_audio_codec),
                getString(R.string.media_info_sample_rate),
                getString(R.string.media_info_channels),
                waiting);
        if (cols.videoColumn.isEmpty() && cols.audioColumn.isEmpty()) {
            mediaInfoVideoColumn.setText("");
            mediaInfoAudioColumn.setText("");
            return;
        }
        mediaInfoVideoColumn.setText(cols.videoColumn);
        mediaInfoAudioColumn.setText(cols.audioColumn);
    }

    private void loadAndPlay() {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            currentChannel = db.channelDao().getById(currentChannelId);
            allChannels = db.channelDao().getBySource(sourceId);

            for (int i = 0; i < allChannels.size(); i++) {
                if (allChannels.get(i).id == currentChannelId) {
                    currentChannelIndex = i;
                    break;
                }
            }

            if (currentChannel != null) {
                preferenceManager.saveLastChannel(currentChannelId, sourceId);
                isFavorite = channelRepository.isFavorite(currentChannelId);
                List<ChannelSource> sources = channelRepository.getChannelSources(currentChannelId);
                runOnUiThread(() -> {
                    updateChannelInfo();
                    dismissAllSourcesFailedToast();
                    playerManager.playChannel(sources);
                    showOverlayTemporarily();
                });
            }
        });
    }

    private void playChannel(Channel channel) {
        currentChannel = channel;
        currentChannelId = channel.id;
        preferenceManager.saveLastChannel(channel.id, sourceId);
        executor.execute(() -> {
            List<ChannelSource> sources = channelRepository.getChannelSources(channel.id);
            isFavorite = channelRepository.isFavorite(channel.id);
            runOnUiThread(() -> {
                updateChannelInfo();
                dismissAllSourcesFailedToast();
                playerManager.playChannel(sources);
                showOverlayTemporarily();
            });
        });
    }

    /** 用户切换频道或重新发起播放时关闭「所有源失败」等常驻提示；成功开播时仍由 onPlaybackStarted 处理。 */
    private void dismissAllSourcesFailedToast() {
        switchingToast.setVisibility(View.GONE);
    }

    private void updateChannelInfo() {
        if (currentChannel == null) return;

        channelNameView.setText(currentChannel.displayName);
        updateFavoriteIcon();

        if (currentChannel.logoUrl != null && !currentChannel.logoUrl.isEmpty()) {
            Glide.with(this)
                    .asBitmap()
                    .load(currentChannel.logoUrl)
                    .into(channelLogoView);
        }

        loadEpgInfo();
    }

    private void updateFavoriteIcon() {
        if (favoriteIcon != null) {
            favoriteIcon.setImageResource(isFavorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite_border);
            favoriteIcon.setVisibility(View.VISIBLE);
        }
    }

    private void toggleFavorite() {
        if (currentChannel == null) return;
        executor.execute(() -> {
            channelRepository.toggleFavorite(currentChannelId);
            isFavorite = channelRepository.isFavorite(currentChannelId);
            runOnUiThread(() -> {
                updateFavoriteIcon();
                String msg = isFavorite ? getString(R.string.added_to_favorites) : getString(R.string.removed_from_favorites);
                switchingToast.setText(msg);
                switchingToast.setVisibility(View.VISIBLE);
                handler.postDelayed(() -> switchingToast.setVisibility(View.GONE), 2000);
            });
        });
    }

    /** 频道列表内长按确认：切换任意行的收藏，并刷新列表心形与当前播放页心形 */
    private void toggleFavoriteForChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        executor.execute(() -> {
            channelRepository.toggleFavorite(channel.id);
            boolean nowFav = channelRepository.isFavorite(channel.id);
            Set<Long> favIds = new HashSet<>(channelRepository.getAllFavoriteChannelIds());
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (channel.id == currentChannelId) {
                    isFavorite = nowFav;
                    updateFavoriteIcon();
                }
                RecyclerView.Adapter<?> ad = channelListOverlay.getAdapter();
                if (ad instanceof ChannelListAdapter) {
                    ((ChannelListAdapter) ad).setFavoriteIds(favIds);
                }
                switchingToast.setText(nowFav
                        ? getString(R.string.added_to_favorites)
                        : getString(R.string.removed_from_favorites));
                switchingToast.setVisibility(View.VISIBLE);
                handler.postDelayed(() -> switchingToast.setVisibility(View.GONE), 2000);
                scheduleChannelListIdleHide();
            });
        });
    }

    private void loadEpgInfo() {
        if (currentChannel == null) {
            currentProgramView.setText(getString(R.string.no_epg));
            nextProgramView.setVisibility(View.GONE);
            return;
        }

        boolean hasTvgId = currentChannel.tvgId != null && !currentChannel.tvgId.isEmpty();
        boolean hasTvgName = currentChannel.tvgName != null && !currentChannel.tvgName.isEmpty();
        if (!hasTvgId && !hasTvgName) {
            currentProgramView.setText(getString(R.string.no_epg));
            nextProgramView.setVisibility(View.GONE);
            return;
        }

        executor.execute(() -> {
            List<EpgProgram> programs = epgRepository.getCurrentAndNext(currentChannel.tvgId, currentChannel.tvgName);
            runOnUiThread(() -> {
                if (programs != null && !programs.isEmpty()) {
                    EpgProgram current = programs.get(0);
                    String currentText = String.format("%s  %s - %s",
                            current.title,
                            timeFormat.format(new Date(current.startTime)),
                            timeFormat.format(new Date(current.endTime)));
                    currentProgramView.setText(getString(R.string.current_program) + "：" + currentText);

                    if (programs.size() > 1) {
                        EpgProgram next = programs.get(1);
                        String nextText = String.format("%s  %s",
                                next.title,
                                timeFormat.format(new Date(next.startTime)));
                        nextProgramView.setText(getString(R.string.next_program) + "：" + nextText);
                        nextProgramView.setVisibility(View.VISIBLE);
                    } else {
                        nextProgramView.setVisibility(View.GONE);
                    }
                } else {
                    currentProgramView.setText(getString(R.string.no_epg));
                    nextProgramView.setVisibility(View.GONE);
                }
            });
        });
    }

    private void showOverlayTemporarily() {
        epgOverlay.setVisibility(View.VISIBLE);
        overlayVisible = true;
        updateMediaInfoText();
        handler.removeCallbacks(hideOverlayRunnable);
        handler.postDelayed(hideOverlayRunnable, 5000);
    }

    private void hideOverlay() {
        cancelChannelListIdleHide();
        epgOverlay.setVisibility(View.GONE);
        if (channelListPanel != null) {
            channelListPanel.setVisibility(View.GONE);
        }
        overlayVisible = false;
    }

    private boolean isChannelListPanelVisible() {
        return channelListPanel != null && channelListPanel.getVisibility() == View.VISIBLE;
    }

    private void updateChannelListEpgPanel(Channel ch) {
        if (ch == null || channelListEpgChannelName == null || channelListEpgContent == null) {
            return;
        }
        channelListEpgChannelName.setText(ch.displayName);
        boolean hasEpgKey = (ch.tvgId != null && !ch.tvgId.isEmpty())
                || (ch.tvgName != null && !ch.tvgName.isEmpty());
        if (!hasEpgKey) {
            channelListEpgContent.setText(getString(R.string.no_epg));
            return;
        }
        channelListEpgContent.setText(getString(R.string.loading));
        final int seq = ++channelListEpgLoadSeq;
        executor.execute(() -> {
            List<EpgProgram> programs = epgRepository.getUpcomingPrograms(ch.tvgId, ch.tvgName, 12);
            final CharSequence text = buildChannelListEpgText(programs);
            runOnUiThread(() -> {
                if (isFinishing() || channelListEpgContent == null || seq != channelListEpgLoadSeq) {
                    return;
                }
                channelListEpgContent.setText(text);
            });
        });
    }

    /**
     * 连续节目列表；正在播出的一条用主题色加粗，其余沿用 TextView 默认 secondary 色。
     */
    private CharSequence buildChannelListEpgText(List<EpgProgram> programs) {
        if (programs == null || programs.isEmpty()) {
            return getString(R.string.no_epg);
        }
        long now = System.currentTimeMillis();
        int highlightColor = ContextCompat.getColor(this, R.color.accent);
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (int i = 0; i < programs.size(); i++) {
            EpgProgram p = programs.get(i);
            String line = timeFormat.format(new Date(p.startTime)) + " - "
                    + timeFormat.format(new Date(p.endTime)) + "  " + p.title;
            int start = ssb.length();
            ssb.append(line);
            int end = ssb.length();
            if (now >= p.startTime && now < p.endTime) {
                ssb.setSpan(new ForegroundColorSpan(highlightColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < programs.size() - 1) {
                ssb.append('\n');
            }
        }
        return ssb;
    }

    private void scheduleChannelListIdleHide() {
        handler.removeCallbacks(hideChannelListIdleRunnable);
        handler.postDelayed(hideChannelListIdleRunnable, CHANNEL_LIST_HIDE_IDLE_MS);
    }

    private void cancelChannelListIdleHide() {
        handler.removeCallbacks(hideChannelListIdleRunnable);
    }

    private int channelStepForUpDown(int baseStep) {
        if (preferenceManager != null && preferenceManager.isReverseChannelKeysEnabled()) {
            return -baseStep;
        }
        return baseStep;
    }

    private void hideSettingsPanel() {
        SettingsCollapsibleFragment f = (SettingsCollapsibleFragment) getSupportFragmentManager()
                .findFragmentByTag("settings_drawer");
        if (f != null) {
            f.onSettingsDrawerDismiss();
        }
        if (settingsPanelOverlay != null) {
            settingsPanelOverlay.setVisibility(View.GONE);
        }
        if (playerView != null) {
            playerView.requestFocus();
        }
    }

    private void showSettingsPanel() {
        if (settingsPanelOverlay == null) {
            return;
        }
        settingsPanelOverlay.setVisibility(View.VISIBLE);
        SettingsCollapsibleFragment f = (SettingsCollapsibleFragment) getSupportFragmentManager()
                .findFragmentByTag("settings_drawer");
        if (f != null) {
            f.refreshAndFocus();
        }
    }

    private boolean isSettingsPanelVisible() {
        return settingsPanelOverlay != null && settingsPanelOverlay.getVisibility() == View.VISIBLE;
    }

    /** 供 {@link SettingsCollapsibleFragment} 读取当前频道以展示「切换源」列表 */
    public long getCurrentChannelIdForPanel() {
        return currentChannelId;
    }

    @Override
    public boolean shouldShowStreamSwitchGroup() {
        return currentChannelId > 0;
    }

    @Override
    public boolean shouldShowSourceTimeoutGroup() {
        return true;
    }

    @Override
    public boolean shouldShowPlaybackMediaInfoHelp() {
        return true;
    }

    @Override
    public PlayerManager getPlayerManagerOrNull() {
        return playerManager;
    }

    @Override
    public void onManualStreamSwitch(int index) {
        if (playerManager != null) {
            playerManager.manualSwitchSource(index);
        }
    }

    @Override
    public void onPlaybackOverlayPreferenceChanged() {
        applyLoadSpeedOverlayPreference();
        startLoadSpeedRefreshIfNeeded();
    }

    @Override
    public void onSourceSwitchTimeoutChanged() {
        if (playerManager != null) {
            playerManager.rescheduleSourceTimeoutFromPreferences();
        }
    }

    @Override
    public void showPlaybackMediaInfoDialog() {
        if (currentChannel == null || playerManager == null) {
            Toast.makeText(this, R.string.media_info_no_playback, Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            Channel ch = currentChannel;
            List<EpgProgram> programs = null;
            if ((ch.tvgId != null && !ch.tvgId.isEmpty())
                    || (ch.tvgName != null && !ch.tvgName.isEmpty())) {
                programs = epgRepository.getCurrentAndNext(ch.tvgId, ch.tvgName);
            }
            final List<EpgProgram> programsFinal = programs;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                String text = buildPlaybackMediaInfoSummary(programsFinal);
                showScrollableInfoDialog(R.string.settings_help_sub_media_info, text);
            });
        });
    }

    private void showScrollableInfoDialog(int titleRes, String message) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int maxH = (int) (360 * density);
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setTextSize(15);
        tv.setPadding(pad, pad, pad, pad);
        tv.setLineSpacing(0, 1.35f);
        sv.addView(tv);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ContextCompat.getColor(this, R.color.card_bg));
        outer.addView(sv);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(outer)
                .setPositiveButton(android.R.string.ok, (d, w) -> d.dismiss())
                .show();
    }

    private String buildPlaybackMediaInfoSummary(List<EpgProgram> programs) {
        StringBuilder sb = new StringBuilder();
        sb.append("频道：").append(currentChannel.displayName).append('\n');
        int total = playerManager.getSourceCount();
        int idx = playerManager.getCurrentSourceIndex();
        sb.append("当前线路：").append(idx + 1).append(" / ").append(Math.max(total, 1)).append('\n');
        String url = playerManager.getCurrentPlaybackUrl();
        sb.append("播放地址：\n").append(url != null ? url : "—").append("\n\n");

        ExoPlayer exo = playerManager.getPlayer();
        String waiting = getString(R.string.media_info_waiting);
        MediaInfoFormatter.MediaInfoColumns cols = MediaInfoFormatter.buildTwoColumns(exo,
                getString(R.string.media_info_resolution),
                getString(R.string.media_info_video_codec),
                getString(R.string.media_info_audio_codec),
                getString(R.string.media_info_sample_rate),
                getString(R.string.media_info_channels),
                waiting);
        sb.append("── 视频 ──\n");
        sb.append(cols.videoColumn.isEmpty() ? "—" : cols.videoColumn);
        sb.append("\n\n── 音频 ──\n");
        sb.append(cols.audioColumn.isEmpty() ? "—" : cols.audioColumn);
        sb.append("\n\n── 节目预告 ──\n");
        if (programs != null && !programs.isEmpty()) {
            EpgProgram cur = programs.get(0);
            sb.append(getString(R.string.current_program)).append("：")
                    .append(cur.title).append("  ")
                    .append(timeFormat.format(new Date(cur.startTime))).append(" - ")
                    .append(timeFormat.format(new Date(cur.endTime))).append('\n');
            if (programs.size() > 1) {
                EpgProgram next = programs.get(1);
                sb.append(getString(R.string.next_program)).append("：")
                        .append(next.title).append("  ")
                        .append(timeFormat.format(new Date(next.startTime))).append('\n');
            }
        } else {
            sb.append(getString(R.string.no_epg));
        }
        return sb.toString();
    }

    private void switchChannel(int direction) {
        if (allChannels == null || allChannels.isEmpty()) return;

        currentChannelIndex += direction;
        if (currentChannelIndex < 0) currentChannelIndex = allChannels.size() - 1;
        if (currentChannelIndex >= allChannels.size()) currentChannelIndex = 0;

        playChannel(allChannels.get(currentChannelIndex));
    }

    private void showChannelList() {
        if (allChannels == null || allChannels.isEmpty()) return;

        executor.execute(() -> {
            Set<Long> favIds = new HashSet<>(channelRepository.getAllFavoriteChannelIds());
            runOnUiThread(() -> {
                ChannelListAdapter adapter = new ChannelListAdapter(allChannels, currentChannelIndex, favIds, channel -> {
                    cancelChannelListIdleHide();
                    if (channelListPanel != null) {
                        channelListPanel.setVisibility(View.GONE);
                    }
                    for (int i = 0; i < allChannels.size(); i++) {
                        if (allChannels.get(i).id == channel.id) {
                            currentChannelIndex = i;
                            break;
                        }
                    }
                    playChannel(channel);
                }, ch -> {
                    scheduleChannelListIdleHide();
                    updateChannelListEpgPanel(ch);
                }, ch -> {
                    toggleFavoriteForChannel(ch);
                    return true;
                });

                channelListOverlay.setAdapter(adapter);
                if (channelListPanel != null) {
                    channelListPanel.setVisibility(View.VISIBLE);
                }
                scheduleChannelListIdleHide();
                updateChannelListEpgPanel(allChannels.get(currentChannelIndex));
                channelListOverlay.scrollToPosition(currentChannelIndex);
                channelListOverlay.post(() -> {
                    RecyclerView.ViewHolder vh = channelListOverlay.findViewHolderForAdapterPosition(currentChannelIndex);
                    if (vh != null) vh.itemView.requestFocus();
                });
            });
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isSettingsPanelVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                SettingsCollapsibleFragment f = (SettingsCollapsibleFragment) getSupportFragmentManager()
                        .findFragmentByTag("settings_drawer");
                if (f != null && f.handleBack()) {
                    return true;
                }
                hideSettingsPanel();
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
                SettingsCollapsibleFragment navFragment =
                        (SettingsCollapsibleFragment) getSupportFragmentManager()
                                .findFragmentByTag("settings_drawer");
                if (navFragment != null) {
                    navFragment.dispatchDrawerKey(keyCode, event);
                }
                return true;
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!isChannelListPanelVisible()) {
                    switchChannel(channelStepForUpDown(-1));
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!isChannelListPanelVisible()) {
                    switchChannel(channelStepForUpDown(1));
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!isChannelListPanelVisible()) {
                    showChannelList();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isChannelListPanelVisible()) {
                    cancelChannelListIdleHide();
                    hideOverlay();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (isChannelListPanelVisible()) {
                    cancelChannelListIdleHide();
                    if (channelListPanel != null) {
                        channelListPanel.setVisibility(View.GONE);
                    }
                    return true;
                }
                if (overlayVisible) {
                    hideOverlay();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_SPACE:
                if (overlayVisible) {
                    hideOverlay();
                } else {
                    showOverlayTemporarily();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_F6:
                showSettingsPanel();
                return true;
            case KeyEvent.KEYCODE_BOOKMARK:
            case KeyEvent.KEYCODE_STAR:
            case KeyEvent.KEYCODE_F:
                toggleFavorite();
                return true;
            default:
                // Handle number keys (0-9) for direct channel input
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    int digit = keyCode - KeyEvent.KEYCODE_0;
                    numberInput.append(digit);
                    switchingToast.setText("频道: " + numberInput.toString());
                    switchingToast.setVisibility(View.VISIBLE);
                    handler.removeCallbacks(numberInputRunnable);
                    handler.postDelayed(numberInputRunnable, 1500);
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    // PlayerManager.Callback implementations

    @Override
    public void onSourceSwitching(int newIndex, int total) {
        switchingToast.setText(String.format(Locale.getDefault(),
                "%s (%d/%d)", getString(R.string.switching_source), newIndex + 1, total));
        switchingToast.setVisibility(View.VISIBLE);
    }

    @Override
    public void onAllSourcesFailed() {
        switchingToast.setText(getString(R.string.all_sources_failed));
        switchingToast.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPlaybackStarted(int sourceIndex, int total) {
        switchingToast.setVisibility(View.GONE);
        sourceInfoView.setText(String.format(Locale.getDefault(),
                getString(R.string.source_count) + " (当前: %d)", total, sourceIndex + 1));
        preferenceManager.saveLastPlayStreamIndex(sourceIndex);
        updateMediaInfoText();
        updateLoadSpeedOverlayText();
    }

    @Override
    public void onError(String message) {
        switchingToast.setText(message);
        switchingToast.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> switchingToast.setVisibility(View.GONE), 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(loadSpeedRefreshRunnable);
        playerManager.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        playerManager.resume();
        applyLoadSpeedOverlayPreference();
        startLoadSpeedRefreshIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        ExoPlayer exo = playerManager.getPlayer();
        if (exo != null) {
            exo.removeListener(mediaInfoListener);
        }
        WiTVApp.getInstance().setActivePlayerManager(null);
        playerManager.release();
        executor.shutdown();
    }
}
