package com.whyun.witv.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PacketRingBufferTest {

    private static final int MAX_PACKET = 16;

    private static byte[] payload(int marker, int length) {
        byte[] p = new byte[length];
        Arrays.fill(p, (byte) marker);
        return p;
    }

    @Test
    public void deliversPacketsInOrder() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(4, MAX_PACKET);
        byte[] dest = new byte[MAX_PACKET];

        for (int i = 1; i <= 3; i++) {
            assertTrue(ring.offer(payload(i, 4), 0, 4));
        }
        for (int i = 1; i <= 3; i++) {
            assertEquals(4, ring.poll(dest, 10));
            assertEquals(i, dest[0]);
        }
        assertEquals(0, ring.size());
    }

    @Test
    public void copiesPayloadIncludingOffset() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(2, MAX_PACKET);
        byte[] src = {9, 9, 1, 2, 3};
        ring.offer(src, 2, 3);

        byte[] dest = new byte[MAX_PACKET];
        assertEquals(3, ring.poll(dest, 10));
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOf(dest, 3));
    }

    /**
     * 核心语义：缓冲满了丢最旧的。直播保新不保旧，而且丢弃发生在我们自己手里、可统计——
     * 比让内核在 socket 层面任意丢一串要可控得多。
     */
    @Test
    public void dropsOldestWhenFull() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(3, MAX_PACKET);
        byte[] dest = new byte[MAX_PACKET];

        for (int i = 1; i <= 3; i++) {
            assertTrue("未满时不应丢弃", ring.offer(payload(i, 2), 0, 2));
        }
        assertEquals(0, ring.getDroppedPackets());

        // 第 4 个挤掉第 1 个
        assertEquals(false, ring.offer(payload(4, 2), 0, 2));
        assertEquals(1, ring.getDroppedPackets());
        assertEquals(3, ring.size());

        assertEquals(2, ring.poll(dest, 10));
        assertEquals(2, dest[0]);
        assertEquals(2, ring.poll(dest, 10));
        assertEquals(3, dest[0]);
        assertEquals(2, ring.poll(dest, 10));
        assertEquals(4, dest[0]);
    }

    @Test
    public void wrapsAroundRepeatedly() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(3, MAX_PACKET);
        byte[] dest = new byte[MAX_PACKET];

        for (int i = 1; i <= 50; i++) {
            ring.offer(payload(i & 0x7F, 2), 0, 2);
            assertEquals(2, ring.poll(dest, 10));
            assertEquals(i & 0x7F, dest[0]);
        }
        assertEquals(0, ring.getDroppedPackets());
    }

    @Test
    public void pollTimesOutWhenEmpty() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(2, MAX_PACKET);
        long start = System.currentTimeMillis();

        assertEquals(PacketRingBuffer.RESULT_TIMEOUT, ring.poll(new byte[MAX_PACKET], 50));

        assertTrue("应真的等待而不是立刻返回",
                System.currentTimeMillis() - start >= 45);
    }

    @Test
    public void closeWakesBlockedConsumer() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(2, MAX_PACKET);
        AtomicInteger result = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            try {
                result.set(ring.poll(new byte[MAX_PACKET], 10_000));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        consumer.start();
        Thread.sleep(50);
        ring.close();

        assertTrue("close 必须立刻唤醒等待者", done.await(2, TimeUnit.SECONDS));
        assertEquals(PacketRingBuffer.RESULT_CLOSED, result.get());
    }

    /** 关闭前已缓存的数据仍应能取走，避免丢掉最后一段。 */
    @Test
    public void bufferedDataSurvivesClose() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(4, MAX_PACKET);
        ring.offer(payload(7, 3), 0, 3);
        ring.close();

        byte[] dest = new byte[MAX_PACKET];
        assertEquals(3, ring.poll(dest, 10));
        assertEquals(7, dest[0]);
        assertEquals(PacketRingBuffer.RESULT_CLOSED, ring.poll(dest, 10));
    }

    @Test
    public void ignoresOversizedOrEmptyPayloads() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(2, MAX_PACKET);

        ring.offer(new byte[MAX_PACKET + 1], 0, MAX_PACKET + 1);
        ring.offer(new byte[4], 0, 0);

        assertEquals(0, ring.size());
        assertEquals(PacketRingBuffer.RESULT_TIMEOUT, ring.poll(new byte[MAX_PACKET], 10));
    }

    /**
     * 生产者（收包线程）全速写入、消费者慢一拍的真实形态：
     * 不能死锁，也不能丢失顺序——丢的只能是被挤掉的最旧的那些。
     */
    @Test
    public void producerNeverBlocksOnSlowConsumer() throws Exception {
        PacketRingBuffer ring = new PacketRingBuffer(8, MAX_PACKET);
        int total = 2000;
        AtomicLong produced = new AtomicLong();
        CountDownLatch producerDone = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            byte[] buf = new byte[4];
            for (int i = 0; i < total; i++) {
                buf[0] = (byte) (i & 0x7F);
                ring.offer(buf, 0, 4);
                produced.incrementAndGet();
            }
            producerDone.countDown();
        });
        producer.start();

        // 生产者必须跑完，不能被慢消费者卡住
        assertTrue("生产者被阻塞了", producerDone.await(5, TimeUnit.SECONDS));
        assertEquals(total, produced.get());
        assertTrue("应当发生了挤出", ring.getDroppedPackets() > 0);
        assertTrue(ring.size() <= 8);

        ring.close();
        producer.join(1000);
    }
}
