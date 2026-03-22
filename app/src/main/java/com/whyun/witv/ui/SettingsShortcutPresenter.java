package com.whyun.witv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

import com.whyun.witv.R;

public class SettingsShortcutPresenter extends Presenter {

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_settings_shortcut_card, parent, false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        TextView title = viewHolder.view.findViewById(R.id.settings_shortcut_title);
        title.setText(R.string.settings);
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
    }
}
