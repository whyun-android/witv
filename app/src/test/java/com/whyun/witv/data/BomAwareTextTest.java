package com.whyun.witv.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class BomAwareTextTest {

    private static final String M3U = "#EXTM3U\n#EXTINF:-1,北京卫视\nrtp://239.3.1.118:8001";

    /** 没有 BOM、没有声明时按 UTF-8 解，这是绝大多数源的情况。 */
    @Test
    public void defaultsToUtf8() {
        assertEquals(M3U, BomAwareText.decode(M3U.getBytes(StandardCharsets.UTF_8), null));
    }

    /**
     * 国内直播源有不少是 GBK 的。之前走 ResponseBody.string() 时它们是好的，
     * 加 gzip 支持时如果写死 UTF-8，频道名会整片变成乱码——这个测试就是守着这条。
     */
    @Test
    public void honoursDeclaredGbkCharset() {
        Charset gbk = Charset.forName("GBK");
        assertEquals(M3U, BomAwareText.decode(M3U.getBytes(gbk), gbk));
    }

    /** BOM 优先于声明：BOM 是文件自己带的，Content-Type 常常是服务器随手填的默认值。 */
    @Test
    public void bomWinsOverDeclaredCharset() {
        byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                M3U.getBytes(StandardCharsets.UTF_8));
        assertEquals(M3U, BomAwareText.decode(withBom, Charset.forName("GBK")));
    }

    /** BOM 字节必须吃掉：留着的话字符串以 U+FEFF 开头，解析器连 #EXTM3U 都认不出来。 */
    @Test
    public void stripsBomBytes() {
        byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "#EXTM3U".getBytes(StandardCharsets.UTF_8));
        String decoded = BomAwareText.decode(withBom, null);
        assertEquals("#EXTM3U", decoded);
        assertEquals('#', decoded.charAt(0));
    }

    @Test
    public void detectsUtf16Boms() {
        assertEquals(M3U, BomAwareText.decode(
                concat(new byte[]{(byte) 0xFE, (byte) 0xFF}, M3U.getBytes(StandardCharsets.UTF_16BE)),
                null));
        assertEquals(M3U, BomAwareText.decode(
                concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, M3U.getBytes(StandardCharsets.UTF_16LE)),
                null));
    }

    /** 空响应和只有一两个字节时不能越界。 */
    @Test
    public void toleratesShortInput() {
        assertEquals("", BomAwareText.decode(new byte[0], null));
        assertEquals("#", BomAwareText.decode(new byte[]{'#'}, null));
        assertEquals("#E", BomAwareText.decode(new byte[]{'#', 'E'}, null));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(a);
            out.write(b);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
