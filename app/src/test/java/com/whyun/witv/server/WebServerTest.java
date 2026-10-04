package com.whyun.witv.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WebServerTest {

    /**
     * 这个方法存在的直接原因：原来走 {@code session.parseBody()}，NanoHTTPD 用
     * {@code ContentType.getEncoding()} 解码，而它在 Content-Type 不带 charset 时默认
     * US-ASCII，于是「北京」这样的列表名在 Web 页面保存后全变成 {@code ??????}。
     */
    @Test
    public void decodesChineseBodyAsUtf8() throws IOException {
        String json = "{\"name\":\"北京联通\"}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(json, WebServer.readBodyFrom(new ByteArrayInputStream(raw), raw.length));
    }

    /** 一个汉字是 3 个 UTF-8 字节，长度必须按字节算而不是按字符算。 */
    @Test
    public void readsExactByteLengthNotCharLength() throws IOException {
        String json = "{\"name\":\"央视\"}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(json.length() + 4, raw.length);
        assertEquals(json, WebServer.readBodyFrom(new ByteArrayInputStream(raw), raw.length));
    }

    /**
     * {@code InputStream.read} 允许返回少于请求的字节数。若只读一次就收工，
     * 多字节汉字会被从中间截断，照样乱码——所以必须循环读满。
     */
    @Test
    public void readsFullBodyWhenStreamReturnsPartialChunks() throws IOException {
        String json = "{\"name\":\"北京联通组播\",\"url\":\"http://example.com/a.m3u\"}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(json, WebServer.readBodyFrom(new ChunkedInputStream(raw, 3), raw.length));
    }

    /** 流提前结束时按已读到的部分解码，而不是抛异常或返回整块空字节。 */
    @Test
    public void toleratesTruncatedStream() throws IOException {
        byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals("abc", WebServer.readBodyFrom(new ByteArrayInputStream(raw), 16));
    }

    /** GET / DELETE 没有请求体，不能因为缺少 Content-Length 就报错。 */
    @Test
    public void returnsEmptyWithoutBody() throws IOException {
        assertEquals("", WebServer.readBodyFrom(null, 10));
        assertEquals("", WebServer.readBodyFrom(new ByteArrayInputStream(new byte[0]), 0));
        assertEquals("", WebServer.readBodyFrom(new ByteArrayInputStream(new byte[0]), -1));
    }

    /** 每次 read 最多吐出固定字节数，模拟 socket 的分片到达。 */
    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int chunkSize;
        private int position;

        ChunkedInputStream(byte[] data, int chunkSize) {
            this.data = data;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(Math.min(chunkSize, length), data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
