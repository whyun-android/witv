package com.whyun.witv.player;

/**
 * RFC 3550 RTP 固定头解析。组播 IPTV 里 RTP 载荷通常是 MPEG-TS（PT=33），
 * 必须剥掉 12 字节固定头（含 CSRC / 扩展头 / 尾部 padding）后才能交给 TsExtractor。
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * |                           timestamp                           |
 * |                             SSRC                              |
 * |                      CSRC (0..15 × 4 bytes)                   |
 * </pre>
 */
public final class RtpPacketUtil {

    /** RTP 固定头长度 */
    public static final int FIXED_HEADER_SIZE = 12;
    /** 本实现只接受 RFC 3550 的版本号 2 */
    public static final int RTP_VERSION = 2;
    /** MPEG-2 Transport Stream 的静态载荷类型 */
    public static final int PAYLOAD_TYPE_MP2T = 33;

    /** {@link #parse} 输出数组长度 */
    public static final int RESULT_SIZE = 4;
    /** 载荷在原数组中的起始下标 */
    public static final int RESULT_PAYLOAD_OFFSET = 0;
    /** 载荷字节数 */
    public static final int RESULT_PAYLOAD_LENGTH = 1;
    /** 16 位序号 */
    public static final int RESULT_SEQUENCE = 2;
    /** 7 位载荷类型 */
    public static final int RESULT_PAYLOAD_TYPE = 3;

    private RtpPacketUtil() {
    }

    /**
     * 解析一个 RTP 包。
     *
     * @param buf    数据报缓冲区
     * @param offset 包在缓冲区中的起始下标
     * @param length 包长度
     * @param out    长度至少 {@link #RESULT_SIZE} 的输出数组，成功时按 {@code RESULT_*} 下标填充
     * @return 解析成功返回 true；不是合法 RTP 包（版本不符、长度不足、头比包长）返回 false
     */
    public static boolean parse(byte[] buf, int offset, int length, int[] out) {
        if (buf == null || out == null || out.length < RESULT_SIZE) {
            return false;
        }
        if (length < FIXED_HEADER_SIZE || offset < 0 || offset + length > buf.length) {
            return false;
        }
        int b0 = buf[offset] & 0xFF;
        if ((b0 >>> 6) != RTP_VERSION) {
            return false;
        }
        boolean hasPadding = ((b0 >>> 5) & 0x01) == 1;
        boolean hasExtension = ((b0 >>> 4) & 0x01) == 1;
        int csrcCount = b0 & 0x0F;

        int headerLength = FIXED_HEADER_SIZE + 4 * csrcCount;
        if (hasExtension) {
            if (length < headerLength + 4) {
                return false;
            }
            int extensionWords = ((buf[offset + headerLength + 2] & 0xFF) << 8)
                    | (buf[offset + headerLength + 3] & 0xFF);
            headerLength += 4 + 4 * extensionWords;
        }
        if (headerLength >= length) {
            return false;
        }

        int payloadLength = length - headerLength;
        if (hasPadding) {
            int padding = buf[offset + length - 1] & 0xFF;
            // padding 计数包含自身，必须落在载荷范围内
            if (padding <= 0 || padding > payloadLength) {
                return false;
            }
            payloadLength -= padding;
            if (payloadLength <= 0) {
                return false;
            }
        }

        out[RESULT_PAYLOAD_OFFSET] = offset + headerLength;
        out[RESULT_PAYLOAD_LENGTH] = payloadLength;
        out[RESULT_SEQUENCE] = ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
        out[RESULT_PAYLOAD_TYPE] = buf[offset + 1] & 0x7F;
        return true;
    }
}
