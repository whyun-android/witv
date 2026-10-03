package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 回归保护：{@code rtp://} / {@code udp://} 绝不能落到 HTTP 链路。
 *
 * <p>一旦分流失效，{@code DefaultHttpDataSource} 会抛
 * {@code MalformedURLException: unknown protocol: rtp}，而且因为它被包在
 * {@code ERROR_CODE_IO_NETWORK_CONNECTION_FAILED} 里，表现为「所有线路都失败」，
 * 很难一眼看出是分流而不是网络的问题。
 */
@RunWith(RobolectricTestRunner.class)
public class MulticastAwareDataSourceFactoryTest {

    private RecordingFactory defaultFactory;
    private RecordingFactory udpFactory;
    private RecordingFactory rtpFactory;
    private DataSource.Factory factory;

    @Before
    public void setUp() {
        defaultFactory = new RecordingFactory("default");
        udpFactory = new RecordingFactory("udp");
        rtpFactory = new RecordingFactory("rtp");
        factory = new MulticastAwareDataSourceFactory(defaultFactory, udpFactory, rtpFactory);
    }

    private void open(String url) throws IOException {
        DataSource source = factory.createDataSource();
        source.open(new DataSpec(Uri.parse(url)));
        source.close();
    }

    @Test
    public void rtpSchemeNeverReachesHttpChain() throws Exception {
        open("rtp://239.3.1.173:8001");

        assertEquals(1, rtpFactory.openedUris.size());
        assertEquals("rtp://239.3.1.173:8001", rtpFactory.openedUris.get(0).toString());
        assertTrue(udpFactory.openedUris.isEmpty());
        assertTrue(defaultFactory.openedUris.isEmpty());
    }

    @Test
    public void udpSchemeNeverReachesHttpChain() throws Exception {
        open("udp://239.3.1.173:8001");

        assertEquals(1, udpFactory.openedUris.size());
        assertTrue(rtpFactory.openedUris.isEmpty());
        assertTrue(defaultFactory.openedUris.isEmpty());
    }

    @Test
    public void schemeMatchIsCaseInsensitive() throws Exception {
        open("RTP://239.3.1.173:8001");
        open("UDP://239.3.1.173:8001");

        assertEquals(1, rtpFactory.openedUris.size());
        assertEquals(1, udpFactory.openedUris.size());
        assertTrue(defaultFactory.openedUris.isEmpty());
    }

    @Test
    public void httpAndHlsStillUseTheDefaultChain() throws Exception {
        open("http://a.com/live.m3u8");
        open("https://a.com/live.ts");
        // udpxy 改写后的组播也是普通 HTTP，必须走默认链路
        open("http://192.168.1.1:4022/rtp/239.3.1.173:8001");

        assertEquals(3, defaultFactory.openedUris.size());
        assertTrue(udpFactory.openedUris.isEmpty());
        assertTrue(rtpFactory.openedUris.isEmpty());
    }

    @Test
    public void sameInstanceCanSwitchSchemesAcrossOpens() throws Exception {
        DataSource source = factory.createDataSource();

        source.open(new DataSpec(Uri.parse("http://a.com/live.m3u8")));
        source.close();
        source.open(new DataSpec(Uri.parse("rtp://239.3.1.173:8001")));
        source.close();

        assertEquals(1, defaultFactory.openedUris.size());
        assertEquals(1, rtpFactory.openedUris.size());
    }

    /** 带宽统计依赖 TransferListener，分流层必须把它透传给真正干活的数据源。 */
    @Test
    public void transferListenersReachTheRoutedSource() throws Exception {
        DataSource source = factory.createDataSource();
        TransferListener listener = new NoOpTransferListener();
        source.addTransferListener(listener);

        source.open(new DataSpec(Uri.parse("rtp://239.3.1.173:8001")));

        assertEquals(1, rtpFactory.created.size());
        assertTrue(rtpFactory.created.get(0).listeners.contains(listener));
        source.close();
    }

    @Test
    public void closePropagatesToRoutedSource() throws Exception {
        DataSource source = factory.createDataSource();
        source.open(new DataSpec(Uri.parse("udp://239.3.1.173:8001")));
        source.close();

        assertEquals(1, udpFactory.created.size());
        assertEquals(1, udpFactory.created.get(0).closeCount);
    }

    // ------------------------------------------------------------------

    private static final class RecordingFactory implements DataSource.Factory {
        final String name;
        final List<Uri> openedUris = new ArrayList<>();
        final List<RecordingDataSource> created = new ArrayList<>();

        RecordingFactory(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            RecordingDataSource source = new RecordingDataSource(this);
            created.add(source);
            return source;
        }
    }

    private static final class RecordingDataSource implements DataSource {
        private final RecordingFactory owner;
        final List<TransferListener> listeners = new ArrayList<>();
        int closeCount;
        @Nullable
        private Uri uri;

        RecordingDataSource(RecordingFactory owner) {
            this.owner = owner;
        }

        @Override
        public void addTransferListener(@NonNull TransferListener transferListener) {
            listeners.add(transferListener);
        }

        @Override
        public long open(@NonNull DataSpec dataSpec) {
            uri = dataSpec.uri;
            owner.openedUris.add(dataSpec.uri);
            return 0;
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) {
            return -1;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class NoOpTransferListener implements TransferListener {
        @Override
        public void onTransferInitializing(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                           boolean isNetwork) {
        }

        @Override
        public void onTransferStart(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                    boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                       boolean isNetwork, int bytesTransferred) {
        }

        @Override
        public void onTransferEnd(@NonNull DataSource source, @NonNull DataSpec dataSpec,
                                  boolean isNetwork) {
        }
    }
}
