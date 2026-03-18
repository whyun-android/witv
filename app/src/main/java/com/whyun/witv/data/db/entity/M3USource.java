package com.whyun.witv.data.db.entity;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "m3u_sources")
public class M3USource {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    public String url;
    public String epgUrl;  // nullable, from x-tvg-url in M3U header
    public long addedAt;
    public boolean isActive;

    public M3USource() {
    }

    @Ignore
    public M3USource(String name, String url, String epgUrl, long addedAt, boolean isActive) {
        this.name = name;
        this.url = url;
        this.epgUrl = epgUrl;
        this.addedAt = addedAt;
        this.isActive = isActive;
    }
}
