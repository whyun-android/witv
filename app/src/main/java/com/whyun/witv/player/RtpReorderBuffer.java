package com.whyun.witv.player;

/**
 * RTP 抖动/乱序重排缓冲。按 16 位序号把乱序到达的载荷重新排好再吐给解复用器。
 *
 * <p>策略：
 * <ul>
 *   <li>下一个期望序号已到达 → 立即吐出，无额外延迟；</li>
 *   <li>出现空洞但缓冲里积压不足 {@code capacity/2} 个包 → 先等，给乱序包留出到达时间；</li>
 *   <li>积压达到阈值仍没补上 → 判定丢包，跳到最近的已缓冲包继续（计入 {@link #getLostPackets()}）；</li>
 *   <li>序号跳变超过一个窗口（换台、信号中断）→ 丢弃积压并以新序号重新起点。</li>
 * </ul>
 *
 * <p>槽位按 {@code seq & (capacity-1)} 直接寻址，窗口内不会冲突，收包路径无内存分配。
 * 非线程安全，仅供单个 {@link MulticastDataSource} 的收流线程使用。
 */
public final class RtpReorderBuffer {

    /** 默认窗口大小（包数），必须是 2 的幂 */
    public static final int DEFAULT_CAPACITY = 32;

    private static final int SEQ_MASK = 0xFFFF;
    private static final int SEQ_HALF = 0x8000;

    /**
     * 连续这么多个包都被判为「迟到」就认定发送端换了序号空间，按新起点重新同步。
     *
     * <p>发送端重启会把序号重置到一个更小的值。如果新起点落在当前期望值「之前」的半个序号空间里，
     * 每个新包都会被当成迟到丢弃，最坏要丢 32767 个包（760pkt/s 下约 43 秒黑屏）才会自然追上。
     * 正常网络不会出现连续几十个迟到包，所以这个阈值不会误伤。
     */
    private static final int LATE_PACKETS_BEFORE_RESYNC = 64;

    private final int capacity;
    private final int indexMask;
    private final int flushThreshold;
    private final int maxPayloadSize;

    private final byte[][] slots;
    private final int[] slotLength;
    private final int[] slotSequence;
    private final boolean[] slotUsed;

    private boolean started;
    private int expectedSequence;
    private int pending;

    private int consecutiveLatePackets;

    private long lostPackets;
    private long latePackets;
    private long discontinuities;

    /**
     * @param capacity       窗口包数，必须是 2 的幂且 &gt;= 2
     * @param maxPayloadSize 单个载荷最大字节数
     */
    public RtpReorderBuffer(int capacity, int maxPayloadSize) {
        if (capacity < 2 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        if (maxPayloadSize <= 0) {
            throw new IllegalArgumentException("maxPayloadSize must be positive: " + maxPayloadSize);
        }
        this.capacity = capacity;
        this.indexMask = capacity - 1;
        this.flushThreshold = Math.max(1, capacity / 2);
        this.maxPayloadSize = maxPayloadSize;
        this.slots = new byte[capacity][maxPayloadSize];
        this.slotLength = new int[capacity];
        this.slotSequence = new int[capacity];
        this.slotUsed = new boolean[capacity];
    }

    /**
     * 放入一个收到的 RTP 载荷。
     *
     * @param sequence  16 位 RTP 序号
     * @param src       载荷所在缓冲区
     * @param srcOffset 载荷起始下标
     * @param length    载荷字节数
     */
    public void offer(int sequence, byte[] src, int srcOffset, int length) {
        if (length <= 0 || length > maxPayloadSize) {
            return;
        }
        int seq = sequence & SEQ_MASK;
        if (!started) {
            started = true;
            expectedSequence = seq;
        }
        int diff = (seq - expectedSequence) & SEQ_MASK;
        if (diff >= SEQ_HALF) {
            // 序号早于当前期望：迟到或重复，直接丢弃
            latePackets++;
            if (++consecutiveLatePackets < LATE_PACKETS_BEFORE_RESYNC) {
                return;
            }
            // 连续这么多个都「迟到」只能是发送端重启换了序号空间，按新起点重来
            discontinuities++;
            clearSlots();
            expectedSequence = seq;
            diff = 0;
        }
        consecutiveLatePackets = 0;
        if (diff >= capacity) {
            // 跳变超出窗口：丢弃积压，以当前包为新起点
            discontinuities++;
            clearSlots();
            expectedSequence = seq;
        }
        int idx = seq & indexMask;
        if (slotUsed[idx] && slotSequence[idx] == seq) {
            latePackets++;
            return;
        }
        if (!slotUsed[idx]) {
            pending++;
        }
        System.arraycopy(src, srcOffset, slots[idx], 0, length);
        slotLength[idx] = length;
        slotSequence[idx] = seq;
        slotUsed[idx] = true;
    }

    /**
     * 取出下一个按序可用的载荷。
     *
     * @param dest 目标缓冲区，容量需 &gt;= 构造时的 maxPayloadSize
     * @return 写入 {@code dest} 的字节数；暂时没有可吐出的包时返回 -1（调用方应继续收包）
     */
    public int poll(byte[] dest) {
        if (!started || pending == 0) {
            return -1;
        }
        int idx = expectedSequence & indexMask;
        if (slotUsed[idx] && slotSequence[idx] == expectedSequence) {
            return emit(idx, dest);
        }
        if (pending < flushThreshold) {
            // 可能只是乱序，再等等
            return -1;
        }
        int bestIdx = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < capacity; i++) {
            if (!slotUsed[i]) {
                continue;
            }
            int diff = (slotSequence[i] - expectedSequence) & SEQ_MASK;
            if (diff >= SEQ_HALF) {
                continue;
            }
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            clearSlots();
            return -1;
        }
        lostPackets += bestDiff;
        expectedSequence = slotSequence[bestIdx];
        return emit(bestIdx, dest);
    }

    private int emit(int idx, byte[] dest) {
        int length = slotLength[idx];
        System.arraycopy(slots[idx], 0, dest, 0, length);
        slotUsed[idx] = false;
        pending--;
        expectedSequence = (expectedSequence + 1) & SEQ_MASK;
        return length;
    }

    /** 关闭/重开数据源时清空状态与统计。 */
    public void reset() {
        clearSlots();
        started = false;
        expectedSequence = 0;
        consecutiveLatePackets = 0;
        lostPackets = 0;
        latePackets = 0;
        discontinuities = 0;
    }

    private void clearSlots() {
        for (int i = 0; i < capacity; i++) {
            slotUsed[i] = false;
        }
        pending = 0;
    }

    /** 当前缓冲中的包数 */
    public int getPendingCount() {
        return pending;
    }

    /** 判定为丢失（空洞被跳过）的包数 */
    public long getLostPackets() {
        return lostPackets;
    }

    /** 迟到或重复而被丢弃的包数 */
    public long getLatePackets() {
        return latePackets;
    }

    /** 序号跳变超窗口导致缓冲重置的次数 */
    public long getDiscontinuities() {
        return discontinuities;
    }
}
