package com.whyun.witv.player;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.List;
import java.util.Map;

/**
 * 按 URI scheme 选择解复用参数：只有 {@code udp://} / {@code rtp://} 使用直播 TS 调参，
 * 其余（HTTP 直连 {@code .ts}、HLS 等）一律保持 Media3 默认。
 *
 * <p>组播档用的是 {@code TsExtractor.MODE_SINGLE_PMT}，它假定流里只有一个 PMT，起播更快；
 * 但这个假定对「多节目 MPTS」或「播放中途重新下发 PMT」的流不成立，PID 映射会变味，
 * 典型症状是 {@code PesReader: Unexpected start code prefix}。运营商组播通常是单节目，
 * 吃得消这个假定；而 HTTP 上的 TS 来源五花八门，不该替它做这个假设。
 *
 * <p>{@code BundledExtractorsAdapter} 会调用带 URI 的那个重载，所以这里拿得到地址。
 * 注意经 udpxy 代理后 scheme 变成 http，会落到默认档——内容仍是裸 TS，默认档照样能解，
 * 只是少了 SINGLE_PMT 的起播加速。
 */
@OptIn(markerClass = UnstableApi.class)
final class MulticastAwareExtractorsFactory implements ExtractorsFactory {

    private final ExtractorsFactory multicastFactory;
    private final ExtractorsFactory standardFactory;

    MulticastAwareExtractorsFactory(ExtractorsFactory multicastFactory,
                                    ExtractorsFactory standardFactory) {
        this.multicastFactory = multicastFactory;
        this.standardFactory = standardFactory;
    }

    private ExtractorsFactory factoryFor(@Nullable Uri uri) {
        return uri != null && MulticastUrlUtil.isMulticastStreamUrl(uri.toString())
                ? multicastFactory
                : standardFactory;
    }

    @NonNull
    @Override
    public Extractor[] createExtractors() {
        // 没有 URI 可判断时按默认档，宁可慢一点也不要用错假定
        return standardFactory.createExtractors();
    }

    @NonNull
    @Override
    public Extractor[] createExtractors(@NonNull Uri uri,
                                        @NonNull Map<String, List<String>> responseHeaders) {
        return factoryFor(uri).createExtractors(uri, responseHeaders);
    }

    // 下面几个是 ExtractorsFactory 的配置型方法，必须转发给两个委托，
    // 否则只有其中一个会收到 DefaultMediaSourceFactory 下发的配置。

    @NonNull
    @Override
    public ExtractorsFactory setSubtitleParserFactory(
            @NonNull SubtitleParser.Factory subtitleParserFactory) {
        multicastFactory.setSubtitleParserFactory(subtitleParserFactory);
        standardFactory.setSubtitleParserFactory(subtitleParserFactory);
        return this;
    }

    @NonNull
    @Override
    public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(
            boolean textTrackTranscodingEnabled) {
        multicastFactory.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled);
        standardFactory.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled);
        return this;
    }

    @NonNull
    @Override
    public ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(
            int codecsToParseWithinGopSampleDependencies) {
        multicastFactory.experimentalSetCodecsToParseWithinGopSampleDependencies(
                codecsToParseWithinGopSampleDependencies);
        standardFactory.experimentalSetCodecsToParseWithinGopSampleDependencies(
                codecsToParseWithinGopSampleDependencies);
        return this;
    }
}
