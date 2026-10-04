package com.whyun.witv.data;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 按内容自动解压 gzip 的输入流包装。
 *
 * <p>XMLTV 节目单动辄几十 MB，公开 EPG 源基本都提供 {@code .xml.gz}；m3u 也有 {@code .m3u.gz}。
 * OkHttp 的透明解压只在响应带 {@code Content-Encoding: gzip} 时生效，而这类地址返回的是
 * {@code Content-Type: application/gzip}——「内容本身就是压缩文件」与「传输编码」是两回事，
 * 后者 OkHttp 不会碰，拿到的是裸 gzip 字节。
 *
 * <p>判断依据是 <b>魔数</b>而非 URL 后缀或 {@code Content-Type}：两者都不可靠，
 * 有的源地址不带 {@code .gz} 却返回 gzip，有的把 {@code Content-Type} 标成 {@code text/xml}。
 * gzip 固定以 {@code 0x1F 0x8B} 开头，而 XML 与 m3u 都不可能以这两个字节开头，不会误判。
 */
public final class GzipAwareStreams {

    private static final int GZIP_MAGIC_BYTE_1 = 0x1F;
    private static final int GZIP_MAGIC_BYTE_2 = 0x8B;

    private GzipAwareStreams() {
    }

    /**
     * 内容是 gzip 就返回解压流，否则原样返回（已带缓冲）。
     *
     * @param source 原始流；调用方仍需负责关闭返回的流
     */
    @NonNull
    public static InputStream maybeDecompress(@NonNull InputStream source) throws IOException {
        // GZIPInputStream 内部也会缓冲，但这里必须自己包一层才能 mark/reset 做嗅探
        BufferedInputStream buffered = source instanceof BufferedInputStream
                ? (BufferedInputStream) source
                : new BufferedInputStream(source);
        return isGzip(buffered) ? new GZIPInputStream(buffered) : buffered;
    }

    /**
     * 窥探前两个字节判断是否为 gzip，并把流复位，不消耗数据。
     *
     * <p>流不足两个字节（空响应、截断）时一律按非 gzip 处理，交给后续解析器报错，
     * 这里不额外抛异常。
     */
    @VisibleForTesting
    static boolean isGzip(@NonNull BufferedInputStream in) throws IOException {
        in.mark(2);
        try {
            int first = in.read();
            int second = in.read();
            return first == GZIP_MAGIC_BYTE_1 && second == GZIP_MAGIC_BYTE_2;
        } finally {
            in.reset();
        }
    }
}
