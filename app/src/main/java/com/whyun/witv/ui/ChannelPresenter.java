package com.whyun.witv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.whyun.witv.R;
import com.whyun.witv.data.db.entity.Channel;

public class ChannelPresenter extends Presenter {

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_card, parent, false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        Channel channel = (Channel) item;
        View view = viewHolder.view;

        TextView nameView = view.findViewById(R.id.channel_name);
        TextView epgView = view.findViewById(R.id.channel_epg);
        TextView numberView = view.findViewById(R.id.channel_number);
        ImageView logoView = view.findViewById(R.id.channel_logo);

        nameView.setText(channel.displayName);
        numberView.setText(String.valueOf(channel.sortOrder + 1));

        if (channel.groupTitle != null && !channel.groupTitle.isEmpty()) {
            epgView.setText(channel.groupTitle);
            epgView.setVisibility(View.VISIBLE);
        } else {
            epgView.setVisibility(View.GONE);
        }

        if (channel.logoUrl != null && !channel.logoUrl.isEmpty()) {
            Glide.with(view.getContext())
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.app_banner)
                    .error(R.drawable.app_banner)
                    .into(logoView);
        } else {
            logoView.setImageResource(R.drawable.app_banner);
        }
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        // Clean up
    }
}
