package com.whyun.witv.data.db.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "favorite_channels",
    foreignKeys = @ForeignKey(
        entity = Channel.class,
        parentColumns = "id",
        childColumns = "channelId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index(value = "channelId", unique = true)}
)
public class FavoriteChannel {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long channelId;
    public long addedAt;

    public FavoriteChannel() {
    }

    public FavoriteChannel(long channelId, long addedAt) {
        this.channelId = channelId;
        this.addedAt = addedAt;
    }
}
