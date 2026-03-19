package com.whyun.witv.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.whyun.witv.data.db.entity.Channel;

import java.util.List;

@Dao
public interface ChannelDao {
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId ORDER BY sortOrder")
    List<Channel> getBySource(long sourceId);

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE sourceId = :sourceId")
    List<String> getGroups(long sourceId);

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND groupTitle = :group ORDER BY sortOrder")
    List<Channel> getByGroup(long sourceId, String group);

    @Query("SELECT * FROM channels WHERE id = :id")
    Channel getById(long id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Channel channel);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Channel> channels);

    @Update
    void update(Channel channel);

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    void deleteBySource(long sourceId);

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    void deleteByIds(List<Long> ids);
}
