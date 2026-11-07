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

    /**
     * 某一天的节目（含已经播完的），用于频道菜单右侧的节目单列表；
     * 时间区间取交叠语义：只要跟 [from, to) 有交集就算。
     */
    @Query("SELECT * FROM epg_programs WHERE channelTvgId = :tvgId AND startTime < :to AND endTime > :from ORDER BY startTime")
    List<EpgProgram> getProgramsInRange(String tvgId, long from, long to);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EpgProgram> programs);

    @Query("DELETE FROM epg_programs WHERE endTime < :before")
    void deleteOld(long before);

    @Query("DELETE FROM epg_programs")
    void deleteAll();
}
