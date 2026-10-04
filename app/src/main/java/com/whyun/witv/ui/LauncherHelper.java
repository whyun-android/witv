package com.whyun.witv.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.whyun.witv.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「作为桌面启动」相关操作。
 *
 * <p>普通应用无法用代码把自己设成默认桌面，也清不掉别的应用的默认设置；能做的只有：
 * 开关自己的 HOME 候选组件（manifest 里的 {@code HomeAlias}），再引导系统弹出桌面选择框。
 * 开关状态由 PackageManager 保存，不另存 SharedPreferences，免得两边不一致。
 */
public final class LauncherHelper {

    private static final String HOME_ALIAS = "com.whyun.witv.ui.HomeAlias";
    private static final String CHOOSER_RESET_ALIAS = "com.whyun.witv.ui.HomeChooserResetAlias";

    /** 切换面板里的一行：桌面或可启动应用 */
    public static final class AppEntry {
        public final String label;
        public final ComponentName component;
        @Nullable
        public final Drawable icon;

        public AppEntry(String label, ComponentName component, @Nullable Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private LauncherHelper() {
    }

    public static boolean isHomeEnabled(Context context) {
        int state = context.getPackageManager()
                .getComponentEnabledSetting(homeAlias(context));
        // manifest 里默认 enabled=false，所以 DEFAULT 也算关闭
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setHomeEnabled(Context context, boolean enabled) {
        setComponentEnabled(context, homeAlias(context), enabled);
    }

    /** 当前系统默认桌面是否就是 WiTV（用户已选「始终」） */
    public static boolean isDefaultHome(Context context) {
        ResolveInfo ri = context.getPackageManager()
                .resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && context.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * 引导用户重新选择默认桌面：优先跳系统「默认桌面」设置页；
     * 很多电视盒子没有这个页面，就短暂启用一个伪 HOME 组件让系统丢弃已保存的默认值，
     * 再发 HOME intent 触发选择框。
     */
    public static void requestChooseDefaultHome(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        Intent settings = new Intent(Settings.ACTION_HOME_SETTINGS);
        if (settings.resolveActivity(pm) != null) {
            try {
                activity.startActivity(settings);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // 有些 ROM 声明了却不让第三方打开，走下面的兜底
            }
        }
        ComponentName reset = new ComponentName(activity, CHOOSER_RESET_ALIAS);
        setComponentEnabled(activity, reset, true);
        try {
            activity.startActivity(homeIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.app_switcher_launch_failed, Toast.LENGTH_SHORT).show();
        } finally {
            setComponentEnabled(activity, reset, false);
        }
    }

    /** 除 WiTV 以外的所有桌面 */
    public static List<AppEntry> queryOtherHomes(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppEntry> entries = new ArrayList<>();
        for (AppEntry e : toEntries(pm, pm.queryIntentActivities(homeIntent(), 0))) {
            // 系统设置的 FallbackHome 只在开机解锁前兜底，直接打开是空白页
            if (!e.component.getClassName().endsWith(".FallbackHome")) {
                entries.add(e);
            }
        }
        return dedupeAndSort(entries, context.getPackageName());
    }

    /** 电视与手机启动器里能看到的应用，按包名去重（同一应用优先保留 Leanback 入口） */
    public static List<AppEntry> queryLaunchableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppEntry> entries = new ArrayList<>();
        entries.addAll(toEntries(pm, pm.queryIntentActivities(
                launcherIntent(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)));
        entries.addAll(toEntries(pm, pm.queryIntentActivities(
                launcherIntent(Intent.CATEGORY_LAUNCHER), 0)));
        return dedupeAndSort(entries, context.getPackageName());
    }

    public static void launch(Context context, ComponentName component) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.app_switcher_launch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 去掉自身包，按包名去重（保留先出现的），再按名称排序。
     * 纯数据处理，便于单测。
     */
    static List<AppEntry> dedupeAndSort(List<AppEntry> entries, String selfPackage) {
        Set<String> seen = new HashSet<>();
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : entries) {
            String pkg = e.component.getPackageName();
            if (pkg.equals(selfPackage) || !seen.add(pkg)) {
                continue;
            }
            out.add(e);
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    private static List<AppEntry> toEntries(PackageManager pm, List<ResolveInfo> infos) {
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            out.add(new AppEntry(
                    label != null ? label.toString() : ai.packageName,
                    new ComponentName(ai.packageName, ai.name),
                    ri.loadIcon(pm)));
        }
        return out;
    }

    private static ComponentName homeAlias(Context context) {
        return new ComponentName(context, HOME_ALIAS);
    }

    private static void setComponentEnabled(Context context, ComponentName component, boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(component,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    private static Intent launcherIntent(String category) {
        return new Intent(Intent.ACTION_MAIN).addCategory(category);
    }
}
