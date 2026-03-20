package com.whyun.witv.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.whyun.witv.data.db.entity.EpgProgram;

import java.util.List;

@Dao
public interface EpgDao {
    @Query("SELECT * FROM epg_programs WHERE channelTvgId = :tvgId AND endTime > :now ORDER BY startTime LIMIT 2")
    List<EpgProgram> getCurrentAndNext(String tvgId, long now);

    /**
     * Upcoming programs (not yet ended), ordered by start time.
     *
     * @param limit max rows (Room binds this as integer)
     */
    @Query("SELECT * FROM epg_programs WHERE channelTvgId = :tvgId AND endTime > :now ORDER BY startTime LIMIT :limit")
    List<EpgProgram> getUpcomingPrograms(String tvgId, long now, int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EpgProgram> programs);

    @Query("DELETE FROM epg_programs WHERE endTime < :before")
    void deleteOld(long before);

    @Query("DELETE FROM epg_programs")
    void deleteAll();
}
