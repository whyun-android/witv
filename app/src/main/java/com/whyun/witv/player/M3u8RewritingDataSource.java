package com.whyun.witv.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对 {@code .m3u8} 请求在内存中读全量后交给 {@link HlsMediaSequenceFixUtil} 修正再供 HLS 解析；其它 URI 透传。
 */
@OptIn(markerClass = UnstableApi.class)
public final class M3u8RewritingDataSource implements DataSource {

    private final DataSource upstream;
    private boolean rewriteMode;
    private byte[] rewrittenData;
    private int readPosition;
    @Nullable
    private Uri openedUri;
    @Nullable
    private Map<String, List<String>> rewrittenResponseHeaders;

    public M3u8RewritingDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        close();
        openedUri = dataSpec.uri;
        long upstreamLength = upstream.open(dataSpec);
        if (!isHlsPlaylistUri(dataSpec.uri)) {
            rewriteMode = false;
            return upstreamLength;
        }
        byte[] raw = readAll(upstream);
        rewrittenResponseHeaders = upstream.getResponseHeaders();
        upstream.close();
        String text = new String(raw, StandardCharsets.UTF_8);
        String fixed = HlsMediaSequenceFixUtil.fixPlaylistIfNeeded(text);
        rewrittenData = fixed.getBytes(StandardCharsets.UTF_8);
        readPosition = 0;
        rewriteMode = true;
        return rewrittenData.length;
    }

    private static boolean isHlsPlaylistUri(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        return path.toLowerCase(Locale.US).contains(".m3u8");
    }

    private static byte[] readAll(DataSource source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = source.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (!rewriteMode) {
            return upstream.read(buffer, offset, readLength);
        }
        if (readPosition >= rewrittenData.length) {
            return C.RESULT_END_OF_INPUT;
        }
        int len = Math.min(readLength, rewrittenData.length - readPosition);
        System.arraycopy(rewrittenData, readPosition, buffer, offset, len);
        readPosition += len;
        return len;
    }

    @Override
    @Nullable
    public Uri getUri() {
        if (rewriteMode) {
            return openedUri;
        }
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (rewriteMode && rewrittenResponseHeaders != null) {
            return rewrittenResponseHeaders;
        }
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        if (rewriteMode) {
            rewriteMode = false;
            rewrittenData = null;
            readPosition = 0;
            rewrittenResponseHeaders = null;
            openedUri = null;
            return;
        }
        openedUri = null;
        upstream.close();
    }

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        public Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new M3u8RewritingDataSource(upstreamFactory.createDataSource());
        }
    }
}
