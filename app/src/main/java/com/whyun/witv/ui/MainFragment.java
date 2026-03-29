package com.whyun.witv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;

import com.whyun.witv.R;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.repository.ChannelRepository;
import com.whyun.witv.data.repository.EpgRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainFragment extends BrowseSupportFragment {

    private static final String TAG = "MainFragment";

    private ArrayObjectAdapter rowsAdapter;
    private ChannelRepository channelRepository;
    private EpgRepository epgRepository;
    private PreferenceManager preferenceManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Serializes background EPG downloads so rapid onResume does not pile up parallel requests */
    private final ExecutorService epgRefreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WiTV-EpgRefresh");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        channelRepository = new ChannelRepository(requireContext());
        epgRepository = new EpgRepository(requireContext());
        preferenceManager = new PreferenceManager(requireContext());
        setupUI();
        setOnItemViewClickedListener(new ItemClickListener());
    }

    @Override
    public void onResume() {
        super.onResume();
        loadChannels();
    }

    private void setupUI() {
        setTitle(getString(R.string.app_name));
        setBrandColor(getResources().getColor(R.color.primary));
        setSearchAffordanceColor(getResources().getColor(R.color.accent));

        setHeadersState(HEADERS_ENABLED);
        setHeadersTransitionOnBackEnabled(false);

        rowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(rowsAdapter);
    }

    public void loadChannels() {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(requireContext());
            M3USource activeSource = db.m3uSourceDao().getActive();

            if (activeSource == null) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        rowsAdapter.clear();
                        ((MainActivity) getActivity()).showEmptyState(true);
                    });
                }
                return;
            }

            List<String> groups = channelRepository.getGroups(activeSource.id);
            ArrayObjectAdapter newRows = new ArrayObjectAdapter(new ListRowPresenter());

            int headerIndex = 0;
            Set<Long> favIds = new HashSet<>(channelRepository.getAllFavoriteChannelIds());

            List<Channel> favorites = channelRepository.getFavoriteChannels(activeSource.id);
            ChannelPresenter favPresenter = new ChannelPresenter();
            favPresenter.setFavoriteIds(favIds);
            ArrayObjectAdapter favAdapter = new ArrayObjectAdapter(favPresenter);
            for (Channel ch : favorites) {
                favAdapter.add(ch);
            }
            HeaderItem favHeader = new HeaderItem(headerIndex++, getString(R.string.favorite_channels_category));
            newRows.add(new ListRow(favHeader, favAdapter));

            for (int i = 0; i < groups.size(); i++) {
                String group = groups.get(i);
                String displayGroup = (group == null || group.isEmpty()) ? "其他" : group;
                List<Channel> channels = channelRepository.getChannelsByGroup(activeSource.id, group);

                ChannelPresenter presenter = new ChannelPresenter();
                presenter.setFavoriteIds(favIds);
                ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(presenter);
                for (Channel ch : channels) {
                    listRowAdapter.add(ch);
                }

                HeaderItem header = new HeaderItem(headerIndex++, displayGroup);
                newRows.add(new ListRow(header, listRowAdapter));
            }

            HeaderItem settingsHeader = new HeaderItem(headerIndex++, getString(R.string.settings));
            ArrayObjectAdapter settingsRowAdapter = new ArrayObjectAdapter(new SettingsShortcutPresenter());
            settingsRowAdapter.add(SettingsShortcutEntry.INSTANCE);
            newRows.add(new SettingsShortcutListRow(settingsHeader, settingsRowAdapter));

            // Clean ended programs quickly; full EPG fetch runs on separate executor (non-blocking)
            if (activeSource.epgUrl != null && !activeSource.epgUrl.isEmpty()) {
                try {
                    epgRepository.cleanOldPrograms();
                } catch (Exception ignored) {
                }
                final String epgUrl = activeSource.epgUrl;
                if (preferenceManager.shouldAutoRefreshEpg(epgUrl, PreferenceManager.EPG_AUTO_REFRESH_INTERVAL_MS)) {
                    epgRefreshExecutor.execute(() -> {
                        try {
                            epgRepository.loadEpg(epgUrl);
                            preferenceManager.markEpgAutoRefreshSuccess(epgUrl);
                        } catch (Exception e) {
                            Log.w(TAG, "Background EPG refresh failed", e);
                        }
                    });
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    rowsAdapter.clear();
                    for (int i = 0; i < newRows.size(); i++) {
                        rowsAdapter.add(newRows.get(i));
                    }
                    ((MainActivity) getActivity()).showEmptyState(false);
                });
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        epgRefreshExecutor.shutdown();
    }

    private class ItemClickListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof SettingsShortcutEntry && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showSettingsPanel();
                return;
            }
            if (item instanceof Channel) {
                Channel channel = (Channel) item;
                Intent intent = new Intent(getActivity(), PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id);
                intent.putExtra(PlayerActivity.EXTRA_SOURCE_ID, channel.sourceId);
                startActivity(intent);
            }
        }
    }
}
