package com.whyun.witv.ui;

import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.repository.ChannelRepository;
import com.whyun.witv.data.repository.EpgRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends FragmentActivity {

    private AppDatabase db;
    private ChannelRepository channelRepo;
    private EpgRepository epgRepo;
    private SourceListAdapter adapter;
    private EditText epgUrlInput;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        db = AppDatabase.getInstance(this);
        channelRepo = new ChannelRepository(this);
        epgRepo = new EpgRepository(this);

        setupWebHint();
        setupSourceList();
        setupSettings();
    }

    private void setupWebHint() {
        TextView webHint = findViewById(R.id.web_hint);
        String ip = getDeviceIp();
        webHint.setText(String.format("通过浏览器管理：http://%s:9978", ip));
    }

    private void setupSourceList() {
        RecyclerView sourceList = findViewById(R.id.source_list);
        sourceList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SourceListAdapter(new ArrayList<>(), this::onActivateSource);
        sourceList.setAdapter(adapter);
        loadSources();
    }

    private void setupSettings() {
        epgUrlInput = findViewById(R.id.epg_url_input);
        Button saveEpg = findViewById(R.id.btn_save_epg);
        Button reloadEpg = findViewById(R.id.btn_reload_epg);

        executor.execute(() -> {
            M3USource active = db.m3uSourceDao().getActive();
            if (active != null && active.epgUrl != null) {
                runOnUiThread(() -> epgUrlInput.setText(active.epgUrl));
            }
        });

        saveEpg.setOnClickListener(v -> {
            String epgUrl = epgUrlInput.getText().toString().trim();
            executor.execute(() -> {
                M3USource active = db.m3uSourceDao().getActive();
                if (active != null) {
                    active.epgUrl = epgUrl;
                    db.m3uSourceDao().update(active);
                    runOnUiThread(() ->
                            Toast.makeText(this, "EPG 设置已保存", Toast.LENGTH_SHORT).show());
                }
            });
        });

        reloadEpg.setOnClickListener(v -> {
            executor.execute(() -> {
                M3USource active = db.m3uSourceDao().getActive();
                if (active != null && active.epgUrl != null && !active.epgUrl.isEmpty()) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "正在刷新 EPG…", Toast.LENGTH_SHORT).show());
                    try {
                        epgRepo.loadEpg(active.epgUrl);
                        runOnUiThread(() ->
                                Toast.makeText(this, "EPG 刷新完成", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() ->
                                Toast.makeText(this, "EPG 刷新失败: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show());
                    }
                } else {
                    runOnUiThread(() ->
                            Toast.makeText(this, "请先设置 EPG 地址", Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    private void loadSources() {
        executor.execute(() -> {
            List<M3USource> sources = db.m3uSourceDao().getAll();
            runOnUiThread(() -> adapter.updateData(sources));
        });
    }

    private void onActivateSource(M3USource source) {
        executor.execute(() -> {
            db.m3uSourceDao().deactivateAll();
            db.m3uSourceDao().activate(source.id);

            try {
                List<com.whyun.witv.data.db.entity.Channel> channels =
                        db.channelDao().getBySource(source.id);
                if (channels.isEmpty()) {
                    channelRepo.loadSource(source);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            loadSources();
            runOnUiThread(() ->
                    Toast.makeText(this, "已切换到: " + source.name, Toast.LENGTH_SHORT).show());
        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
