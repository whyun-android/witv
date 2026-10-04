package com.whyun.witv.ui;

import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.whyun.witv.R;
import com.whyun.witv.data.db.entity.ChannelSource;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.player.MulticastUrlUtil;
import com.whyun.witv.player.PlaybackDecoderMode;
import com.whyun.witv.server.QrCodeUtil;

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
    static final int VT_MULTICAST_PROXY = 8;
    static final int VT_DECODER_MODE = 9;

    public abstract static class Row {
        abstract int viewType();
    }

    public static final class WebHintRow extends Row {
        final String text;
        /** 纯 URL，用于生成二维码；与 {@link #text} 里那句说明文字分开 */
        final String url;

        public WebHintRow(String text, String url) {
            this.text = text;
            this.url = url;
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

    /** 解码方式单选行 */
    public static final class DecoderModeRow extends Row {
        final PlaybackDecoderMode mode;
        final String title;
        final String description;
        final boolean selected;

        public DecoderModeRow(PlaybackDecoderMode mode, String title, String description,
                              boolean selected) {
            this.mode = mode;
            this.title = title;
            this.description = description;
            this.selected = selected;
        }

        @Override
        int viewType() {
            return VT_DECODER_MODE;
        }
    }

    /** 组播转单播代理（udpxy）地址输入行 */
    public static final class MulticastProxyRow extends Row {
        final String proxyBase;

        public MulticastProxyRow(String proxyBase) {
            this.proxyBase = proxyBase != null ? proxyBase : "";
        }

        @Override
        int viewType() {
            return VT_MULTICAST_PROXY;
        }
    }

    public static final class CheckRow extends Row {
        enum Kind {
            AUTO_PLAY, REFRESH_M3U_ON_STARTUP, USE_DISK_CACHE_FOR_LIVE_TS, LOAD_SPEED, REVERSE_CHANNEL_KEYS
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

        void onRefreshM3uOnStartup(boolean checked);

        void onUseDiskCacheForLiveTs(boolean checked);

        void onLoadSpeed(boolean checked);

        void onReverseChannelKeys(boolean checked);

        void onHelpSubmenuClick(HelpSubRow.Kind kind);

        void onSourceTimeoutSeconds(int seconds);

        /** @param proxyBase udpxy 前缀；空串表示直接收组播 */
        void onSaveUdpxyProxy(String proxyBase);

        void onPlaybackDecoderMode(PlaybackDecoderMode mode);
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
                return new WebHintVH(
                        inf.inflate(R.layout.item_settings_web_hint, parent, false));
            case VT_EMPTY_HINT:
                return new HintVH(inf.inflate(R.layout.item_settings_hint, parent, false));
            case VT_M3U:
                return new M3UVH(inf.inflate(R.layout.item_source, parent, false));
            case VT_STREAM:
            case VT_SOURCE_TIMEOUT:
            case VT_DECODER_MODE:
                return new StreamVH(inf.inflate(R.layout.item_settings_stream_row, parent, false));
            case VT_EPG:
                return new EpgVH(inf.inflate(R.layout.item_settings_epg, parent, false));
            case VT_MULTICAST_PROXY:
                return new MulticastProxyVH(
                        inf.inflate(R.layout.item_settings_multicast_proxy, parent, false));
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
        if (holder instanceof WebHintVH) {
            ((WebHintVH) holder).bind((WebHintRow) row);
        } else if (holder instanceof HintVH) {
            ((HintVH) holder).bind(((EmptyHintRow) row).text);
        } else if (holder instanceof M3UVH) {
            ((M3UVH) holder).bind(((M3USourceRow) row).source, listener);
        } else if (holder instanceof StreamVH) {
            if (row instanceof StreamRow) {
                ((StreamVH) holder).bind((StreamRow) row, listener);
            } else if (row instanceof DecoderModeRow) {
                ((StreamVH) holder).bind((DecoderModeRow) row, listener);
            } else {
                ((StreamVH) holder).bind((SourceTimeoutRow) row, listener);
            }
        } else if (holder instanceof EpgVH) {
            ((EpgVH) holder).bind(((EpgRow) row).epgUrl, listener);
        } else if (holder instanceof MulticastProxyVH) {
            ((MulticastProxyVH) holder).bind(((MulticastProxyRow) row).proxyBase, listener);
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

    /** Web 管理提示：地址上方带一个二维码，省去在电视上用遥控器输 URL。 */
    static final class WebHintVH extends RecyclerView.ViewHolder {
        final ImageView qr;
        final TextView qrCaption;
        final TextView text;

        WebHintVH(@NonNull View itemView) {
            super(itemView);
            qr = itemView.findViewById(R.id.web_hint_qr);
            qrCaption = itemView.findViewById(R.id.web_hint_qr_caption);
            text = itemView.findViewById(R.id.hint_text);
        }

        void bind(WebHintRow row) {
            text.setText(row.text);
            int sizePx = itemView.getResources()
                    .getDimensionPixelSize(R.dimen.settings_web_qr_size);
            Bitmap bitmap = QrCodeUtil.encode(row.url, sizePx);
            if (bitmap == null) {
                qr.setVisibility(View.GONE);
                qrCaption.setVisibility(View.GONE);
                return;
            }
            qr.setImageBitmap(bitmap);
            qr.setVisibility(View.VISIBLE);
            qrCaption.setVisibility(View.VISIBLE);
        }
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

        void bind(DecoderModeRow row, Listener listener) {
            label.setText(row.title);
            url.setText(row.description);
            url.setVisibility(View.VISIBLE);
            currentBadge.setVisibility(row.selected ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> listener.onPlaybackDecoderMode(row.mode));
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    listener.onPlaybackDecoderMode(row.mode);
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

    static final class MulticastProxyVH extends RecyclerView.ViewHolder {
        final EditText input;
        final TextView preview;
        final Button save;
        final Button clear;

        MulticastProxyVH(@NonNull View itemView) {
            super(itemView);
            input = itemView.findViewById(R.id.multicast_proxy_input);
            preview = itemView.findViewById(R.id.multicast_proxy_preview);
            save = itemView.findViewById(R.id.btn_save_multicast_proxy);
            clear = itemView.findViewById(R.id.btn_clear_multicast_proxy);
        }

        void bind(String proxyBase, Listener listener) {
            if (!proxyBase.equals(input.getText().toString())) {
                input.setText(proxyBase);
            }
            updatePreview(proxyBase);
            save.setOnClickListener(v -> {
                String normalized =
                        MulticastUrlUtil.normalizeProxyBase(input.getText().toString());
                // 回填规范化结果，让用户直接看到实际会用的地址
                input.setText(normalized);
                updatePreview(normalized);
                listener.onSaveUdpxyProxy(normalized);
            });
            clear.setOnClickListener(v -> {
                input.setText("");
                updatePreview("");
                listener.onSaveUdpxyProxy("");
            });
        }

        /** 用一条示例频道展示改写效果，比单看前缀直观 */
        private void updatePreview(String proxyBase) {
            if (proxyBase == null || proxyBase.isEmpty()) {
                preview.setText(R.string.multicast_proxy_preview_direct);
            } else {
                preview.setText(preview.getContext()
                        .getString(R.string.multicast_proxy_preview_format, proxyBase));
            }
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
                    case REFRESH_M3U_ON_STARTUP:
                        listener.onRefreshM3uOnStartup(isChecked);
                        break;
                    case USE_DISK_CACHE_FOR_LIVE_TS:
                        listener.onUseDiskCacheForLiveTs(isChecked);
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
