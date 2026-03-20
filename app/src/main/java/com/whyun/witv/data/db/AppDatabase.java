package com.whyun.witv.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.whyun.witv.data.db.dao.ChannelDao;
import com.whyun.witv.data.db.dao.ChannelSourceDao;
import com.whyun.witv.data.db.dao.EpgChannelDao;
import com.whyun.witv.data.db.dao.EpgDao;
import com.whyun.witv.data.db.dao.FavoriteChannelDao;
import com.whyun.witv.data.db.dao.M3USourceDao;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.EpgChannel;
import com.whyun.witv.data.db.entity.EpgProgram;
import com.whyun.witv.data.db.entity.FavoriteChannel;
import com.whyun.witv.data.db.entity.M3USource;

@Database(
    entities = {M3USource.class, Channel.class, ChannelSource.class, EpgProgram.class, FavoriteChannel.class, EpgChannel.class},
    version = 3,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `favorite_channels` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`channelId` INTEGER NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`channelId`) REFERENCES `channels`(`id`) ON DELETE CASCADE)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_favorite_channels_channelId` ON `favorite_channels` (`channelId`)");
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `epg_channels` (" +
                    "`channelId` TEXT NOT NULL, " +
                    "`displayName` TEXT, " +
                    "PRIMARY KEY(`channelId`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_channels_displayName` ON `epg_channels` (`displayName`)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "witv_database"
                    )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build();
                }
            }
        }
        return INSTANCE;
    }

    public abstract M3USourceDao m3uSourceDao();
    public abstract ChannelDao channelDao();
    public abstract ChannelSourceDao channelSourceDao();
    public abstract EpgDao epgDao();
    public abstract EpgChannelDao epgChannelDao();
    public abstract FavoriteChannelDao favoriteChannelDao();
}
