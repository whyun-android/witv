package com.whyun.witv.data.repository;

import android.content.Context;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.dao.EpgChannelDao;
import com.whyun.witv.data.db.dao.EpgDao;
import com.whyun.witv.data.db.entity.EpgChannel;
import com.whyun.witv.data.db.entity.EpgProgram;
import com.whyun.witv.data.parser.EpgParser;

import org.xmlpull.v1.XmlPullParserException;

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

    private final EpgDao epgDao;
    private final EpgChannelDao epgChannelDao;
    private final OkHttpClient httpClient;

    public EpgRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.epgDao = db.epgDao();
        this.epgChannelDao = db.epgChannelDao();
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
            try (InputStream inputStream = response.body().byteStream()) {
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
     * Deletes EPG programs that have already ended.
     */
    public void cleanOldPrograms() {
        epgDao.deleteOld(System.currentTimeMillis());
    }
}
