package com.whyun.witv.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.FavoriteChannel;

import java.util.List;

@Dao
public interface FavoriteChannelDao {

    @Query("SELECT c.* FROM channels c INNER JOIN favorite_channels f ON c.id = f.channelId " +
           "WHERE c.sourceId = :sourceId ORDER BY f.addedAt DESC")
    List<Channel> getFavoriteChannels(long sourceId);

    @Query("SELECT c.* FROM channels c INNER JOIN favorite_channels f ON c.id = f.channelId " +
           "ORDER BY f.addedAt DESC")
    List<Channel> getAllFavoriteChannels();

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channels WHERE channelId = :channelId)")
    boolean isFavorite(long channelId);

    @Query("SELECT channelId FROM favorite_channels")
    List<Long> getAllFavoriteChannelIds();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(FavoriteChannel favorite);

    @Query("DELETE FROM favorite_channels WHERE channelId = :channelId")
    void deleteByChannelId(long channelId);
}
