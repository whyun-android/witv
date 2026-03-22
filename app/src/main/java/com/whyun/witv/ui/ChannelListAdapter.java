package com.whyun.witv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.whyun.witv.R;
import com.whyun.witv.data.db.entity.Channel;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.VH> {

    public interface OnChannelClickListener {
        void onChannelClick(Channel channel);
    }

    /** 列表项获得焦点时回调（用于右侧 EPG 预览、空闲隐藏计时等） */
    public interface OnChannelRowFocusedListener {
        void onFocused(Channel channel);
    }

    /** 长按确认键（或长按条目）时回调，返回 true 表示已消费 */
    public interface OnChannelLongClickListener {
        boolean onChannelLongClick(Channel channel);
    }

    private final List<Channel> channels;
    private final int selectedIndex;
    private final OnChannelClickListener listener;
    @Nullable
    private final OnChannelRowFocusedListener rowFocusedListener;
    @Nullable
    private final OnChannelLongClickListener longClickListener;
    private Set<Long> favoriteIds = Collections.emptySet();

    public ChannelListAdapter(List<Channel> channels, int selectedIndex, OnChannelClickListener listener) {
        this(channels, selectedIndex, Collections.emptySet(), listener, null, null);
    }

    public ChannelListAdapter(List<Channel> channels, int selectedIndex,
                            OnChannelClickListener listener,
                            @Nullable OnChannelRowFocusedListener rowFocusedListener) {
        this(channels, selectedIndex, Collections.emptySet(), listener, rowFocusedListener, null);
    }

    public ChannelListAdapter(List<Channel> channels, int selectedIndex,
                              Set<Long> favoriteIds, OnChannelClickListener listener) {
        this(channels, selectedIndex, favoriteIds, listener, null, null);
    }

    public ChannelListAdapter(List<Channel> channels, int selectedIndex,
                              Set<Long> favoriteIds, OnChannelClickListener listener,
                              @Nullable OnChannelRowFocusedListener rowFocusedListener) {
        this(channels, selectedIndex, favoriteIds, listener, rowFocusedListener, null);
    }

    public ChannelListAdapter(List<Channel> channels, int selectedIndex,
                              Set<Long> favoriteIds, OnChannelClickListener listener,
                              @Nullable OnChannelRowFocusedListener rowFocusedListener,
                              @Nullable OnChannelLongClickListener longClickListener) {
        this.channels = channels;
        this.selectedIndex = selectedIndex;
        this.listener = listener;
        this.favoriteIds = favoriteIds != null ? favoriteIds : Collections.emptySet();
        this.rowFocusedListener = rowFocusedListener;
        this.longClickListener = longClickListener;
    }

    public void setFavoriteIds(Set<Long> ids) {
        favoriteIds = ids != null ? ids : Collections.emptySet();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_card, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Channel channel = channels.get(position);

        holder.nameView.setText(channel.displayName);
        holder.numberView.setText(String.valueOf(position + 1));

        if (channel.groupTitle != null && !channel.groupTitle.isEmpty()) {
            holder.epgView.setText(channel.groupTitle);
            holder.epgView.setVisibility(View.VISIBLE);
        } else {
            holder.epgView.setVisibility(View.GONE);
        }

        if (channel.logoUrl != null && !channel.logoUrl.isEmpty()) {
            Glide.with(holder.itemView.getContext())
                    .asBitmap()
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.app_banner)
                    .into(holder.logoView);
        } else {
            holder.logoView.setImageResource(R.drawable.app_banner);
        }

        if (holder.favoriteView != null) {
            holder.favoriteView.setVisibility(favoriteIds.contains(channel.id) ? View.VISIBLE : View.GONE);
        }

        holder.itemView.setSelected(position == selectedIndex);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChannelClick(channel);
        });
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && rowFocusedListener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    pos = holder.getAdapterPosition();
                }
                if (pos != RecyclerView.NO_POSITION && pos >= 0 && pos < channels.size()) {
                    rowFocusedListener.onFocused(channels.get(pos));
                }
            }
        });
        if (longClickListener != null) {
            holder.itemView.setLongClickable(true);
            holder.itemView.setOnLongClickListener(v -> longClickListener.onChannelLongClick(channel));
        } else {
            holder.itemView.setOnLongClickListener(null);
            holder.itemView.setLongClickable(false);
        }
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView epgView;
        TextView numberView;
        ImageView logoView;
        ImageView favoriteView;

        VH(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.channel_name);
            epgView = itemView.findViewById(R.id.channel_epg);
            numberView = itemView.findViewById(R.id.channel_number);
            logoView = itemView.findViewById(R.id.channel_logo);
            favoriteView = itemView.findViewById(R.id.channel_favorite);
            itemView.setFocusable(true);
            itemView.setFocusableInTouchMode(true);
        }
    }
}
