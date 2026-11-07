package com.whyun.witv.ui;

import android.content.Context;
import android.util.TypedValue;

/** 从当前 Activity 主题读取颜色属性（见 res/values/attrs.xml）。 */
final class ThemeColors {

    private ThemeColors() {
    }

    static int get(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
