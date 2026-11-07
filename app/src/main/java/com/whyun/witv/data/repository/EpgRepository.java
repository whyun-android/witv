package com.whyun.witv.data.repository;

import android.content.Context;
import android.util.Log;

import com.whyun.witv.data.GzipAwareStreams;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.dao.EpgChannelDao;
import com.whyun.witv.data.db.dao.EpgDao;
import com.whyun.witv.data.db.entity.EpgChannel;
import com.whyun.witv.data.db.entity.EpgProgram;
import com.whyun.witv.data.parser.EpgParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Repository for EPG (Electronic Program Guide) data.
 */
public class EpgRepository {

    /** EPG 下载/解析/入库的日志 tag，失败一律走这个 tag，方便用 adb logcat 过滤。 */
    public static final String TAG = "WiTVEpg";

    private final EpgDao epgDao;
    private final EpgChannelDao epgChannelDao;
    private final PreferenceManager preferenceManager;
    private final OkHttpClient httpClient;

    public EpgRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.epgDao = db.epgDao();
        this.epgChannelDao = db.epgChannelDao();
        this.preferenceManager = new PreferenceManager(context);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Loads EPG data from the given URL, parses it, and saves to database.
     *
     * @param epgUrl The URL of the XMLTV EPG data
     * @throws IOException if network request or parsing fails
     */
    public void loadEpg(String epgUrl) throws IOException, XmlPullParserException {
        Request request = new Request.Builder()
                .url(epgUrl)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to fetch EPG: " + response.code());
            }
            // 公开 EPG 源基本都提供 .xml.gz；这类响应是 Content-Type: application/gzip，
            // 不带 Content-Encoding，OkHttp 不会自动解压，必须自己按魔数判断
            // 先把整包读完再解析：解析是 CPU 活（7MB 的 XMLTV 在盒子上要几十秒），
            // 边下边解会让 OkHttp 的 readTimeout 在解析间隙里超时，大源永远拉不下来。
            byte[] payload = response.body().bytes();
            try (InputStream inputStream =
                         GzipAwareStreams.maybeDecompress(new ByteArrayInputStream(payload))) {
                EpgParser.ParseResult result = new EpgParser().parseFull(inputStream);

                epgDao.deleteAll();
                epgChannelDao.deleteAll();

                List<EpgChannel> channelEntities = new ArrayList<>();
                for (EpgParser.EpgChannelData ch : result.channels) {
                    channelEntities.add(new EpgChannel(ch.channelId, ch.displayName));
                }
                if (!channelEntities.isEmpty()) {
                    epgChannelDao.insertAll(channelEntities);
                }

                List<EpgProgram> entities = new ArrayList<>();
                for (EpgParser.EpgProgramData p : result.programs) {
                    entities.add(new EpgProgram(
                            p.channelId,
                            p.title,
                            p.description,
                            p.startTime,
                            p.endTime
                    ));
                }
                if (!entities.isEmpty()) {
                    epgDao.insertAll(entities);
                }
            }
        }
    }

    /**
     * Gets current and next program for a channel.
     * First tries matching by tvgId; falls back to tvgName via XMLTV channel display-name.
     */
    public List<EpgProgram> getCurrentAndNext(String tvgId, String tvgName) {
        long now = System.currentTimeMillis();

        if (tvgId != null && !tvgId.isEmpty()) {
            List<EpgProgram> programs = epgDao.getCurrentAndNext(tvgId, now);
            if (!programs.isEmpty()) {
                return programs;
            }
        }

        if (tvgName != null && !tvgName.isEmpty()) {
            String mappedId = epgChannelDao.findChannelIdByDisplayName(tvgName);
            if (mappedId != null && !mappedId.isEmpty()) {
                return epgDao.getCurrentAndNext(mappedId, now);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Gets up to {@code limit} upcoming programs for a channel (same matching rules as {@link #getCurrentAndNext}).
     */
    public List<EpgProgram> getUpcomingPrograms(String tvgId, String tvgName, int limit) {
        if (limit < 1) {
            limit = 1;
        }
        long now = System.currentTimeMillis();

        if (tvgId != null && !tvgId.isEmpty()) {
            List<EpgProgram> programs = epgDao.getUpcomingPrograms(tvgId, now, limit);
            if (!programs.isEmpty()) {
                return programs;
            }
        }

        if (tvgName != null && !tvgName.isEmpty()) {
            String mappedId = epgChannelDao.findChannelIdByDisplayName(tvgName);
            if (mappedId != null && !mappedId.isEmpty()) {
                return epgDao.getUpcomingPrograms(mappedId, now, limit);
            }
        }

        return new ArrayList<>();
    }

    /**
     * 某个频道当天的节目（包含已经播完的），用于频道菜单右侧的节目单列表；
     * 匹配规则与 {@link #getCurrentAndNext} 一致（先 tvgId，再退回 tvgName）。
     */
    public List<EpgProgram> getDayPrograms(String tvgId, String tvgName, long from, long to) {
        if (tvgId != null && !tvgId.isEmpty()) {
            List<EpgProgram> programs = epgDao.getProgramsInRange(tvgId, from, to);
            if (!programs.isEmpty()) {
                return programs;
            }
        }

        if (tvgName != null && !tvgName.isEmpty()) {
            String mappedId = epgChannelDao.findChannelIdByDisplayName(tvgName);
            if (mappedId != null && !mappedId.isEmpty()) {
                return epgDao.getProgramsInRange(mappedId, from, to);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Deletes EPG programs that have already ended.
     */
    public void cleanOldPrograms() {
        epgDao.deleteOld(System.currentTimeMillis());
    }

    /**
     * EPG 刷新的统一入口：判断该不该刷 → 下载解析入库 → 记录结果，三件事收在一处。
     *
     * <p>两个调用方复用它：{@link ChannelRepository} 在 M3U 源加载成功后做后台自动刷新；
     * 设置页与 Web 管理页在用户手动点「刷新EPG」时强制刷新。失败一律打 {@link #TAG}
     * 的 {@link Log#w}（带 URL 和异常摘要），不再是静默的 {@code printStackTrace()}。
     *
     * @param epgUrl EPG 源地址；为空时直接跳过
     * @param force  {@code true} 时忽略节流强制刷新（用户主动触发用）；{@code false} 时按
     *               {@link PreferenceManager#shouldAutoRefreshEpg(String, long)} 判断
     *               （URL 变了、或距上次成功已满 {@link PreferenceManager#EPG_AUTO_REFRESH_INTERVAL_MS}）
     * @return 刷新结果，供调用方做 Toast / JSON 反馈
     */
    public RefreshResult refreshIfStale(String epgUrl, boolean force) {
        if (epgUrl == null || epgUrl.isEmpty()) {
            return RefreshResult.skipped();
        }
        if (!force && !preferenceManager.shouldAutoRefreshEpg(
                epgUrl, PreferenceManager.EPG_AUTO_REFRESH_INTERVAL_MS)) {
            return RefreshResult.skipped();
        }
        try {
            loadEpg(epgUrl);
            // 只有成功才写时间戳，节流才真正生效：失败不记录，下次该刷还会重试
            preferenceManager.markEpgAutoRefreshSuccess(epgUrl);
            Log.i(TAG, "EPG refreshed: " + epgUrl);
            return RefreshResult.ok();
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
            Log.w(TAG, "EPG refresh failed: url=" + epgUrl + ", reason=" + reason, e);
            return RefreshResult.failed(reason);
        }
    }

    /**
     * EPG 刷新结果。{@code refreshed == false} 表示被节流跳过（未真正下载）。
     */
    public static final class RefreshResult {
        /** 是否真正执行了刷新（被节流跳过时为 false） */
        public final boolean refreshed;
        /** 是否成功；被跳过时视为成功（无需刷新） */
        public final boolean success;
        /** 失败原因，成功或跳过后为 null */
        public final String errorMessage;

        private RefreshResult(boolean refreshed, boolean success, String errorMessage) {
            this.refreshed = refreshed;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        static RefreshResult skipped() {
            return new RefreshResult(false, true, null);
        }

        static RefreshResult ok() {
            return new RefreshResult(true, true, null);
        }

        static RefreshResult failed(String errorMessage) {
            return new RefreshResult(true, false, errorMessage);
        }
    }
}
