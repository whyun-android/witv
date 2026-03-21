package com.whyun.witv.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.repository.ChannelRepository;
import com.whyun.witv.data.repository.EpgRepository;
import com.whyun.witv.player.PlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsCollapsibleFragment extends Fragment
        implements SettingsPanelAdapter.Listener, SettingsMainMenuAdapter.Listener {

    public static final int CAT_ADDRESS = 1;
    public static final int CAT_SWITCH = 2;
    public static final int CAT_EPG = 3;
    public static final int CAT_PLAYBACK = 4;
    public static final int CAT_HELP = 5;

    private SettingsPanelHost host;

    private LinearLayout menuRow;
    private FrameLayout submenuContainer;
    private RecyclerView submenuRecycler;
    private RecyclerView mainMenuRecycler;
    private SettingsPanelAdapter submenuAdapter;
    private SettingsMainMenuAdapter mainMenuAdapter;

    private ExecutorService executor;
    private PreferenceManager preferenceManager;
    private ChannelRepository channelRepository;
    private EpgRepository epgRepository;
    private AppDatabase db;

    private List<M3USource> m3uCache = new ArrayList<>();
    private List<ChannelSource> channelStreamCache = new ArrayList<>();
    private String cachedEpgUrl = "";

    /** 0 = 子菜单未打开；否则为 {@link #CAT_ADDRESS}… */
    private int openCategory = 0;
    private int lastOpenedCategory = CAT_ADDRESS;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof SettingsPanelHost) {
            host = (SettingsPanelHost) context;
        } else {
            throw new IllegalStateException("Activity must implement SettingsPanelHost");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_collapsible, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        menuRow = view.findViewById(R.id.settings_menu_row);
        submenuContainer = view.findViewById(R.id.settings_submenu_container);
        submenuRecycler = view.findViewById(R.id.settings_submenu_recycler);
        mainMenuRecycler = view.findViewById(R.id.settings_main_menu);

        submenuRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        submenuAdapter = new SettingsPanelAdapter(this);
        submenuRecycler.setAdapter(submenuAdapter);

        mainMenuRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mainMenuAdapter = new SettingsMainMenuAdapter(this);
        mainMenuRecycler.setAdapter(mainMenuAdapter);

        preferenceManager = new PreferenceManager(requireContext());
        channelRepository = new ChannelRepository(requireContext());
        epgRepository = new EpgRepository(requireContext());
        db = AppDatabase.getInstance(requireContext());
        executor = Executors.newSingleThreadExecutor();

        if (savedInstanceState != null) {
            openCategory = savedInstanceState.getInt("open_category", 0);
            lastOpenedCategory = savedInstanceState.getInt("last_open_category", CAT_ADDRESS);
        }

        refreshMainMenuItems();
        if (openCategory != 0) {
            applyMainMenuLayoutParams(true);
            submenuContainer.setVisibility(View.VISIBLE);
            submenuAdapter.setRows(buildSubmenuRows(openCategory));
        } else {
            applyMainMenuLayoutParams(false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("open_category", openCategory);
        outState.putInt("last_open_category", lastOpenedCategory);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDataFromDb();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    /**
     * 子菜单打开时先收子菜单，再关整抽屉。
     *
     * @return true 表示已消费（仅收了子菜单）
     */
    public boolean handleBack() {
        if (openCategory != 0 && submenuContainer.getVisibility() == View.VISIBLE) {
            closeSubmenu();
            return true;
        }
        return false;
    }

    /** 关闭整条设置抽屉时收起左侧子菜单，避免下次打开仍停在子层 */
    public void onSettingsDrawerDismiss() {
        openCategory = 0;
        if (submenuContainer != null && mainMenuRecycler != null && menuRow != null) {
            submenuContainer.setVisibility(View.GONE);
            applyMainMenuLayoutParams(false);
        }
    }

    public void refreshAndFocus() {
        refreshDataFromDb();
        mainMenuRecycler.post(() -> {
            if (openCategory != 0 && submenuContainer.getVisibility() == View.VISIBLE) {
                submenuRecycler.scrollToPosition(0);
                submenuRecycler.post(() -> {
                    if (submenuRecycler.getChildCount() > 0) {
                        submenuRecycler.getChildAt(0).requestFocus();
                    } else {
                        submenuRecycler.requestFocus();
                    }
                });
            } else {
                int pos = mainMenuAdapter.positionOfCategory(lastOpenedCategory);
                mainMenuRecycler.scrollToPosition(pos);
                mainMenuRecycler.post(() -> {
                    RecyclerView.ViewHolder vh = mainMenuRecycler.findViewHolderForAdapterPosition(pos);
                    if (vh != null) {
                        vh.itemView.requestFocus();
                    } else {
                        mainMenuRecycler.requestFocus();
                    }
                });
            }
        });
    }

    public void refreshDataFromDb() {
        executor.execute(() -> {
            List<M3USource> sources = db.m3uSourceDao().getAll();
            M3USource active = db.m3uSourceDao().getActive();
            String epg = "";
            if (active != null && active.epgUrl != null) {
                epg = active.epgUrl;
            }

            List<ChannelSource> streams = new ArrayList<>();
            if (host.shouldShowStreamSwitchGroup() && requireActivity() instanceof PlayerActivity) {
                long cid = ((PlayerActivity) requireActivity()).getCurrentChannelIdForPanel();
                if (cid > 0) {
                    streams = channelRepository.getChannelSources(cid);
                }
            }

            List<M3USource> sourcesCopy = new ArrayList<>(sources);
            List<ChannelSource> streamsCopy = new ArrayList<>(streams);
            String epgFinal = epg;
            if (!isAdded() || getActivity() == null) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                m3uCache = sourcesCopy;
                channelStreamCache = streamsCopy;
                cachedEpgUrl = epgFinal;
                refreshMainMenuItems();
                rebuildSubmenuIfOpen();
            });
        });
    }

    private void refreshMainMenuItems() {
        if (mainMenuAdapter == null || !isAdded()) {
            return;
        }
        Context ctx = requireContext();
        List<SettingsMainMenuAdapter.Item> items = new ArrayList<>();
        items.add(new SettingsMainMenuAdapter.Item(CAT_ADDRESS,
                ctx.getString(R.string.settings_group_address), true));
        if (host.shouldShowStreamSwitchGroup()) {
            items.add(new SettingsMainMenuAdapter.Item(CAT_SWITCH,
                    ctx.getString(R.string.settings_group_switch_stream), true));
        }
        items.add(new SettingsMainMenuAdapter.Item(CAT_EPG,
                ctx.getString(R.string.settings_group_epg), true));
        items.add(new SettingsMainMenuAdapter.Item(CAT_PLAYBACK,
                ctx.getString(R.string.settings_group_playback), true));
        items.add(new SettingsMainMenuAdapter.Item(CAT_HELP,
                ctx.getString(R.string.settings_help_title), false));
        mainMenuAdapter.setItems(items);
    }

    private void rebuildSubmenuIfOpen() {
        if (openCategory != 0 && submenuAdapter != null) {
            submenuAdapter.setRows(buildSubmenuRows(openCategory));
        }
    }

    private void openSubmenu(int category) {
        lastOpenedCategory = category;
        openCategory = category;
        submenuContainer.setVisibility(View.VISIBLE);
        applyMainMenuLayoutParams(true);
        submenuAdapter.setRows(buildSubmenuRows(category));
        submenuRecycler.post(() -> {
            submenuRecycler.scrollToPosition(0);
            submenuRecycler.post(() -> {
                if (submenuRecycler.getChildCount() > 0) {
                    submenuRecycler.getChildAt(0).requestFocus();
                } else {
                    submenuRecycler.requestFocus();
                }
            });
        });
    }

    private void closeSubmenu() {
        openCategory = 0;
        submenuContainer.setVisibility(View.GONE);
        applyMainMenuLayoutParams(false);
        int pos = mainMenuAdapter.positionOfCategory(lastOpenedCategory);
        mainMenuRecycler.scrollToPosition(pos);
        mainMenuRecycler.post(() -> {
            RecyclerView.ViewHolder vh = mainMenuRecycler.findViewHolderForAdapterPosition(pos);
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                mainMenuRecycler.requestFocus();
            }
        });
    }

    private void applyMainMenuLayoutParams(boolean submenuOpen) {
        if (menuRow == null || submenuContainer == null || mainMenuRecycler == null) {
            return;
        }
        LinearLayout.LayoutParams lpSub =
                (LinearLayout.LayoutParams) submenuContainer.getLayoutParams();
        LinearLayout.LayoutParams lpMain =
                (LinearLayout.LayoutParams) mainMenuRecycler.getLayoutParams();
        if (submenuOpen) {
            lpSub.width = 0;
            lpSub.weight = 1f;
            lpMain.width = dp(240);
            lpMain.weight = 0f;
        } else {
            lpSub.width = 0;
            lpSub.weight = 0f;
            lpMain.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lpMain.weight = 0f;
        }
        submenuContainer.setLayoutParams(lpSub);
        mainMenuRecycler.setLayoutParams(lpMain);
        menuRow.requestLayout();
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density + 0.5f);
    }

    private List<SettingsPanelAdapter.Row> buildSubmenuRows(int category) {
        List<SettingsPanelAdapter.Row> rows = new ArrayList<>();
        Context ctx = requireContext();
        switch (category) {
            case CAT_ADDRESS:
                rows.add(new SettingsPanelAdapter.WebHintRow(buildWebHint(ctx)));
                for (M3USource s : m3uCache) {
                    rows.add(new SettingsPanelAdapter.M3USourceRow(s));
                }
                break;
            case CAT_SWITCH:
                if (channelStreamCache.size() <= 1) {
                    rows.add(new SettingsPanelAdapter.EmptyHintRow(
                            ctx.getString(R.string.switch_stream_empty)));
                } else {
                    PlayerManager pm = host.getPlayerManagerOrNull();
                    int cur = pm != null ? pm.getCurrentSourceIndex() : 0;
                    for (int i = 0; i < channelStreamCache.size(); i++) {
                        rows.add(new SettingsPanelAdapter.StreamRow(i, channelStreamCache.get(i),
                                i == cur));
                    }
                }
                break;
            case CAT_EPG:
                rows.add(new SettingsPanelAdapter.EpgRow(cachedEpgUrl));
                break;
            case CAT_PLAYBACK:
                rows.add(new SettingsPanelAdapter.CheckRow(SettingsPanelAdapter.CheckRow.Kind.AUTO_PLAY,
                        preferenceManager.isAutoPlayLastEnabled(),
                        ctx.getString(R.string.auto_play_last), null));
                rows.add(new SettingsPanelAdapter.CheckRow(SettingsPanelAdapter.CheckRow.Kind.LOAD_SPEED,
                        preferenceManager.isShowLoadSpeedOverlay(),
                        ctx.getString(R.string.show_load_speed_overlay),
                        ctx.getString(R.string.show_load_speed_overlay_hint)));
                break;
            default:
                break;
        }
        return rows;
    }

    private static String buildWebHint(Context ctx) {
        return String.format(Locale.US, "通过浏览器管理：http://%s:9978", getDeviceIp(ctx));
    }

    private static String getAppVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static String getDeviceIp(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
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

    @Override
    public void onMainMenuItemClick(int categoryId) {
        if (categoryId == CAT_HELP) {
            showHelpDialog();
            return;
        }
        if (openCategory == categoryId && submenuContainer.getVisibility() == View.VISIBLE) {
            return;
        }
        openSubmenu(categoryId);
    }

    private void showHelpDialog() {
        Context ctx = requireContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int gapAfterVersion = (int) (10 * density);
        int scrollMaxH = (int) (280 * density);

        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(pad, pad, pad, pad);
        outer.setBackgroundColor(ContextCompat.getColor(ctx, R.color.card_bg));

        TextView verLine = new TextView(ctx);
        verLine.setText(getString(R.string.settings_help_version_line, getAppVersionName(ctx)));
        verLine.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        verLine.setTextSize(14);
        outer.addView(verLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scrollMaxH));

        TextView body = new TextView(ctx);
        body.setText(R.string.settings_help_body);
        body.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        body.setTextSize(15);
        body.setPadding(0, gapAfterVersion, 0, 0);
        body.setLineSpacing(0, 1.35f);
        scrollView.addView(body);
        outer.addView(scrollView);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_help_title)
                .setView(outer)
                .setPositiveButton(android.R.string.ok, (d, w) -> d.dismiss())
                .show();
    }

    @Override
    public void onActivateM3U(M3USource source) {
        executor.execute(() -> {
            db.m3uSourceDao().deactivateAll();
            db.m3uSourceDao().activate(source.id);
            try {
                if (db.channelDao().getBySource(source.id).isEmpty()) {
                    channelRepository.loadSource(source);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!isAdded() || getActivity() == null) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                Toast.makeText(requireContext(), "已切换到: " + source.name, Toast.LENGTH_SHORT).show();
                refreshDataFromDb();
            });
        });
    }

    @Override
    public void onSaveEpg(String url) {
        executor.execute(() -> {
            M3USource active = db.m3uSourceDao().getActive();
            if (active != null) {
                active.epgUrl = url;
                db.m3uSourceDao().update(active);
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    cachedEpgUrl = url;
                    Toast.makeText(requireContext(), "EPG 设置已保存", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onReloadEpg(String url) {
        executor.execute(() -> {
            M3USource active = db.m3uSourceDao().getActive();
            if (active != null && active.epgUrl != null && !active.epgUrl.isEmpty()) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "正在刷新 EPG…", Toast.LENGTH_SHORT).show());
                try {
                    epgRepository.loadEpg(active.epgUrl);
                    preferenceManager.markEpgAutoRefreshSuccess(active.epgUrl);
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "EPG 刷新完成", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "EPG 刷新失败: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show());
                }
            } else {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "请先设置 EPG 地址", Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onStreamSwitch(int index) {
        host.onManualStreamSwitch(index);
        rebuildSubmenuIfOpen();
    }

    @Override
    public void onAutoPlay(boolean checked) {
        preferenceManager.setAutoPlayLast(checked);
        Toast.makeText(requireContext(),
                checked ? "已开启启动播放上次频道" : "已关闭启动播放上次频道",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLoadSpeed(boolean checked) {
        preferenceManager.setShowLoadSpeedOverlay(checked);
        Toast.makeText(requireContext(),
                checked ? "已开启播放页加载速度显示" : "已关闭播放页加载速度显示",
                Toast.LENGTH_SHORT).show();
        host.onPlaybackOverlayPreferenceChanged();
    }
}
