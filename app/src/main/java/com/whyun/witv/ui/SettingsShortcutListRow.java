package com.whyun.witv.ui;

import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ObjectAdapter;

/**
 * Browse 左侧「设置」分类行：右侧为 {@link SettingsShortcutEntry}，由用户确认点击后打开设置浮层。
 */
public final class SettingsShortcutListRow extends ListRow {

    public SettingsShortcutListRow(HeaderItem header, ObjectAdapter adapter) {
        super(header, adapter);
    }
}
