package com.whyun.witv.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.whyun.witv.R;
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

public class PlayerActivity extends FragmentActivity implements PlayerManager.Callback {

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
    private TextView switchingToast;
    private RecyclerView channelListOverlay;

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
        switchingToast = findViewById(R.id.switching_toast);
        channelListOverlay = findViewById(R.id.channel_list_overlay);
        channelListOverlay.setLayoutManager(new LinearLayoutManager(this));
    }

    private void initPlayer() {
        PlayerView playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);

        playerManager = new PlayerManager(this);
        playerManager.initialize(playerView);
        playerManager.setCallback(this);

        channelRepository = new ChannelRepository(this);
        epgRepository = new EpgRepository(this);
        preferenceManager = new PreferenceManager(this);
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
                playerManager.playChannel(sources);
                showOverlayTemporarily();
            });
        });
    }

    private void updateChannelInfo() {
        if (currentChannel == null) return;

        channelNameView.setText(currentChannel.displayName);
        updateFavoriteIcon();

        if (currentChannel.logoUrl != null && !currentChannel.logoUrl.isEmpty()) {
            Glide.with(this)
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
            isFavorite = !isFavorite;
            runOnUiThread(() -> {
                updateFavoriteIcon();
                String msg = isFavorite ? getString(R.string.added_to_favorites) : getString(R.string.removed_from_favorites);
                switchingToast.setText(msg);
                switchingToast.setVisibility(View.VISIBLE);
                handler.postDelayed(() -> switchingToast.setVisibility(View.GONE), 2000);
            });
        });
    }

    private void loadEpgInfo() {
        if (currentChannel == null || currentChannel.tvgId == null || currentChannel.tvgId.isEmpty()) {
            currentProgramView.setText(getString(R.string.no_epg));
            nextProgramView.setVisibility(View.GONE);
            return;
        }

        executor.execute(() -> {
            List<EpgProgram> programs = epgRepository.getCurrentAndNext(currentChannel.tvgId);
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
        handler.removeCallbacks(hideOverlayRunnable);
        handler.postDelayed(hideOverlayRunnable, 5000);
    }

    private void hideOverlay() {
        epgOverlay.setVisibility(View.GONE);
        channelListOverlay.setVisibility(View.GONE);
        overlayVisible = false;
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
                    channelListOverlay.setVisibility(View.GONE);
                    for (int i = 0; i < allChannels.size(); i++) {
                        if (allChannels.get(i).id == channel.id) {
                            currentChannelIndex = i;
                            break;
                        }
                    }
                    playChannel(channel);
                });

                channelListOverlay.setAdapter(adapter);
                channelListOverlay.setVisibility(View.VISIBLE);
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
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channelListOverlay.getVisibility() != View.VISIBLE) {
                    switchChannel(-1);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channelListOverlay.getVisibility() != View.VISIBLE) {
                    switchChannel(1);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelListOverlay.getVisibility() != View.VISIBLE) {
                    showChannelList();
                    showOverlayTemporarily();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (channelListOverlay.getVisibility() != View.VISIBLE) {
                    showChannelList();
                    showOverlayTemporarily();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (channelListOverlay.getVisibility() == View.VISIBLE) {
                    hideOverlay();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (channelListOverlay.getVisibility() == View.VISIBLE) {
                    channelListOverlay.setVisibility(View.GONE);
                    return true;
                }
                if (overlayVisible) {
                    hideOverlay();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                startActivity(new android.content.Intent(this, SettingsActivity.class));
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
        handler.postDelayed(() -> switchingToast.setVisibility(View.GONE), 3000);
    }

    @Override
    public void onPlaybackStarted(int sourceIndex, int total) {
        switchingToast.setVisibility(View.GONE);
        sourceInfoView.setText(String.format(Locale.getDefault(),
                getString(R.string.source_count) + " (当前: %d)", total, sourceIndex + 1));
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
        playerManager.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        playerManager.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        playerManager.release();
        executor.shutdown();
    }
}
