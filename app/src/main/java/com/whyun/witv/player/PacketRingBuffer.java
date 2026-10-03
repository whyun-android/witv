package com.whyun.witv.player;

import androidx.annotation.Nullable;

/**
 * 收包线程与消费线程之间的有界环形缓冲。
 *
 * <p>存在的理由：UDP 组播是推模式，内核接收缓冲一满就永久丢包，没有重传。而
 * {@code SO_RCVBUF} 常被内核夹到 512KB——8Mbps 下只有约 0.5 秒的容错。如果直接由
 * ExoPlayer 的 Loader 线程去 {@code receive()}，那条线程还要做 TS 解复用、写采样队列、
 * 分配内存，解码器一挣扎或 GC 一停顿就腾不出手收包，内核立刻开始突发丢包。
 *
 * <p>所以让独立线程只管把 socket 抽干、把载荷塞进这里，消费侧慢一点也只是让这个缓冲变长，
 * 不会变成丢包。缓冲满了才丢，而且是我们自己丢、丢得到统计。
 *
 * <p>槽位预分配，收包路径无内存分配。
 */
final class PacketRingBuffer {

    private final byte[][] slots;
    private final int[] slotLengths;
    private final int capacity;
    private final int maxPacketSize;

    private int head;
    private int count;
    private boolean closed;
    private long droppedPackets;

    /**
     * @param capacity      可缓存的数据报个数
     * @param maxPacketSize 单个载荷最大字节数
     */
    PacketRingBuffer(int capacity, int maxPacketSize) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (maxPacketSize < 1) {
            throw new IllegalArgumentException("maxPacketSize must be positive: " + maxPacketSize);
        }
        this.capacity = capacity;
        this.maxPacketSize = maxPacketSize;
        this.slots = new byte[capacity][maxPacketSize];
        this.slotLengths = new int[capacity];
    }

    /**
     * 放入一个载荷。缓冲已满时丢弃**最旧**的一个——直播场景保新不保旧，
     * 而且丢弃发生在我们自己手里，可统计。
     *
     * @return true 表示未发生丢弃；false 表示挤掉了一个旧包
     */
    synchronized boolean offer(byte[] src, int offset, int length) {
        if (closed || length <= 0 || length > maxPacketSize) {
            return !closed;
        }
        boolean dropped = false;
        if (count == capacity) {
            // 丢掉最旧的：头指针前移一格，腾出位置
            head = (head + 1) % capacity;
            count--;
            droppedPackets++;
            dropped = true;
        }
        int writeIndex = (head + count) % capacity;
        System.arraycopy(src, offset, slots[writeIndex], 0, length);
        slotLengths[writeIndex] = length;
        count++;
        notifyAll();
        return !dropped;
    }

    /**
     * 取出下一个载荷，必要时阻塞等待。
     *
     * @param dest      目标缓冲区，容量需 &gt;= 构造时的 maxPacketSize
     * @param timeoutMs 最长等待毫秒数
     * @return 写入 {@code dest} 的字节数；超时返回 -1；已关闭返回 -2
     */
    synchronized int poll(byte[] dest, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (count == 0) {
            if (closed) {
                return RESULT_CLOSED;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return RESULT_TIMEOUT;
            }
            wait(remaining);
        }
        int length = slotLengths[head];
        System.arraycopy(slots[head], 0, dest, 0, length);
        head = (head + 1) % capacity;
        count--;
        return length;
    }

    /** {@link #poll} 超时 */
    static final int RESULT_TIMEOUT = -1;
    /** {@link #poll} 时缓冲已关闭且无残留数据 */
    static final int RESULT_CLOSED = -2;

    /** 关闭并唤醒所有等待者。已缓存但未取走的数据仍可继续 poll 出来。 */
    synchronized void close() {
        closed = true;
        notifyAll();
    }

    /** 因缓冲写满而被挤掉的包数 */
    synchronized long getDroppedPackets() {
        return droppedPackets;
    }

    /** 当前缓存的包数 */
    synchronized int size() {
        return count;
    }

    int getCapacity() {
        return capacity;
    }
}
