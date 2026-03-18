package com.whyun.witv.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;
import com.whyun.witv.data.db.entity.M3USource;

import java.util.List;

public class SourceListAdapter extends RecyclerView.Adapter<SourceListAdapter.VH> {

    public interface OnSourceActionListener {
        void onActivate(M3USource source);
    }

    private List<M3USource> sources;
    private final OnSourceActionListener listener;

    public SourceListAdapter(List<M3USource> sources, OnSourceActionListener listener) {
        this.sources = sources;
        this.listener = listener;
    }

    public void updateData(List<M3USource> sources) {
        this.sources = sources;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_source, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        M3USource source = sources.get(position);

        holder.nameView.setText(source.name);
        holder.urlView.setText(source.url);

        if (source.isActive) {
            holder.statusView.setVisibility(View.VISIBLE);
        } else {
            holder.statusView.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (!source.isActive && listener != null) {
                listener.onActivate(source);
            }
        });
    }

    @Override
    public int getItemCount() {
        return sources != null ? sources.size() : 0;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView nameView, urlView, statusView;

        VH(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.source_name);
            urlView = itemView.findViewById(R.id.source_url);
            statusView = itemView.findViewById(R.id.source_status);
            itemView.setFocusable(true);
            itemView.setFocusableInTouchMode(true);
        }
    }
}
