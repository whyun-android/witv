package com.whyun.witv.data.repository;

import android.content.Context;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.dao.EpgDao;
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
    private final OkHttpClient httpClient;

    public EpgRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.epgDao = db.epgDao();
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
                List<EpgParser.EpgProgramData> programs = new EpgParser().parse(inputStream);

                // Clear old EPG data and save new
                epgDao.deleteAll();

                List<EpgProgram> entities = new ArrayList<>();
                for (EpgParser.EpgProgramData p : programs) {
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
     * Gets current and next program for a channel by tvg-id.
     */
    public List<EpgProgram> getCurrentAndNext(String tvgId) {
        return epgDao.getCurrentAndNext(tvgId, System.currentTimeMillis());
    }

    /**
     * Deletes EPG programs that have already ended.
     */
    public void cleanOldPrograms() {
        epgDao.deleteOld(System.currentTimeMillis());
    }
}
