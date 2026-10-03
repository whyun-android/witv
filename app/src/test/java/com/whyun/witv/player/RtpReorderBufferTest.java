package com.whyun.witv.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RtpReorderBufferTest {

    private static final int CAPACITY = 8;
    private static final int MAX_PAYLOAD = 16;

    private final RtpReorderBuffer buffer = new RtpReorderBuffer(CAPACITY, MAX_PAYLOAD);
    private final byte[] dest = new byte[MAX_PAYLOAD];

    /** 用 payload 第一个字节标记包身份，方便断言吐出顺序。 */
    private void offer(int sequence, int marker) {
        byte[] payload = {(byte) marker, 0x11, 0x22, 0x33};
        buffer.offer(sequence, payload, 0, payload.length);
    }

    /** 取出所有当前可吐的包的 marker。 */
    private List<Integer> drainMarkers() {
        List<Integer> markers = new ArrayList<>();
        int len;
        while ((len = buffer.poll(dest)) > 0) {
            assertEquals(4, len);
            markers.add(dest[0] & 0xFF);
        }
        return markers;
    }

    @Test
    public void inOrderPacketsPassThroughImmediately() {
        for (int i = 0; i < 5; i++) {
            offer(100 + i, i);
            assertEquals(Arrays.asList(i), drainMarkers());
        }
        assertEquals(0, buffer.getLostPackets());
        assertEquals(0, buffer.getPendingCount());
    }

    @Test
    public void copiesPayloadBytes() {
        byte[] payload = {1, 2, 3, 4, 5};
        buffer.offer(7, payload, 0, payload.length);
        int len = buffer.poll(dest);
        assertEquals(5, len);
        assertArrayEquals(payload, Arrays.copyOf(dest, len));
    }

    @Test
    public void respectsSourceOffset() {
        byte[] src = {9, 9, 1, 2, 3};
        buffer.offer(1, src, 2, 3);
        int len = buffer.poll(dest);
        assertEquals(3, len);
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOf(dest, len));
    }

    @Test
    public void reordersSwappedPair() {
        offer(10, 0);
        assertEquals(Arrays.asList(0), drainMarkers());

        // 12 先到、11 后到
        offer(12, 2);
        assertEquals(Arrays.asList(), drainMarkers());
        offer(11, 1);
        assertEquals(Arrays.asList(1, 2), drainMarkers());
        assertEquals(0, buffer.getLostPackets());
    }

    @Test
    public void waitsBeforeDeclaringLossThenSkipsHole() {
        offer(10, 0);
        drainMarkers();

        // 11 永久丢失，后续 12..15 到达但还没到阈值（capacity/2 = 4）
        for (int i = 12; i <= 14; i++) {
            offer(i, i);
        }
        assertEquals(Arrays.asList(), drainMarkers());
        assertEquals(0, buffer.getLostPackets());

        // 第 4 个积压包触发跳洞
        offer(15, 15);
        assertEquals(Arrays.asList(12, 13, 14, 15), drainMarkers());
        assertEquals(1, buffer.getLostPackets());
    }

    @Test
    public void dropsLatePacketAfterHoleWasSkipped() {
        offer(10, 0);
        drainMarkers();
        for (int i = 12; i <= 15; i++) {
            offer(i, i);
        }
        drainMarkers();
        assertEquals(1, buffer.getLostPackets());

        // 迟到的 11 已经没有意义了
        offer(11, 11);
        assertEquals(Arrays.asList(), drainMarkers());
        assertEquals(1, buffer.getLatePackets());
    }

    @Test
    public void dropsDuplicatePacket() {
        offer(10, 0);
        offer(10, 99);
        assertEquals(Arrays.asList(0), drainMarkers());
        assertEquals(1, buffer.getLatePackets());
    }

    @Test
    public void handlesSequenceWraparound() {
        offer(65534, 1);
        assertEquals(Arrays.asList(1), drainMarkers());
        offer(65535, 2);
        assertEquals(Arrays.asList(2), drainMarkers());
        offer(0, 3);
        assertEquals(Arrays.asList(3), drainMarkers());
        offer(1, 4);
        assertEquals(Arrays.asList(4), drainMarkers());
        assertEquals(0, buffer.getLostPackets());
    }

    @Test
    public void reordersAcrossWraparound() {
        offer(65535, 1);
        drainMarkers();
        offer(1, 3);
        assertEquals(Arrays.asList(), drainMarkers());
        offer(0, 2);
        assertEquals(Arrays.asList(2, 3), drainMarkers());
        assertEquals(0, buffer.getLostPackets());
    }

    @Test
    public void largeJumpResetsWindow() {
        offer(10, 0);
        drainMarkers();

        // 跳变超出窗口：丢弃积压，以新序号重新起点
        offer(5000, 7);
        assertEquals(Arrays.asList(7), drainMarkers());
        assertEquals(1, buffer.getDiscontinuities());
    }

    @Test
    public void pollReturnsMinusOneWhenEmpty() {
        assertEquals(-1, buffer.poll(dest));
        offer(1, 1);
        assertTrue(buffer.poll(dest) > 0);
        assertEquals(-1, buffer.poll(dest));
    }

    @Test
    public void ignoresOversizedPayload() {
        byte[] tooBig = new byte[MAX_PAYLOAD + 1];
        buffer.offer(1, tooBig, 0, tooBig.length);
        assertEquals(-1, buffer.poll(dest));
        assertEquals(0, buffer.getPendingCount());
    }

    @Test
    public void resetClearsStateAndCounters() {
        offer(10, 0);
        drainMarkers();
        for (int i = 12; i <= 15; i++) {
            offer(i, i);
        }
        drainMarkers();
        assertTrue(buffer.getLostPackets() > 0);

        buffer.reset();
        assertEquals(0, buffer.getLostPackets());
        assertEquals(0, buffer.getLatePackets());
        assertEquals(0, buffer.getPendingCount());
        assertEquals(-1, buffer.poll(dest));

        // reset 后第一个包重新定义起点
        offer(900, 5);
        assertEquals(Arrays.asList(5), drainMarkers());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPowerOfTwoCapacity() {
        new RtpReorderBuffer(6, MAX_PAYLOAD);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositivePayloadSize() {
        new RtpReorderBuffer(8, 0);
    }
}
