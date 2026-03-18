package com.whyun.witv.data.db.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "channel_sources",
    foreignKeys = @ForeignKey(
        entity = Channel.class,
        parentColumns = "id",
        childColumns = "channelId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index("channelId")}
)
public class ChannelSource {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long channelId;
    public String url;
    public int priority;  // lower = higher priority

    public ChannelSource() {
    }

    @Ignore
    public ChannelSource(long channelId, String url, int priority) {
        this.channelId = channelId;
        this.url = url;
        this.priority = priority;
    }
}
