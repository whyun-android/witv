package com.whyun.witv.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 组播直播地址（{@code udp://} / {@code rtp://}）的规范化与 udpxy 代理改写。
 *
 * <p>m3u 源里组播地址写法很杂（VLC 风格 {@code udp://@group:port}、SSM 风格
 * {@code udp://@src@group:port}），先统一成 {@code scheme://group:port} 再交给播放器；
 * 配置了组播转单播代理（udpxy）时则整条改写成 HTTP 单播地址。
 */
public final class MulticastUrlUtil {

    public static final String SCHEME_UDP = "udp";
    public static final String SCHEME_RTP = "rtp";

    private static final String SCHEME_SEPARATOR = "://";

    private MulticastUrlUtil() {
    }

    /** 取小写 scheme；无 {@code ://} 时返回空串。 */
    @NonNull
    public static String schemeOf(@Nullable String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        int sep = trimmed.indexOf(SCHEME_SEPARATOR);
        if (sep <= 0) {
            return "";
        }
        return trimmed.substring(0, sep).toLowerCase(Locale.US);
    }

    /** 是否为需要本地收流的组播/UDP 地址（{@code udp://} 或 {@code rtp://}）。 */
    public static boolean isMulticastStreamUrl(@Nullable String url) {
        String scheme = schemeOf(url);
        return SCHEME_UDP.equals(scheme) || SCHEME_RTP.equals(scheme);
    }

    /**
     * 规范化组播地址：小写 scheme，去掉 {@code @}（VLC 风格）与 SSM 源地址段。
     * 非组播地址只做 trim 原样返回。
     *
     * <pre>
     * udp://@@239.1.1.1:1234      → udp://239.1.1.1:1234
     * udp://@192.168.1.1@239.1.1.1:1234 → udp://239.1.1.1:1234
     * RTP://239.1.1.1:1234        → rtp://239.1.1.1:1234
     * </pre>
     */
    @NonNull
    public static String normalize(@Nullable String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        String scheme = schemeOf(trimmed);
        if (!SCHEME_UDP.equals(scheme) && !SCHEME_RTP.equals(scheme)) {
            return trimmed;
        }
        String rest = trimmed.substring(scheme.length() + SCHEME_SEPARATOR.length());
        // SSM / VLC 写法里 group:port 永远在最后一个 '@' 之后；UDP 地址没有合法的 userinfo。
        int lastAt = rest.lastIndexOf('@');
        if (lastAt >= 0) {
            rest = rest.substring(lastAt + 1);
        }
        if (rest.isEmpty()) {
            return trimmed;
        }
        return scheme + SCHEME_SEPARATOR + rest;
    }

    /**
     * 规范化 udpxy 代理地址：补 {@code http://}、去掉末尾 {@code /} 和用户可能粘贴进来的
     * {@code /udp}、{@code /rtp} 后缀。空输入返回空串表示未配置。
     */
    @NonNull
    public static String normalizeProxyBase(@Nullable String base) {
        if (base == null) {
            return "";
        }
        String trimmed = base.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String scheme = schemeOf(trimmed);
        String authorityAndPath;
        if (scheme.isEmpty()) {
            scheme = "http";
            authorityAndPath = trimmed;
        } else {
            authorityAndPath = trimmed.substring(scheme.length() + SCHEME_SEPARATOR.length());
        }
        authorityAndPath = stripTrailingSlashes(authorityAndPath);
        String lower = authorityAndPath.toLowerCase(Locale.US);
        if (lower.endsWith("/udp") || lower.endsWith("/rtp")) {
            authorityAndPath = authorityAndPath.substring(0, authorityAndPath.length() - 4);
            authorityAndPath = stripTrailingSlashes(authorityAndPath);
        }
        // 只剩 scheme（例如用户只填了 "http://"）视为未配置
        if (authorityAndPath.isEmpty()) {
            return "";
        }
        return scheme + SCHEME_SEPARATOR + authorityAndPath;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 把已规范化的组播地址改写成 udpxy 单播地址：
     * {@code rtp://239.1.1.1:1234} + {@code http://10.0.0.1:4022}
     * → {@code http://10.0.0.1:4022/rtp/239.1.1.1:1234}。
     *
     * @return 代理地址；入参不是组播地址或代理未配置时返回原地址
     */
    @NonNull
    public static String toProxyUrl(@NonNull String normalizedUrl, @Nullable String proxyBase) {
        String scheme = schemeOf(normalizedUrl);
        if (!SCHEME_UDP.equals(scheme) && !SCHEME_RTP.equals(scheme)) {
            return normalizedUrl;
        }
        String base = normalizeProxyBase(proxyBase);
        if (base.isEmpty()) {
            return normalizedUrl;
        }
        String rest = normalizedUrl.substring(scheme.length() + SCHEME_SEPARATOR.length());
        // udpxy 只认 group:port，截掉多余的 path / query
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String groupAndPort = rest.substring(0, cut);
        if (groupAndPort.isEmpty()) {
            return normalizedUrl;
        }
        return base + "/" + scheme + "/" + groupAndPort;
    }

    /**
     * 播放前的统一入口：规范化地址，并在配置了 udpxy 代理时改写成 HTTP 单播。
     *
     * @param rawUrl    m3u 里的原始地址
     * @param proxyBase udpxy 代理前缀，空表示直接收组播
     */
    @NonNull
    public static String resolvePlaybackUrl(@Nullable String rawUrl, @Nullable String proxyBase) {
        String normalized = normalize(rawUrl);
        if (!isMulticastStreamUrl(normalized)) {
            return normalized;
        }
        return toProxyUrl(normalized, proxyBase);
    }
}
