package com.whyun.witv.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class GzipAwareStreamsTest {

    private static final String XMLTV =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"cctv1\"/></tv>";

    /** 公开 EPG 源基本都是 .xml.gz，这是这个工具存在的理由。 */
    @Test
    public void decompressesGzipContent() throws IOException {
        assertEquals(XMLTV, readAll(GzipAwareStreams.maybeDecompress(
                new ByteArrayInputStream(gzip(XMLTV)))));
    }

    /** 未压缩的源必须原样通过，一个字节都不能少。 */
    @Test
    public void passesThroughPlainContent() throws IOException {
        assertEquals(XMLTV, readAll(GzipAwareStreams.maybeDecompress(
                new ByteArrayInputStream(XMLTV.getBytes(StandardCharsets.UTF_8)))));
    }

    /** 嗅探魔数时读掉的两个字节必须复位，否则解析器会看到缺头的内容。 */
    @Test
    public void doesNotConsumeLeadingBytes() throws IOException {
        InputStream wrapped = GzipAwareStreams.maybeDecompress(
                new ByteArrayInputStream("#EXTM3U\n#EXTINF:-1,CCTV1".getBytes(StandardCharsets.UTF_8)));
        assertEquals("#EXTM3U\n#EXTINF:-1,CCTV1", readAll(wrapped));
    }

    /** 已经是 BufferedInputStream 的流不再多包一层，但同样要能嗅探。 */
    @Test
    public void handlesAlreadyBufferedStream() throws IOException {
        InputStream source = new BufferedInputStream(new ByteArrayInputStream(gzip(XMLTV)));
        assertEquals(XMLTV, readAll(GzipAwareStreams.maybeDecompress(source)));
    }

    /** 空响应或只有一个字节时按非 gzip 处理，交给后续解析器报错，不在这里抛。 */
    @Test
    public void treatsTooShortStreamAsPlain() throws IOException {
        assertEquals("", readAll(GzipAwareStreams.maybeDecompress(
                new ByteArrayInputStream(new byte[0]))));
        assertEquals("\u001f", readAll(GzipAwareStreams.maybeDecompress(
                new ByteArrayInputStream(new byte[]{0x1F}))));
    }

    @Test
    public void detectsMagicBytes() throws IOException {
        assertTrue(GzipAwareStreams.isGzip(new BufferedInputStream(
                new ByteArrayInputStream(gzip(XMLTV)))));
        assertFalse(GzipAwareStreams.isGzip(new BufferedInputStream(
                new ByteArrayInputStream(XMLTV.getBytes(StandardCharsets.UTF_8)))));
        // 第一个字节对、第二个不对，不能误判
        assertFalse(GzipAwareStreams.isGzip(new BufferedInputStream(
                new ByteArrayInputStream(new byte[]{0x1F, 0x00, 0x00}))));
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }
}
