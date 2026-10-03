package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 直播 TS 的 {@code MODE_SINGLE_PMT} 调参只能作用于组播。
 *
 * <p>它假定流里只有一个 PMT；这个假定对运营商单节目组播成立，对 HTTP 上来源不明的 TS
 * （多节目 MPTS、中途重发 PMT）不成立，会导致 PID 映射走样。
 */
@RunWith(RobolectricTestRunner.class)
public class MulticastAwareExtractorsFactoryTest {

    private RecordingExtractorsFactory multicast;
    private RecordingExtractorsFactory standard;
    private ExtractorsFactory factory;

    @Before
    public void setUp() {
        multicast = new RecordingExtractorsFactory();
        standard = new RecordingExtractorsFactory();
        factory = new MulticastAwareExtractorsFactory(multicast, standard);
    }

    private void create(String url) {
        factory.createExtractors(Uri.parse(url), Collections.emptyMap());
    }

    @Test
    public void multicastSchemesUseTheLiveTsTuning() {
        create("udp://239.3.1.173:8001");
        create("rtp://239.3.1.173:8001");
        create("RTP://239.3.1.173:8001");

        assertEquals(3, multicast.uris.size());
        assertTrue(standard.uris.isEmpty());
    }

    @Test
    public void httpTsUsesMedia3Defaults() {
        create("http://a.com/live.ts");
        create("https://a.com/stream?type=ts");
        create("http://a.com/live.m3u8");

        assertEquals(3, standard.uris.size());
        assertTrue(multicast.uris.isEmpty());
    }

    /**
     * 经 udpxy 代理后 scheme 变成 http，走默认档。内容仍是裸 TS，默认档照样能解，
     * 只是少了 SINGLE_PMT 的起播加速——这是刻意的取舍，代理后的流同样来源不明。
     */
    @Test
    public void udpxyRewrittenUrlUsesDefaults() {
        create("http://192.168.1.1:4022/rtp/239.3.1.173:8001");

        assertEquals(1, standard.uris.size());
        assertTrue(multicast.uris.isEmpty());
    }

    @Test
    public void noUriFallsBackToDefaults() {
        factory.createExtractors();

        assertEquals(1, standard.plainCalls);
        assertEquals(0, multicast.plainCalls);
    }

    /** 配置型方法必须同时下发给两个委托，否则只有一档会收到。 */
    @Test
    public void configurationIsForwardedToBothDelegates() {
        assertSame(factory, factory.experimentalSetTextTrackTranscodingEnabled(true));
        assertSame(factory, factory.experimentalSetCodecsToParseWithinGopSampleDependencies(1));

        assertEquals(1, multicast.transcodingCalls);
        assertEquals(1, standard.transcodingCalls);
        assertEquals(1, multicast.gopCalls);
        assertEquals(1, standard.gopCalls);
    }

    // ------------------------------------------------------------------

    private static final class RecordingExtractorsFactory implements ExtractorsFactory {
        final List<Uri> uris = new ArrayList<>();
        int plainCalls;
        int transcodingCalls;
        int gopCalls;

        @NonNull
        @Override
        public Extractor[] createExtractors() {
            plainCalls++;
            return new Extractor[0];
        }

        @NonNull
        @Override
        public Extractor[] createExtractors(@NonNull Uri uri,
                                            @NonNull Map<String, List<String>> responseHeaders) {
            uris.add(uri);
            return new Extractor[0];
        }

        @NonNull
        @Override
        public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
            transcodingCalls++;
            return this;
        }

        @NonNull
        @Override
        public ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int flags) {
            gopCalls++;
            return this;
        }
    }
}
