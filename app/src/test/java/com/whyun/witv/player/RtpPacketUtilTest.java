package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RtpPacketUtilTest {

    private final int[] out = new int[RtpPacketUtil.RESULT_SIZE];

    /** 组一个最小 RTP 包：V=2，无 padding/extension/CSRC。 */
    private static byte[] basicPacket(int sequence, int payloadType, int payloadLength) {
        byte[] p = new byte[RtpPacketUtil.FIXED_HEADER_SIZE + payloadLength];
        p[0] = (byte) 0x80;                       // V=2 P=0 X=0 CC=0
        p[1] = (byte) (payloadType & 0x7F);       // M=0 PT
        p[2] = (byte) ((sequence >> 8) & 0xFF);
        p[3] = (byte) (sequence & 0xFF);
        for (int i = 0; i < payloadLength; i++) {
            p[RtpPacketUtil.FIXED_HEADER_SIZE + i] = (byte) (i & 0xFF);
        }
        return p;
    }

    @Test
    public void parsesBasicMp2tPacket() {
        byte[] p = basicPacket(0x1234, RtpPacketUtil.PAYLOAD_TYPE_MP2T, 1316);

        assertTrue(RtpPacketUtil.parse(p, 0, p.length, out));
        assertEquals(12, out[RtpPacketUtil.RESULT_PAYLOAD_OFFSET]);
        assertEquals(1316, out[RtpPacketUtil.RESULT_PAYLOAD_LENGTH]);
        assertEquals(0x1234, out[RtpPacketUtil.RESULT_SEQUENCE]);
        assertEquals(33, out[RtpPacketUtil.RESULT_PAYLOAD_TYPE]);
    }

    @Test
    public void skipsCsrcList() {
        int csrcCount = 3;
        byte[] p = new byte[12 + 4 * csrcCount + 100];
        p[0] = (byte) (0x80 | csrcCount);
        p[1] = 33;
        p[2] = 0x00;
        p[3] = 0x07;

        assertTrue(RtpPacketUtil.parse(p, 0, p.length, out));
        assertEquals(12 + 4 * csrcCount, out[RtpPacketUtil.RESULT_PAYLOAD_OFFSET]);
        assertEquals(100, out[RtpPacketUtil.RESULT_PAYLOAD_LENGTH]);
        assertEquals(7, out[RtpPacketUtil.RESULT_SEQUENCE]);
    }

    @Test
    public void skipsExtensionHeader() {
        int extensionWords = 2;
        int headerLength = 12 + 4 + 4 * extensionWords;
        byte[] p = new byte[headerLength + 50];
        p[0] = (byte) 0x90;                       // V=2 X=1 CC=0
        p[1] = 33;
        p[14] = (byte) ((extensionWords >> 8) & 0xFF);
        p[15] = (byte) (extensionWords & 0xFF);

        assertTrue(RtpPacketUtil.parse(p, 0, p.length, out));
        assertEquals(headerLength, out[RtpPacketUtil.RESULT_PAYLOAD_OFFSET]);
        assertEquals(50, out[RtpPacketUtil.RESULT_PAYLOAD_LENGTH]);
    }

    @Test
    public void stripsTrailingPadding() {
        byte[] p = basicPacket(1, 33, 20);
        p[0] |= 0x20;                             // P=1
        p[p.length - 1] = 4;                      // 4 字节 padding（含自身）

        assertTrue(RtpPacketUtil.parse(p, 0, p.length, out));
        assertEquals(12, out[RtpPacketUtil.RESULT_PAYLOAD_OFFSET]);
        assertEquals(16, out[RtpPacketUtil.RESULT_PAYLOAD_LENGTH]);
    }

    @Test
    public void rejectsWrongVersion() {
        byte[] p = basicPacket(1, 33, 20);
        p[0] = 0x40;                              // V=1
        assertFalse(RtpPacketUtil.parse(p, 0, p.length, out));
    }

    @Test
    public void rejectsRawTsPacket() {
        // 裸 TS 以 0x47 开头 → 版本位为 01，必须被判为非 RTP，
        // MulticastDataSource 据此回退为透传。
        byte[] ts = new byte[188];
        ts[0] = 0x47;
        assertFalse(RtpPacketUtil.parse(ts, 0, ts.length, out));
    }

    @Test
    public void rejectsTooShortPacket() {
        byte[] p = new byte[11];
        p[0] = (byte) 0x80;
        assertFalse(RtpPacketUtil.parse(p, 0, p.length, out));
    }

    @Test
    public void rejectsHeaderLongerThanPacket() {
        // CC=15 需要 12+60=72 字节头，但包只有 40 字节
        byte[] p = new byte[40];
        p[0] = (byte) 0x8F;
        assertFalse(RtpPacketUtil.parse(p, 0, p.length, out));
    }

    @Test
    public void rejectsPaddingLargerThanPayload() {
        byte[] p = basicPacket(1, 33, 4);
        p[0] |= 0x20;
        p[p.length - 1] = (byte) 200;
        assertFalse(RtpPacketUtil.parse(p, 0, p.length, out));
    }

    @Test
    public void rejectsEmptyPayload() {
        byte[] p = basicPacket(1, 33, 0);
        assertFalse(RtpPacketUtil.parse(p, 0, p.length, out));
    }

    @Test
    public void parsesWithNonZeroOffset() {
        byte[] packet = basicPacket(0x00FF, 33, 30);
        byte[] buf = new byte[8 + packet.length];
        System.arraycopy(packet, 0, buf, 8, packet.length);

        assertTrue(RtpPacketUtil.parse(buf, 8, packet.length, out));
        assertEquals(8 + 12, out[RtpPacketUtil.RESULT_PAYLOAD_OFFSET]);
        assertEquals(30, out[RtpPacketUtil.RESULT_PAYLOAD_LENGTH]);
        assertEquals(0x00FF, out[RtpPacketUtil.RESULT_SEQUENCE]);
    }

    @Test
    public void rejectsOutOfBoundsRange() {
        byte[] p = basicPacket(1, 33, 20);
        assertFalse(RtpPacketUtil.parse(p, 0, p.length + 1, out));
        assertFalse(RtpPacketUtil.parse(p, -1, p.length, out));
    }

    @Test
    public void rejectsUndersizedResultArray() {
        byte[] p = basicPacket(1, 33, 20);
        assertFalse(RtpPacketUtil.parse(p, 0, p.length, new int[2]));
    }
}
