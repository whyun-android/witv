package com.whyun.witv.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 按「BOM &gt; 响应声明的 charset &gt; UTF-8」的顺序解码文本。
 *
 * <p>这是 OkHttp {@code ResponseBody.string()} 的行为。之前 m3u 走的就是它，后来为了支持 gzip
 * 改成自己读流，如果顺手写成硬编码 UTF-8，GBK 编码的直播源（国内 IPTV 列表里相当常见）
 * 频道名和分组名就会整片变成乱码——所以这里必须把那套规则补回来。
 *
 * <p>BOM 优先于 Content-Type 里声明的 charset：BOM 是文件自己带的，而 Content-Type 是服务器
 * 配置出来的，后者经常是随手填的默认值。
 */
public final class BomAwareText {

    private BomAwareText() {
    }

    /**
     * @param bytes    完整内容
     * @param declared 响应头里声明的编码，没有则传 {@code null}
     */
    @NonNull
    public static String decode(@NonNull byte[] bytes, @Nullable Charset declared) {
        Bom bom = detectBom(bytes);
        if (bom != null) {
            // BOM 的那几个字节必须跳过，否则解出来的字符串以 U+FEFF 开头，
            // m3u 解析器连 #EXTM3U 都认不出来
            return new String(bytes, bom.length, bytes.length - bom.length, bom.charset);
        }
        return new String(bytes, declared != null ? declared : StandardCharsets.UTF_8);
    }

    @Nullable
    private static Bom detectBom(@NonNull byte[] b) {
        if (b.length >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF) {
            return new Bom(3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFE && b[1] == (byte) 0xFF) {
            return new Bom(2, StandardCharsets.UTF_16BE);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xFE) {
            return new Bom(2, StandardCharsets.UTF_16LE);
        }
        return null;
    }

    private static final class Bom {
        final int length;
        final Charset charset;

        Bom(int length, Charset charset) {
            this.length = length;
            this.charset = charset;
        }
    }
}
