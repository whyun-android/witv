package com.whyun.witv.ui;

import android.content.ComponentName;
import android.content.pm.ResolveInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class LauncherHelperTest {

    private static LauncherHelper.AppEntry entry(String label, String pkg, String cls) {
        return new LauncherHelper.AppEntry(label, new ComponentName(pkg, cls), null);
    }

    @Test
    public void dedupeAndSortDropsSelfPackage() {
        List<LauncherHelper.AppEntry> out = LauncherHelper.dedupeAndSort(Arrays.asList(
                entry("WiTV", "com.whyun.witv", "com.whyun.witv.ui.HomeAlias"),
                entry("设置", "com.android.tv.settings", "Main")), "com.whyun.witv");

        assertEquals(1, out.size());
        assertEquals("com.android.tv.settings", out.get(0).component.getPackageName());
    }

    @Test
    public void dedupeAndSortKeepsFirstEntryPerPackage() {
        // Leanback 入口先加入，同包的手机启动器入口应被丢弃
        List<LauncherHelper.AppEntry> out = LauncherHelper.dedupeAndSort(Arrays.asList(
                entry("某应用 TV", "com.example.app", "TvMain"),
                entry("某应用", "com.example.app", "PhoneMain")), "com.whyun.witv");

        assertEquals(1, out.size());
        assertEquals("TvMain", out.get(0).component.getClassName());
    }

    @Test
    public void isRealHomeRejectsSetupWizardAndFallbackHome() {
        ResolveInfo launcher = new ResolveInfo();
        ResolveInfo provision = new ResolveInfo();
        provision.priority = 1;
        ResolveInfo fallback = new ResolveInfo();
        fallback.priority = -1000;

        assertTrue(LauncherHelper.isRealHome(launcher));
        assertFalse(LauncherHelper.isRealHome(provision));
        assertFalse(LauncherHelper.isRealHome(fallback));
    }

    @Test
    public void dedupeAndSortOrdersByLabel() {
        List<LauncherHelper.AppEntry> out = LauncherHelper.dedupeAndSort(Arrays.asList(
                entry("Zeta", "z.pkg", "Z"),
                entry("Alpha", "a.pkg", "A"),
                entry("Mid", "m.pkg", "M")), "com.whyun.witv");

        assertEquals("Alpha", out.get(0).label);
        assertEquals("Mid", out.get(1).label);
        assertEquals("Zeta", out.get(2).label);
    }
}
