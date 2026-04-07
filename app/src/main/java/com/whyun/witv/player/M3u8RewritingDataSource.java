package com.whyun.witv.player;

import android.net.Uri;
import android.util.Log;

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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对 {@code .m3u8} 请求在内存中读全量后交给 {@link HlsMediaSequenceFixUtil} 修正再供 HLS 解析；其它 URI 透传。
 */
@OptIn(markerClass = UnstableApi.class)
public final class M3u8RewritingDataSource implements DataSource {

    private static final String TAG = "M3u8RewriteDs";

    private final DataSource playlistUpstream;
    private final DataSource mediaUpstream;
    @Nullable
    private final HlsSegmentPrefetcher segmentPrefetcher;
    private boolean rewriteMode;
    private byte[] rewrittenData;
    private int readPosition;
    @Nullable
    private DataSource activeUpstream;
    @Nullable
    private Uri openedUri;
    @Nullable
    private Map<String, List<String>> rewrittenResponseHeaders;

    public M3u8RewritingDataSource(DataSource playlistUpstream,
                                   DataSource mediaUpstream,
                                   @Nullable HlsSegmentPrefetcher segmentPrefetcher) {
        this.playlistUpstream = playlistUpstream;
        this.mediaUpstream = mediaUpstream;
        this.segmentPrefetcher = segmentPrefetcher;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        playlistUpstream.addTransferListener(transferListener);
        mediaUpstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        close();
        openedUri = dataSpec.uri;
        if (!isHlsPlaylistUri(dataSpec.uri)) {
            rewriteMode = false;
            activeUpstream = mediaUpstream;
            return mediaUpstream.open(dataSpec);
        }
        activeUpstream = playlistUpstream;
        playlistUpstream.open(dataSpec);
        byte[] raw = readAll(playlistUpstream);
        rewrittenResponseHeaders = playlistUpstream.getResponseHeaders();
        playlistUpstream.close();
        activeUpstream = null;
        String text = new String(raw, StandardCharsets.UTF_8);
        String fixed = HlsMediaSequenceFixUtil.fixPlaylistIfNeeded(text);
        if (!text.equals(fixed)) {
            Log.d(TAG, "Playlist rewritten: " + dataSpec.uri);
        } else {
            Log.d(TAG, "Playlist unchanged: " + dataSpec.uri);
        }
        if (segmentPrefetcher != null) {
            Log.d(TAG, "Trigger segment prefetch for playlist: " + dataSpec.uri);
            segmentPrefetcher.prefetchPlaylistSegments(dataSpec.uri, fixed);
        }
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
            return activeUpstream != null
                    ? activeUpstream.read(buffer, offset, readLength)
                    : C.RESULT_END_OF_INPUT;
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
        return activeUpstream != null ? activeUpstream.getUri() : openedUri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (rewriteMode && rewrittenResponseHeaders != null) {
            return rewrittenResponseHeaders;
        }
        return activeUpstream != null ? activeUpstream.getResponseHeaders() : Collections.emptyMap();
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
        if (activeUpstream != null) {
            activeUpstream.close();
            activeUpstream = null;
        }
        openedUri = null;
    }

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory playlistUpstreamFactory;
        private final DataSource.Factory mediaUpstreamFactory;
        @Nullable
        private final HlsSegmentPrefetcher segmentPrefetcher;

        public Factory(DataSource.Factory playlistUpstreamFactory,
                       DataSource.Factory mediaUpstreamFactory,
                       @Nullable HlsSegmentPrefetcher segmentPrefetcher) {
            this.playlistUpstreamFactory = playlistUpstreamFactory;
            this.mediaUpstreamFactory = mediaUpstreamFactory;
            this.segmentPrefetcher = segmentPrefetcher;
        }

        @Override
        public DataSource createDataSource() {
            return new M3u8RewritingDataSource(
                    playlistUpstreamFactory.createDataSource(),
                    mediaUpstreamFactory.createDataSource(),
                    segmentPrefetcher);
        }
    }
}
