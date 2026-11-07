package com.whyun.witv.data.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "m3u_sources")
public class M3USource {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    public String url;
    /**
     * 当前生效的 EPG 地址（nullable）。由 {@link #m3uEpgUrl} / {@link #customEpgUrl} / {@link #useCustomEpg}
     * 推导而来，改了那三个字段后要调 {@link #applyEffectiveEpgUrl()} 同步。读 EPG 的地方只认这一个字段。
     */
    public String epgUrl;
    /** 直播源 M3U 头里声明的 x-tvg-url，每次刷新直播源时覆盖写入（nullable） */
    public String m3uEpgUrl;
    /** 用户自己填的 EPG 地址，刷新直播源不会动它（nullable） */
    public String customEpgUrl;
    /** true = 用自定义地址，false = 用直播源内的地址 */
    @ColumnInfo(defaultValue = "0")
    public boolean useCustomEpg;
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

    /**
     * 按开关选出生效地址。选中的那一侧为空时退回另一侧：直播源没声明 EPG 时开关停在「直播源」
     * 不至于整个没节目单；Web 管理页会把实际生效的地址标出来。
     */
    public String resolveEpgUrl() {
        String preferred = useCustomEpg ? customEpgUrl : m3uEpgUrl;
        String fallback = useCustomEpg ? m3uEpgUrl : customEpgUrl;
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return null;
    }

    /** 重新计算 {@link #epgUrl}，返回生效地址是否发生了变化 */
    public boolean applyEffectiveEpgUrl() {
        String next = resolveEpgUrl();
        boolean changed = next == null ? epgUrl != null && !epgUrl.isEmpty() : !next.equals(epgUrl);
        epgUrl = next;
        return changed;
    }
}
