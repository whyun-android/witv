package com.whyun.witv.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "epg_channels",
    indices = {@Index("displayName")}
)
public class EpgChannel {
    @PrimaryKey
    @NonNull
    public String channelId;

    public String displayName;

    public EpgChannel() {
        this.channelId = "";
    }

    @Ignore
    public EpgChannel(@NonNull String channelId, String displayName) {
        this.channelId = channelId;
        this.displayName = displayName;
    }
}
