package com.whyun.witv.ui;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level settings entries; selecting opens the left submenu (except help → dialog).
 */
public class SettingsMainMenuAdapter extends RecyclerView.Adapter<SettingsMainMenuAdapter.VH> {

    public interface Listener {
        void onMainMenuItemClick(int categoryId);

        void onMainMenuItemFocused(int categoryId);
    }

    public static final class Item {
        final int categoryId;
        final String title;

        public Item(int categoryId, String title) {
            this.categoryId = categoryId;
            this.title = title;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Listener listener;

    public SettingsMainMenuAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Item> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_settings_main_menu_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Item item = items.get(position);
        holder.title.setText(item.title);
        holder.itemView.setOnClickListener(v -> listener.onMainMenuItemClick(item.categoryId));
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                listener.onMainMenuItemFocused(item.categoryId);
            }
        });
        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                listener.onMainMenuItemClick(item.categoryId);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** @return adapter position for category, or 0 */
    public int positionOfCategory(int categoryId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).categoryId == categoryId) {
                return i;
            }
        }
        return 0;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.main_menu_title);
        }
    }
}
