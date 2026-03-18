package com.whyun.witv.data.db.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "channels",
    foreignKeys = @ForeignKey(
        entity = M3USource.class,
        parentColumns = "id",
        childColumns = "sourceId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {@Index("sourceId"), @Index("tvgId")}
)
public class Channel {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long sourceId;
    public String tvgId;
    public String tvgName;
    public String displayName;
    public String logoUrl;
    public String groupTitle;
    public int sortOrder;

    public Channel() {
    }

    @Ignore
    public Channel(long sourceId, String tvgId, String tvgName, String displayName,
                   String logoUrl, String groupTitle, int sortOrder) {
        this.sourceId = sourceId;
        this.tvgId = tvgId;
        this.tvgName = tvgName;
        this.displayName = displayName;
        this.logoUrl = logoUrl;
        this.groupTitle = groupTitle;
        this.sortOrder = sortOrder;
    }
}
