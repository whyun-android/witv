package com.whyun.witv.data.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.whyun.witv.data.db.entity.M3USource;

import java.util.List;

@Dao
public interface M3USourceDao {
    @Query("SELECT * FROM m3u_sources ORDER BY addedAt DESC")
    List<M3USource> getAll();

    @Query("SELECT * FROM m3u_sources WHERE isActive = 1 LIMIT 1")
    M3USource getActive();

    @Query("SELECT * FROM m3u_sources WHERE id = :id")
    M3USource getById(long id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(M3USource source);

    @Update
    void update(M3USource source);

    @Delete
    void delete(M3USource source);

    @Query("UPDATE m3u_sources SET isActive = 0")
    void deactivateAll();

    @Query("UPDATE m3u_sources SET isActive = 1 WHERE id = :id")
    void activate(long id);
}
