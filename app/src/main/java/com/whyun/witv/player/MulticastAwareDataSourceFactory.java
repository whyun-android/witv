package com.whyun.witv.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 URI scheme 分流的数据源工厂：{@code udp://} / {@code rtp://} 交给
 * {@link MulticastDataSource}，其余（HTTP/HLS/本地文件等）走原有链路。
 *
 * <p>真正的数据源在 {@link DataSource#open} 时才按 scheme 创建，避免每个 HLS 分片的加载任务
 * 都白白分配一套组播收包缓冲。
 */
@OptIn(markerClass = UnstableApi.class)
public final class MulticastAwareDataSourceFactory implements DataSource.Factory {

    private final DataSource.Factory defaultFactory;
    private final DataSource.Factory udpFactory;
    private final DataSource.Factory rtpFactory;

    public MulticastAwareDataSourceFactory(DataSource.Factory defaultFactory,
                                           DataSource.Factory udpFactory,
                                           DataSource.Factory rtpFactory) {
        this.defaultFactory = defaultFactory;
        this.udpFactory = udpFactory;
        this.rtpFactory = rtpFactory;
    }

    @NonNull
    @Override
    public DataSource createDataSource() {
        return new SchemeRoutingDataSource(defaultFactory, udpFactory, rtpFactory);
    }

    private static final class SchemeRoutingDataSource implements DataSource {

        private final DataSource.Factory defaultFactory;
        private final DataSource.Factory udpFactory;
        private final DataSource.Factory rtpFactory;
        private final List<TransferListener> transferListeners = new ArrayList<>();

        @Nullable
        private DataSource active;
        @Nullable
        private Uri openedUri;

        SchemeRoutingDataSource(DataSource.Factory defaultFactory,
                                DataSource.Factory udpFactory,
                                DataSource.Factory rtpFactory) {
            this.defaultFactory = defaultFactory;
            this.udpFactory = udpFactory;
            this.rtpFactory = rtpFactory;
        }

        @Override
        public void addTransferListener(@NonNull TransferListener transferListener) {
            transferListeners.add(transferListener);
            if (active != null) {
                active.addTransferListener(transferListener);
            }
        }

        @Override
        public long open(@NonNull DataSpec dataSpec) throws IOException {
            closeActiveQuietly();
            openedUri = dataSpec.uri;
            DataSource source = factoryFor(dataSpec.uri).createDataSource();
            for (int i = 0; i < transferListeners.size(); i++) {
                source.addTransferListener(transferListeners.get(i));
            }
            active = source;
            return source.open(dataSpec);
        }

        private DataSource.Factory factoryFor(@Nullable Uri uri) {
            String scheme = uri != null && uri.getScheme() != null
                    ? uri.getScheme().toLowerCase(Locale.US)
                    : "";
            if (MulticastUrlUtil.SCHEME_UDP.equals(scheme)) {
                return udpFactory;
            }
            if (MulticastUrlUtil.SCHEME_RTP.equals(scheme)) {
                return rtpFactory;
            }
            return defaultFactory;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            return active != null ? active.read(buffer, offset, length) : C.RESULT_END_OF_INPUT;
        }

        @Override
        @Nullable
        public Uri getUri() {
            return active != null ? active.getUri() : openedUri;
        }

        @NonNull
        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return active != null ? active.getResponseHeaders() : Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            openedUri = null;
            DataSource source = active;
            active = null;
            if (source != null) {
                source.close();
            }
        }

        private void closeActiveQuietly() {
            DataSource source = active;
            active = null;
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
