package com.whyun.witv.data;

import com.whyun.witv.R;

/** 界面主题。新增主题时在 themes.xml 加一个 Theme.WiTV.* 样式并在这里登记。 */
public enum AppTheme {
    /** 品类标准做法：深色半透明面板 + 蓝色焦点 */
    STANDARD("standard", R.style.Theme_WiTV),
    /** 电视台包装：演播室深蓝 + 白色字幕条焦点 + 红色号码块 */
    BROADCAST("broadcast", R.style.Theme_WiTV_Broadcast);

    private final String id;
    private final int styleRes;

    AppTheme(String id, int styleRes) {
        this.id = id;
        this.styleRes = styleRes;
    }

    public String getId() {
        return id;
    }

    public int getStyleRes() {
        return styleRes;
    }

    public static AppTheme fromId(String id) {
        if (id != null) {
            for (AppTheme t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return STANDARD;
    }
}
