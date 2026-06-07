package com.whyun.witv.ui;

import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class SettingsCollapsibleFragmentTest {

    @Test
    public void onMainMenuItemFocusedDoesNotScheduleSubmenuOpen()
            throws Exception {
        SettingsCollapsibleFragment fragment = new SettingsCollapsibleFragment();
        FrameLayout submenuContainer = new FrameLayout(
                ApplicationProvider.getApplicationContext());
        submenuContainer.setVisibility(View.GONE);

        setField(fragment, "submenuContainer", submenuContainer);
        setField(fragment, "openCategory", 0);

        fragment.onMainMenuItemFocused(SettingsCollapsibleFragment.CAT_EPG);

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertNull(getField(fragment, "pendingSubmenuOpen"));
        assertEquals(0, getField(fragment, "openCategory"));
    }

    @Test
    public void findFirstFocusableReturnsNestedFocusableControl() throws Exception {
        LinearLayout row = new LinearLayout(ApplicationProvider.getApplicationContext());
        TextView hint = new TextView(ApplicationProvider.getApplicationContext());
        Button action = new Button(ApplicationProvider.getApplicationContext());
        row.addView(hint);
        row.addView(action);

        Method method = SettingsCollapsibleFragment.class
                .getDeclaredMethod("findFirstFocusable", View.class);
        method.setAccessible(true);

        assertSame(action, method.invoke(null, row));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
