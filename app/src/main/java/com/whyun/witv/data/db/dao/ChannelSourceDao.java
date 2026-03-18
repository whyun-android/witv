package com.whyun.witv.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.whyun.witv.data.db.entity.ChannelSource;

import java.util.List;

@Dao
public interface ChannelSourceDao {
    @Query("SELECT * FROM channel_sources WHERE channelId = :channelId ORDER BY priority")
    List<ChannelSource> getByChannel(long channelId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ChannelSource> sources);

    @Query("DELETE FROM channel_sources WHERE channelId IN (SELECT id FROM channels WHERE sourceId = :sourceId)")
    void deleteBySource(long sourceId);
}
