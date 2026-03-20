package com.whyun.witv.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.whyun.witv.data.db.entity.EpgChannel;

import java.util.List;

@Dao
public interface EpgChannelDao {
    @Query("SELECT channelId FROM epg_channels WHERE displayName = :displayName LIMIT 1")
    String findChannelIdByDisplayName(String displayName);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EpgChannel> channels);

    @Query("DELETE FROM epg_channels")
    void deleteAll();
}
