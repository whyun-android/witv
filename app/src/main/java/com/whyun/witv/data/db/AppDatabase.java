package com.whyun.witv.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.whyun.witv.data.db.dao.ChannelDao;
import com.whyun.witv.data.db.dao.ChannelSourceDao;
import com.whyun.witv.data.db.dao.EpgDao;
import com.whyun.witv.data.db.dao.M3USourceDao;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.EpgProgram;
import com.whyun.witv.data.db.entity.M3USource;

@Database(
    entities = {M3USource.class, Channel.class, ChannelSource.class, EpgProgram.class},
    version = 1,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "witv_database"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }

    public abstract M3USourceDao m3uSourceDao();
    public abstract ChannelDao channelDao();
    public abstract ChannelSourceDao channelSourceDao();
    public abstract EpgDao epgDao();
}
