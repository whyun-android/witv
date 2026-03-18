package com.whyun.witv.data.db.entity;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "epg_programs",
    indices = {@Index("channelTvgId"), @Index("startTime")}
)
public class EpgProgram {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String channelTvgId;
    public String title;
    public String description;
    public long startTime;
    public long endTime;

    public EpgProgram() {
    }

    @Ignore
    public EpgProgram(String channelTvgId, String title, String description,
                      long startTime, long endTime) {
        this.channelTvgId = channelTvgId;
        this.title = title;
        this.description = description;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
