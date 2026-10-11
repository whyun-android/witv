package com.whyun.witv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;

import java.util.List;

public class ChannelGroupListAdapter extends RecyclerView.Adapter<ChannelGroupListAdapter.VH> {

    public interface OnGroupClickListener {
        void onGroupClick(int position, String groupName);
    }

    public interface OnGroupFocusedListener {
        void onGroupFocused(int position, String groupName);
    }

    private static final Object PAYLOAD_SELECTION = new Object();

    private final List<String> groups;
    private int selectedIndex;
    @Nullable
    private final OnGroupClickListener clickListener;
    @Nullable
    private final OnGroupFocusedListener focusedListener;

    public ChannelGroupListAdapter(List<String> groups, int selectedIndex,
                                   @Nullable OnGroupClickListener clickListener,
                                   @Nullable OnGroupFocusedListener focusedListener) {
        this.groups = groups;
        this.selectedIndex = selectedIndex;
        this.clickListener = clickListener;
        this.focusedListener = focusedListener;
    }

    public void setSelectedIndex(int selectedIndex) {
        if (this.selectedIndex == selectedIndex) {
            return;
        }
        int previous = this.selectedIndex;
        this.selectedIndex = selectedIndex;
        // 不能 notifyDataSetChanged：没有 stable id 时 RecyclerView 找不回带焦点的那一行，
        // 焦点会退回根布局的第一个可聚焦控件（播放器），菜单开着却没法再操作。
        // 带 payload 只刷新选中态，复用原 ViewHolder，焦点保持不动。
        if (previous >= 0 && previous < groups.size()) {
            notifyItemChanged(previous, PAYLOAD_SELECTION);
        }
        if (selectedIndex >= 0 && selectedIndex < groups.size()) {
            notifyItemChanged(selectedIndex, PAYLOAD_SELECTION);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_group_card, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String group = groups.get(position);
        holder.nameView.setText(group);
        holder.itemView.setSelected(position == selectedIndex);
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos < groups.size()) {
                    clickListener.onGroupClick(pos, groups.get(pos));
                }
            }
        });
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && focusedListener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos < groups.size()) {
                    focusedListener.onGroupFocused(pos, groups.get(pos));
                }
            }
        });
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            holder.itemView.setSelected(position == selectedIndex);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView nameView;

        VH(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.channel_group_name);
            itemView.setFocusable(true);
            itemView.setFocusableInTouchMode(true);
        }
    }
}
