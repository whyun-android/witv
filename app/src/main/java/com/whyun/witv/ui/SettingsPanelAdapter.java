package com.whyun.witv.ui;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.M3USource;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Left submenu content only (no fold headers). Used inside {@link SettingsCollapsibleFragment}.
 */
public class SettingsPanelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int VT_WEB_HINT = 0;
    static final int VT_M3U = 1;
    static final int VT_STREAM = 2;
    static final int VT_EPG = 3;
    static final int VT_CHECK = 4;
    static final int VT_EMPTY_HINT = 5;
    static final int VT_HELP_SUB = 6;
    static final int VT_SOURCE_TIMEOUT = 7;

    public abstract static class Row {
        abstract int viewType();
    }

    public static final class WebHintRow extends Row {
        final String text;

        public WebHintRow(String text) {
            this.text = text;
        }

        @Override
        int viewType() {
            return VT_WEB_HINT;
        }
    }

    public static final class M3USourceRow extends Row {
        final M3USource source;

        public M3USourceRow(M3USource source) {
            this.source = source;
        }

        @Override
        int viewType() {
            return VT_M3U;
        }
    }

    public static final class StreamRow extends Row {
        final int index;
        final ChannelSource source;
        final boolean isCurrent;

        public StreamRow(int index, ChannelSource source, boolean isCurrent) {
            this.index = index;
            this.source = source;
            this.isCurrent = isCurrent;
        }

        @Override
        int viewType() {
            return VT_STREAM;
        }
    }

    public static final class SourceTimeoutRow extends Row {
        public final int seconds;
        public final boolean selected;

        public SourceTimeoutRow(int seconds, boolean selected) {
            this.seconds = seconds;
            this.selected = selected;
        }

        @Override
        int viewType() {
            return VT_SOURCE_TIMEOUT;
        }
    }

    public static final class EpgRow extends Row {
        final String epgUrl;

        public EpgRow(String epgUrl) {
            this.epgUrl = epgUrl != null ? epgUrl : "";
        }

        @Override
        int viewType() {
            return VT_EPG;
        }
    }

    public static final class CheckRow extends Row {
        enum Kind {
            AUTO_PLAY, LOAD_SPEED, REVERSE_CHANNEL_KEYS
        }

        final Kind kind;
        final boolean checked;
        final String title;
        final String subtitleOrNull;

        public CheckRow(Kind kind, boolean checked, String title, String subtitleOrNull) {
            this.kind = kind;
            this.checked = checked;
            this.title = title;
            this.subtitleOrNull = subtitleOrNull;
        }

        @Override
        int viewType() {
            return VT_CHECK;
        }
    }

    public static final class EmptyHintRow extends Row {
        final String text;

        public EmptyHintRow(String text) {
            this.text = text;
        }

        @Override
        int viewType() {
            return VT_EMPTY_HINT;
        }
    }

    public static final class HelpSubRow extends Row {
        public enum Kind {
            MEDIA_INFO, HELP_GUIDE, ABOUT_APP
        }

        public final Kind kind;
        public final String title;

        public HelpSubRow(Kind kind, String title) {
            this.kind = kind;
            this.title = title;
        }

        @Override
        int viewType() {
            return VT_HELP_SUB;
        }
    }

    public interface Listener {
        void onActivateM3U(M3USource source);

        void onSaveEpg(String url);

        void onReloadEpg(String url);

        void onStreamSwitch(int index);

        void onAutoPlay(boolean checked);

        void onLoadSpeed(boolean checked);

        void onReverseChannelKeys(boolean checked);

        void onHelpSubmenuClick(HelpSubRow.Kind kind);

        void onSourceTimeoutSeconds(int seconds);
    }

    private List<Row> rows = Collections.emptyList();
    private final Listener listener;

    public SettingsPanelAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setRows(List<Row> rows) {
        this.rows = rows != null ? rows : Collections.emptyList();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).viewType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VT_WEB_HINT:
            case VT_EMPTY_HINT:
                return new HintVH(inf.inflate(R.layout.item_settings_hint, parent, false));
            case VT_M3U:
                return new M3UVH(inf.inflate(R.layout.item_source, parent, false));
            case VT_STREAM:
            case VT_SOURCE_TIMEOUT:
                return new StreamVH(inf.inflate(R.layout.item_settings_stream_row, parent, false));
            case VT_EPG:
                return new EpgVH(inf.inflate(R.layout.item_settings_epg, parent, false));
            case VT_CHECK:
                return new CheckVH(inf.inflate(R.layout.item_settings_check, parent, false));
            case VT_HELP_SUB:
                return new HelpSubVH(inf.inflate(R.layout.item_settings_help_sub_row, parent, false));
            default:
                throw new IllegalArgumentException("viewType " + viewType);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HintVH) {
            String text;
            if (row instanceof WebHintRow) {
                text = ((WebHintRow) row).text;
            } else {
                text = ((EmptyHintRow) row).text;
            }
            ((HintVH) holder).bind(text);
        } else if (holder instanceof M3UVH) {
            ((M3UVH) holder).bind(((M3USourceRow) row).source, listener);
        } else if (holder instanceof StreamVH) {
            if (row instanceof StreamRow) {
                ((StreamVH) holder).bind((StreamRow) row, listener);
            } else {
                ((StreamVH) holder).bind((SourceTimeoutRow) row, listener);
            }
        } else if (holder instanceof EpgVH) {
            ((EpgVH) holder).bind(((EpgRow) row).epgUrl, listener);
        } else if (holder instanceof CheckVH) {
            ((CheckVH) holder).bind((CheckRow) row, listener);
        } else if (holder instanceof HelpSubVH) {
            ((HelpSubVH) holder).bind((HelpSubRow) row, listener);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class HintVH extends RecyclerView.ViewHolder {
        final TextView text;

        HintVH(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.hint_text);
        }

        void bind(String s) {
            text.setText(s);
        }
    }

    static final class M3UVH extends RecyclerView.ViewHolder {
        final TextView nameView;
        final TextView urlView;
        final TextView statusView;

        M3UVH(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.source_name);
            urlView = itemView.findViewById(R.id.source_url);
            statusView = itemView.findViewById(R.id.source_status);
        }

        void bind(M3USource source, Listener listener) {
            nameView.setText(source.name);
            urlView.setText(source.url);
            if (source.isActive) {
                statusView.setVisibility(View.VISIBLE);
            } else {
                statusView.setVisibility(View.GONE);
            }
            itemView.setOnClickListener(v -> {
                if (!source.isActive) {
                    listener.onActivateM3U(source);
                }
            });
        }
    }

    static final class StreamVH extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView url;
        final TextView currentBadge;

        StreamVH(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.stream_label);
            url = itemView.findViewById(R.id.stream_url);
            currentBadge = itemView.findViewById(R.id.stream_current);
        }

        void bind(StreamRow sr, Listener listener) {
            label.setText(String.format(Locale.getDefault(),
                    itemView.getContext().getString(R.string.stream_line_format), sr.index + 1));
            url.setVisibility(View.GONE);
            currentBadge.setVisibility(sr.isCurrent ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> listener.onStreamSwitch(sr.index));
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    listener.onStreamSwitch(sr.index);
                    return true;
                }
                return false;
            });
        }

        void bind(SourceTimeoutRow row, Listener listener) {
            label.setText(itemView.getContext().getString(R.string.source_timeout_seconds_format, row.seconds));
            url.setVisibility(View.GONE);
            currentBadge.setVisibility(row.selected ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> listener.onSourceTimeoutSeconds(row.seconds));
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    listener.onSourceTimeoutSeconds(row.seconds);
                    return true;
                }
                return false;
            });
        }
    }

    static final class EpgVH extends RecyclerView.ViewHolder {
        final EditText input;
        final Button save;
        final Button reload;

        EpgVH(@NonNull View itemView) {
            super(itemView);
            input = itemView.findViewById(R.id.epg_url_input);
            save = itemView.findViewById(R.id.btn_save_epg);
            reload = itemView.findViewById(R.id.btn_reload_epg);
        }

        void bind(String epgUrl, Listener listener) {
            if (!epgUrl.equals(input.getText().toString())) {
                input.setText(epgUrl);
            }
            save.setOnClickListener(v -> listener.onSaveEpg(input.getText().toString().trim()));
            reload.setOnClickListener(v -> listener.onReloadEpg(input.getText().toString().trim()));
        }
    }

    static final class CheckVH extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView hint;

        CheckVH(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.check_option);
            hint = itemView.findViewById(R.id.check_hint);
        }

        void bind(CheckRow row, Listener listener) {
            check.setOnCheckedChangeListener(null);
            check.setText(row.title);
            check.setChecked(row.checked);
            if (row.subtitleOrNull != null && !row.subtitleOrNull.isEmpty()) {
                hint.setText(row.subtitleOrNull);
                hint.setVisibility(View.VISIBLE);
            } else {
                hint.setVisibility(View.GONE);
            }
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                switch (row.kind) {
                    case AUTO_PLAY:
                        listener.onAutoPlay(isChecked);
                        break;
                    case LOAD_SPEED:
                        listener.onLoadSpeed(isChecked);
                        break;
                    case REVERSE_CHANNEL_KEYS:
                        listener.onReverseChannelKeys(isChecked);
                        break;
                }
            });
        }
    }

    static final class HelpSubVH extends RecyclerView.ViewHolder {

        HelpSubVH(@NonNull View itemView) {
            super(itemView);
        }

        void bind(HelpSubRow row, Listener listener) {
            ((TextView) itemView).setText(row.title);
            itemView.setOnClickListener(v -> listener.onHelpSubmenuClick(row.kind));
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    listener.onHelpSubmenuClick(row.kind);
                    return true;
                }
                return false;
            });
        }
    }
}
