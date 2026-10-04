package com.whyun.witv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.whyun.witv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量的应用切换面板：WiTV 当桌面时，从这里打开其他桌面、系统设置或任意应用。
 * 用 AlertDialog 自带的 ListView，遥控器上下键即可移动焦点。
 */
public final class AppSwitcherDialog {

    private AppSwitcherDialog() {
    }

    /** 列表里的一项：分组标题、「更改默认桌面」或一个应用 */
    private static final class Item {
        final String title;
        @Nullable
        final LauncherHelper.AppEntry app;
        final boolean header;

        Item(String title, @Nullable LauncherHelper.AppEntry app, boolean header) {
            this.title = title;
            this.app = app;
            this.header = header;
        }
    }

    public static AlertDialog show(Activity activity) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(activity.getString(R.string.app_switcher_change_default), null, false));

        List<LauncherHelper.AppEntry> homes = LauncherHelper.queryOtherHomes(activity);
        if (!homes.isEmpty()) {
            items.add(new Item(activity.getString(R.string.app_switcher_other_homes), null, true));
            for (LauncherHelper.AppEntry e : homes) {
                items.add(new Item(e.label, e, false));
            }
        }
        List<LauncherHelper.AppEntry> apps = LauncherHelper.queryLaunchableApps(activity);
        if (!apps.isEmpty()) {
            items.add(new Item(activity.getString(R.string.app_switcher_all_apps), null, true));
            for (LauncherHelper.AppEntry e : apps) {
                items.add(new Item(e.label, e, false));
            }
        }

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public int getViewTypeCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int position) {
                return items.get(position).header ? 1 : 0;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return !items.get(position).header;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Item item = items.get(position);
                LayoutInflater inflater = LayoutInflater.from(parent.getContext());
                if (item.header) {
                    TextView v = convertView != null ? (TextView) convertView
                            : (TextView) inflater.inflate(R.layout.item_app_section, parent, false);
                    v.setText(item.title);
                    return v;
                }
                View v = convertView != null ? convertView
                        : inflater.inflate(R.layout.item_app_entry, parent, false);
                ImageView icon = v.findViewById(R.id.app_icon);
                TextView label = v.findViewById(R.id.app_label);
                label.setText(item.title);
                if (item.app != null && item.app.icon != null) {
                    icon.setImageDrawable(item.app.icon);
                    icon.setVisibility(View.VISIBLE);
                } else {
                    icon.setImageDrawable(null);
                    icon.setVisibility(View.GONE);
                }
                return v;
            }
        };

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.app_switcher_title)
                .setAdapter(adapter, (d, which) -> {
                    Item item = items.get(which);
                    if (item.header) {
                        return;
                    }
                    if (item.app == null) {
                        LauncherHelper.requestChooseDefaultHome(activity);
                    } else {
                        LauncherHelper.launch(activity, item.app.component);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        return dialog;
    }
}
