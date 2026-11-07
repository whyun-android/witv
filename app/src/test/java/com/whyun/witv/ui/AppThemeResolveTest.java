package com.whyun.witv.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;

import androidx.test.core.app.ApplicationProvider;

import com.whyun.witv.R;
import com.whyun.witv.data.AppTheme;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** 两个主题都能解析出颜色属性，且焦点样式确实不同 */
@RunWith(RobolectricTestRunner.class)
public class AppThemeResolveTest {

    private static int resolve(AppTheme theme, int attr) {
        Context base = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(base, theme.getStyleRes());
        return ThemeColors.get(themed, attr);
    }

    @Test
    public void standard_focusIsBlueWithWhiteText() {
        assertEquals(0xFF1E88E5, resolve(AppTheme.STANDARD, R.attr.witvFocusBg));
        assertEquals(0xFFFFFFFF, resolve(AppTheme.STANDARD, R.attr.witvOnFocus));
    }

    @Test
    public void broadcast_focusIsWhiteBarWithDarkText() {
        assertEquals(0xFFFFFFFF, resolve(AppTheme.BROADCAST, R.attr.witvFocusBg));
        assertEquals(0xFF081434, resolve(AppTheme.BROADCAST, R.attr.witvOnFocus));
        assertEquals(0xFFD7261E, resolve(AppTheme.BROADCAST, R.attr.witvFocusAccent));
    }

    /** 菜单面板必须半透明，下面能看到视频 */
    @Test
    public void menuPanelsAreTranslucent() {
        for (AppTheme t : AppTheme.values()) {
            int alpha = resolve(t, R.attr.witvMenuListBg) >>> 24;
            assertNotEquals(t.getId(), 0xFF, alpha);
        }
    }
}
