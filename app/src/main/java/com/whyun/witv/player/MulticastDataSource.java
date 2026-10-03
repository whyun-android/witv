package com.whyun.witv.player;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.UdpDataSource;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * {@code udp://} / {@code rtp://} 组播与单播收流数据源，内容按 MPEG-TS 交给解复用器。
 *
 * <p>相比 Media3 自带的 {@code UdpDataSource}（{@code final}，无法扩展）补齐了直播场景必需的几点：
 * <ul>
 *   <li><b>独立收包线程 + 环形缓冲</b>：UDP 是推模式，内核缓冲一满就永久丢包。若由 ExoPlayer 的
 *       Loader 线程直接 {@code receive()}，那条线程还要做 TS 解复用、写采样队列、分配内存，
 *       解码器一挣扎或 GC 一停顿就会突发丢包。见 {@link PacketRingBuffer}。</li>
 *   <li>可配置 {@code SO_RCVBUF}，并在被内核夹小时告警；</li>
 *   <li>在所有可用组播网卡上 join，避免多网卡（WiFi + 以太网）设备 join 错网卡收不到流；</li>
 *   <li>自动持有 WiFi 组播锁，断流时自动重新入组（见 {@link MulticastStallPolicy}）；</li>
 *   <li>{@code rtp://} 剥 RTP 头并做乱序重排；源实际发的是裸 TS 时自动回退为透传。</li>
 * </ul>
 */
@OptIn(markerClass = UnstableApi.class)
public final class MulticastDataSource extends BaseDataSource {

    private static final String TAG = "MulticastDs";

    /** 单个数据报最大字节数。常见组播 TS 为 7×188=1316，留足余量以容纳带扩展头的 RTP 包。 */
    public static final int DEFAULT_MAX_PACKET_SIZE = 2048;
    /** 收包超时；直播流持续推送，超时即视为断流，不宜太长（Media3 默认 8s 偏长）。 */
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 3_000;
    /** 内核接收缓冲，按 20Mbps × 1.5s 余量取值（常被内核夹小，会告警）。 */
    public static final int DEFAULT_RECEIVE_BUFFER_BYTES = 4 * 1024 * 1024;
    /**
     * 环形缓冲可缓存的数据报个数。2048 × 2048B = 4MB，8Mbps 下约 2.7 秒，
     * 足以吸收 GC 停顿与解码器抖动。
     */
    public static final int DEFAULT_RING_BUFFER_PACKETS = 2048;

    /** MPEG-TS 包同步字节，用于识别标注 rtp:// 但实际推裸 TS 的源 */
    private static final byte TS_SYNC_BYTE = 0x47;

    /** 吞吐统计打印间隔。断流前后的速率变化靠它定位。 */
    private static final long STATS_INTERVAL_MS = 10_000L;

    /** 消费侧等待在收包线程恢复预算之外额外留的余量 */
    private static final long CONSUMER_WAIT_SLACK_MS = 2_000L;

    private final Context appContext;
    private final int maxPacketSize;
    private final int socketTimeoutMs;
    private final int receiveBufferBytes;
    private final int ringBufferPackets;
    private final boolean rtpMode;
    private final MulticastLockHolder lockHolder;

    // --- 仅收包线程访问 ---
    private final byte[] packetBuffer;
    private final DatagramPacket packet;
    private final int[] rtpResult = new int[RtpPacketUtil.RESULT_SIZE];
    @Nullable
    private final RtpReorderBuffer reorderBuffer;
    @Nullable
    private final byte[] readerPayloadBuffer;
    private final MulticastStallPolicy stallPolicy =
            new MulticastStallPolicy(MulticastStallPolicy.DEFAULT_MAX_REJOIN_ATTEMPTS);
    /** rtp:// 源实际发的是裸 TS 时置位，后续按 udp 透传处理 */
    private boolean rawFallback;
    private long malformedPackets;
    private long lastStatsAtMs;
    private long statsWindowPackets;
    private long statsWindowBytes;

    // --- 仅消费线程访问 ---
    private final byte[] consumerBuffer;
    private int currentOffset;
    private int currentRemaining;

    // --- 跨线程 ---
    @Nullable
    private volatile Uri uri;
    @Nullable
    private volatile MulticastSocket socket;
    @Nullable
    private volatile PacketRingBuffer ringBuffer;
    @Nullable
    private volatile Thread readerThread;
    @Nullable
    private volatile IOException readerError;
    private volatile int readerErrorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
    private volatile boolean running;
    private volatile long openedAtMs;
    private volatile long lastPacketAtMs;
    private volatile long totalPackets;
    private volatile long totalBytes;

    @Nullable
    private InetSocketAddress joinedGroup;
    /** 已成功加入组的网卡；open() 之后不再修改，可跨线程安全读取 */
    private volatile List<NetworkInterface> joinedInterfaces = Collections.emptyList();
    private boolean lockAcquired;
    private boolean opened;

    public MulticastDataSource(Context context,
                               boolean rtpMode,
                               int maxPacketSize,
                               int socketTimeoutMs,
                               int receiveBufferBytes,
                               int ringBufferPackets,
                               MulticastLockHolder lockHolder) {
        super(/* isNetwork= */ true);
        this.appContext = context.getApplicationContext();
        this.rtpMode = rtpMode;
        this.maxPacketSize = maxPacketSize;
        this.socketTimeoutMs = socketTimeoutMs;
        this.receiveBufferBytes = receiveBufferBytes;
        this.ringBufferPackets = ringBufferPackets;
        this.lockHolder = lockHolder;
        this.packetBuffer = new byte[maxPacketSize];
        this.packet = new DatagramPacket(packetBuffer, 0, maxPacketSize);
        this.consumerBuffer = new byte[maxPacketSize];
        if (rtpMode) {
            this.reorderBuffer =
                    new RtpReorderBuffer(RtpReorderBuffer.DEFAULT_CAPACITY, maxPacketSize);
            this.readerPayloadBuffer = new byte[maxPacketSize];
        } else {
            this.reorderBuffer = null;
            this.readerPayloadBuffer = null;
        }
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws UdpDataSource.UdpDataSourceException {
        Uri openUri = dataSpec.uri;
        uri = openUri;
        String host = openUri.getHost();
        int port = openUri.getPort();
        if (host == null || host.isEmpty() || port <= 0) {
            throw wrap(new IOException("Invalid multicast URI (host/port missing): " + openUri),
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        transferInitializing(dataSpec);

        MulticastSocket created = null;
        try {
            InetAddress address = InetAddress.getByName(host);
            // 先建未绑定 socket，设好 SO_RCVBUF / SO_REUSEADDR 再 bind
            created = new MulticastSocket(null);
            created.setReuseAddress(true);
            try {
                created.setReceiveBufferSize(receiveBufferBytes);
            } catch (Exception e) {
                Log.w(TAG, "SO_RCVBUF " + receiveBufferBytes + " rejected: " + e.getMessage());
            }
            created.bind(new InetSocketAddress(port));
            created.setSoTimeout(socketTimeoutMs);
            warnIfReceiveBufferShrunk(created);

            if (address.isMulticastAddress()) {
                lockHolder.acquire();
                lockAcquired = true;
                InetSocketAddress group = new InetSocketAddress(address, port);
                joinMulticastGroup(created, address, group);
                joinedGroup = group;
            }
            socket = created;
            created = null;
        } catch (SecurityException e) {
            closeQuietly(created);
            releaseLock();
            throw wrap(new IOException(e), PlaybackException.ERROR_CODE_IO_NO_PERMISSION);
        } catch (IOException e) {
            closeQuietly(created);
            releaseLock();
            throw wrap(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }

        if (reorderBuffer != null) {
            reorderBuffer.reset();
        }
        stallPolicy.reset();
        rawFallback = false;
        malformedPackets = 0;
        openedAtMs = SystemClock.elapsedRealtime();
        lastPacketAtMs = openedAtMs;
        lastStatsAtMs = openedAtMs;
        totalPackets = 0;
        totalBytes = 0;
        statsWindowPackets = 0;
        statsWindowBytes = 0;
        currentOffset = 0;
        currentRemaining = 0;
        readerError = null;

        ringBuffer = new PacketRingBuffer(ringBufferPackets, maxPacketSize);
        running = true;
        Thread thread = new Thread(this::runReaderLoop, "witv-multicast-rx");
        thread.setDaemon(true);
        // 收包是硬实时的：晚一点就是永久丢包，优先级给高一档
        thread.setPriority(Thread.MAX_PRIORITY);
        readerThread = thread;
        thread.start();

        opened = true;
        transferStarted(dataSpec);
        Log.i(TAG, String.format(Locale.US,
                "Opened %s (rtp=%b, rcvbuf=%d, ring=%d pkts, timeout=%dms, iface=%s)",
                openUri, rtpMode, actualReceiveBufferSize(), ringBufferPackets, socketTimeoutMs,
                describeJoinedInterfaces()));
        return C.LENGTH_UNSET;
    }

    // ------------------------------------------------------------------
    // 收包线程
    // ------------------------------------------------------------------

    /**
     * 唯一职责：尽快把 socket 抽干、塞进环形缓冲。这里不做任何可能变慢的事
     * （解复用、采样队列、渲染都在别的线程），否则就失去了独立线程的意义。
     */
    private void runReaderLoop() {
        PacketRingBuffer ring = ringBuffer;
        MulticastSocket active = socket;
        if (ring == null || active == null) {
            return;
        }
        while (running) {
            try {
                // DatagramPacket 收完会把 length 改成实际长度，每次收包前必须复位
                packet.setLength(maxPacketSize);
                active.receive(packet);
                int length = packet.getLength();
                if (length <= 0) {
                    continue;
                }
                onPacketReceived(length);
                dispatchPayload(ring, length);
            } catch (SocketTimeoutException e) {
                if (!running || !handleReceiveTimeout(e)) {
                    break;
                }
            } catch (IOException e) {
                if (!running) {
                    // close() 关掉 socket 导致的异常，属于正常退出
                    break;
                }
                failReader(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
                break;
            } catch (RuntimeException e) {
                failReader(new IOException(e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
                break;
            }
        }
        ring.close();
    }

    /** 把刚收到的数据报转成载荷放进环形缓冲（RTP 模式下先剥头重排）。 */
    private void dispatchPayload(PacketRingBuffer ring, int length) {
        if (!rtpMode || rawFallback || reorderBuffer == null || readerPayloadBuffer == null) {
            ring.offer(packetBuffer, 0, length);
            return;
        }
        if (RtpPacketUtil.parse(packetBuffer, 0, length, rtpResult)) {
            reorderBuffer.offer(rtpResult[RtpPacketUtil.RESULT_SEQUENCE],
                    packetBuffer,
                    rtpResult[RtpPacketUtil.RESULT_PAYLOAD_OFFSET],
                    rtpResult[RtpPacketUtil.RESULT_PAYLOAD_LENGTH]);
            int ready;
            while ((ready = reorderBuffer.poll(readerPayloadBuffer)) > 0) {
                ring.offer(readerPayloadBuffer, 0, ready);
            }
            return;
        }
        if (packetBuffer[0] == TS_SYNC_BYTE) {
            // 源标了 rtp:// 但推的是裸 TS，整条流按 UDP 透传
            Log.i(TAG, "Stream declared rtp:// but carries raw TS; switching to passthrough");
            rawFallback = true;
            ring.offer(packetBuffer, 0, length);
            return;
        }
        malformedPackets++;
        if (malformedPackets == 1 || malformedPackets % 500 == 0) {
            Log.w(TAG, "Dropped malformed RTP packet(s): " + malformedPackets);
        }
    }

    /**
     * @return true 表示已重新入组、收包循环应继续；false 表示判定断流、循环应退出
     */
    private boolean handleReceiveTimeout(SocketTimeoutException e) {
        long now = SystemClock.elapsedRealtime();
        Log.w(TAG, String.format(Locale.US,
                "No packet for %dms on %s - stream stalled. "
                        + "Session: %d packets / %d bytes over %dms%s",
                now - lastPacketAtMs, uri, totalPackets, totalBytes, now - openedAtMs,
                reorderBuffer != null ? ", rtpLost=" + reorderBuffer.getLostPackets() : ""));
        // IGMP 成员被剪掉时，重新入组等于补发一个 Membership Report，流会立刻回来。
        // 这正是「重进频道就好了」背后的机制，没理由让用户手动做。
        if (joinedGroup != null && stallPolicy.shouldRejoin() && rejoinMulticastGroup()) {
            return true;
        }
        failReader(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
        return false;
    }

    private void failReader(IOException e, int errorCode) {
        readerErrorCode = errorCode;
        readerError = e;
    }

    private void onPacketReceived(int length) {
        long now = SystemClock.elapsedRealtime();
        if (stallPolicy.getRejoinCount() > 0) {
            Log.i(TAG, "Stream recovered after rejoin: " + uri);
        }
        stallPolicy.onPacketReceived();
        lastPacketAtMs = now;
        totalPackets++;
        totalBytes += length;
        statsWindowPackets++;
        statsWindowBytes += length;

        long windowMs = now - lastStatsAtMs;
        if (windowMs < STATS_INTERVAL_MS) {
            return;
        }
        PacketRingBuffer ring = ringBuffer;
        // 码率按窗口实际时长算，避免打印间隔抖动导致数字失真
        long kbps = windowMs > 0 ? (statsWindowBytes * 8L) / windowMs : 0L;
        Log.i(TAG, String.format(Locale.US,
                "Receiving %s: %d pkt/s, %d kbps (session %d packets)%s%s",
                uri, statsWindowPackets * 1000L / windowMs, kbps, totalPackets,
                reorderBuffer != null
                        ? String.format(Locale.US, ", rtpLost=%d late=%d disc=%d",
                                reorderBuffer.getLostPackets(), reorderBuffer.getLatePackets(),
                                reorderBuffer.getDiscontinuities())
                        : "",
                ring != null
                        ? String.format(Locale.US, ", ring=%d/%d overflow=%d",
                                ring.size(), ring.getCapacity(), ring.getDroppedPackets())
                        : ""));
        lastStatsAtMs = now;
        statsWindowPackets = 0;
        statsWindowBytes = 0;
    }

    /**
     * 在所有可用的组播网卡上加入该组。
     *
     * <p>不能只认 {@code getActiveNetwork()}：IPTV 常接在没有公网的以太网口上，Android 不会把
     * 这种网络当作活动网络；此时在 WiFi 上 {@code joinGroup} 会「成功」（不抛异常），但一个组播包
     * 都收不到，而基于异常的回退永远不会触发。多 join 几张网卡的代价只是几个 IGMP 报文，
     * 换来的是多网卡盒子上能真正收到流。
     */
    private void joinMulticastGroup(MulticastSocket target, InetAddress address,
                                    InetSocketAddress group) throws IOException {
        List<NetworkInterface> joined = new ArrayList<>();
        for (NetworkInterface ni : collectMulticastInterfaces()) {
            try {
                target.joinGroup(group, ni);
                joined.add(ni);
            } catch (IOException e) {
                Log.d(TAG, "joinGroup on " + ni.getName() + " failed: " + e.getMessage());
            }
        }
        if (!joined.isEmpty()) {
            joinedInterfaces = Collections.unmodifiableList(joined);
            return;
        }
        // 一张都没成功：回退到由系统路由表选择出口网卡
        Log.w(TAG, "No interface accepted the multicast join; falling back to system routing");
        target.joinGroup(address);
        joinedInterfaces = Collections.emptyList();
    }

    /** 候选网卡：活动网络优先（通常就是对的那张），其余可用组播网卡随后。 */
    private List<NetworkInterface> collectMulticastInterfaces() {
        List<NetworkInterface> candidates = new ArrayList<>();
        NetworkInterface preferred = resolveActiveMulticastInterface();
        if (preferred != null) {
            candidates.add(preferred);
        }
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (isUsableMulticastInterface(ni) && !containsByName(candidates, ni)) {
                    candidates.add(ni);
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Unable to enumerate network interfaces: " + e.getMessage());
        }
        return candidates;
    }

    private static boolean containsByName(List<NetworkInterface> list, NetworkInterface ni) {
        for (NetworkInterface existing : list) {
            if (existing.getName().equals(ni.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUsableMulticastInterface(NetworkInterface ni) {
        try {
            if (ni.isLoopback() || !ni.isUp() || !ni.supportsMulticast()) {
                return false;
            }
            // 没有 IPv4 地址的网卡上 join IPv4 组播没有意义
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                if (addresses.nextElement() instanceof Inet4Address) {
                    return true;
                }
            }
            return false;
        } catch (SocketException e) {
            return false;
        }
    }

    /**
     * 原地离组再入组，刷新 IGMP 成员关系。socket 与绑定端口都不变，所以只会丢失这期间
     * （微秒级）到达的包。
     *
     * @return 是否成功重新入组；失败时调用方应按断流报错
     */
    private boolean rejoinMulticastGroup() {
        MulticastSocket active = socket;
        InetSocketAddress group = joinedGroup;
        if (active == null || group == null) {
            return false;
        }
        Log.i(TAG, String.format(Locale.US,
                "Rejoining multicast group %s (attempt %d/%d)",
                group.getAddress().getHostAddress(),
                stallPolicy.getRejoinCount(), stallPolicy.getMaxRejoinAttempts()));
        List<NetworkInterface> interfaces = joinedInterfaces;
        // 已经不是成员了也无所谓，下面照样重新 join
        leaveGroupQuietly(active, group, interfaces);
        if (interfaces.isEmpty()) {
            try {
                active.joinGroup(group.getAddress());
                return true;
            } catch (IOException e) {
                Log.w(TAG, "Rejoin failed: " + e.getMessage());
                return false;
            }
        }
        int rejoined = 0;
        for (NetworkInterface ni : interfaces) {
            try {
                active.joinGroup(group, ni);
                rejoined++;
            } catch (IOException e) {
                Log.w(TAG, "Rejoin on " + ni.getName() + " failed: " + e.getMessage());
            }
        }
        return rejoined > 0;
    }

    private static void leaveGroupQuietly(MulticastSocket target, InetSocketAddress group,
                                          List<NetworkInterface> interfaces) {
        if (interfaces.isEmpty()) {
            try {
                target.leaveGroup(group.getAddress());
            } catch (Exception e) {
                Log.d(TAG, "leaveGroup failed (ignored): " + e.getMessage());
            }
            return;
        }
        for (NetworkInterface ni : interfaces) {
            try {
                target.leaveGroup(group, ni);
            } catch (Exception e) {
                Log.d(TAG, "leaveGroup on " + ni.getName() + " failed (ignored): "
                        + e.getMessage());
            }
        }
    }

    /**
     * 取当前活动网络对应的网卡。多网卡设备（盒子常同时有 eth0 和 wlan0）如果 join 错网卡，
     * 组播流根本到不了 socket。
     */
    @Nullable
    private NetworkInterface resolveActiveMulticastInterface() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return null;
            }
            LinkProperties props = cm.getLinkProperties(active);
            if (props == null || props.getInterfaceName() == null) {
                return null;
            }
            NetworkInterface ni = NetworkInterface.getByName(props.getInterfaceName());
            if (ni == null) {
                return null;
            }
            if (!ni.supportsMulticast()) {
                Log.w(TAG, "Active interface " + ni.getName() + " does not support multicast");
                return null;
            }
            return ni;
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve active multicast interface: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 消费线程（ExoPlayer Loader）
    // ------------------------------------------------------------------

    @Override
    public int read(@NonNull byte[] buffer, int offset, int readLength)
            throws UdpDataSource.UdpDataSourceException {
        if (readLength == 0) {
            return 0;
        }
        while (currentRemaining == 0) {
            fillFromRing();
        }
        int bytesRead = Math.min(currentRemaining, readLength);
        System.arraycopy(consumerBuffer, currentOffset, buffer, offset, bytesRead);
        currentOffset += bytesRead;
        currentRemaining -= bytesRead;
        bytesTransferred(bytesRead);
        return bytesRead;
    }

    private void fillFromRing() throws UdpDataSource.UdpDataSourceException {
        PacketRingBuffer ring = ringBuffer;
        if (ring == null) {
            throw wrap(new IOException("Data source closed"),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }
        // 必须比收包线程的完整恢复过程更长，否则会在它最后一次重新入组还没来得及收到包时
        // 就先行放弃，等于白配了重入组预算
        long waitMs = consumerWaitMs(socketTimeoutMs, stallPolicy.getMaxRejoinAttempts());
        int length;
        try {
            // 收包线程彻底失败时会写 readerError 并关闭环形缓冲，这里会被立刻唤醒，
            // 所以这个超时只是兜底
            length = ring.poll(consumerBuffer, waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw wrap(new IOException(e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        if (length > 0) {
            currentOffset = 0;
            currentRemaining = length;
            return;
        }
        IOException error = readerError;
        if (error != null) {
            throw wrap(error, readerErrorCode);
        }
        if (length == PacketRingBuffer.RESULT_CLOSED) {
            throw wrap(new IOException("Multicast receiver stopped"),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }
        throw wrap(new SocketTimeoutException("No multicast data for " + waitMs + "ms"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
    }

    /**
     * 消费侧的等待时长：收包线程会先超时一次、再重新入组 {@code maxRejoinAttempts} 次，
     * 每次各等一个 socket 超时，所以消费侧至少要等 {@code (maxRejoinAttempts + 1)} 个超时周期，
     * 外加一点余量。
     */
    static long consumerWaitMs(int socketTimeoutMs, int maxRejoinAttempts) {
        return (long) socketTimeoutMs * (Math.max(0, maxRejoinAttempts) + 1)
                + CONSUMER_WAIT_SLACK_MS;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        running = false;

        MulticastSocket active = socket;
        if (active != null) {
            if (joinedGroup != null) {
                leaveGroupQuietly(active, joinedGroup, joinedInterfaces);
            }
            // 关闭 socket 会让阻塞中的 receive() 立刻抛异常，收包线程据此退出
            closeQuietly(active);
            socket = null;
        }

        PacketRingBuffer ring = ringBuffer;
        if (ring != null) {
            ring.close();
        }

        Thread thread = readerThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        readerThread = null;

        joinedGroup = null;
        joinedInterfaces = Collections.emptyList();
        releaseLock();

        if (opened) {
            long now = SystemClock.elapsedRealtime();
            Log.i(TAG, String.format(Locale.US,
                    "Closing after %dms: %d packets / %d bytes, last packet %dms ago%s%s",
                    now - openedAtMs, totalPackets, totalBytes, now - lastPacketAtMs,
                    reorderBuffer != null
                            ? String.format(Locale.US, ", rtpLost=%d late=%d disc=%d malformed=%d",
                                    reorderBuffer.getLostPackets(), reorderBuffer.getLatePackets(),
                                    reorderBuffer.getDiscontinuities(), malformedPackets)
                            : "",
                    ring != null ? ", ringOverflow=" + ring.getDroppedPackets() : ""));
        }
        if (reorderBuffer != null) {
            reorderBuffer.reset();
        }
        ringBuffer = null;
        readerError = null;
        currentOffset = 0;
        currentRemaining = 0;
        uri = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    private String describeJoinedInterfaces() {
        List<NetworkInterface> interfaces = joinedInterfaces;
        if (interfaces.isEmpty()) {
            return "system-routing";
        }
        StringBuilder sb = new StringBuilder();
        for (NetworkInterface ni : interfaces) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(ni.getName());
        }
        return sb.toString();
    }

    private void releaseLock() {
        if (lockAcquired) {
            lockAcquired = false;
            lockHolder.release();
        }
    }

    private int actualReceiveBufferSize() {
        try {
            MulticastSocket active = socket;
            return active != null ? active.getReceiveBufferSize() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 内核会把 {@code SO_RCVBUF} 夹到 {@code net.core.rmem_max}，申请 4MB 实际可能只拿到几百 KB。
     * 这是高码率组播花屏丢包的直接原因，必须显式告警而不是静默接受。
     */
    private void warnIfReceiveBufferShrunk(MulticastSocket target) {
        try {
            int actual = target.getReceiveBufferSize();
            if (actual < receiveBufferBytes) {
                Log.w(TAG, String.format(Locale.US,
                        "SO_RCVBUF clamped by kernel: requested %d, got %d "
                                + "(net.core.rmem_max); relying on the %d-packet ring buffer",
                        receiveBufferBytes, actual, ringBufferPackets));
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read SO_RCVBUF: " + e.getMessage());
        }
    }

    private static void closeQuietly(@Nullable MulticastSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static UdpDataSource.UdpDataSourceException wrap(Throwable cause, int errorCode) {
        return new UdpDataSource.UdpDataSourceException(cause, errorCode);
    }

    /** 按 scheme 产出 {@link MulticastDataSource} 的工厂。 */
    public static final class Factory implements DataSource.Factory {
        private final Context context;
        private final boolean rtpMode;
        private final MulticastLockHolder lockHolder;
        private int maxPacketSize = DEFAULT_MAX_PACKET_SIZE;
        private int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;
        private int receiveBufferBytes = DEFAULT_RECEIVE_BUFFER_BYTES;
        private int ringBufferPackets = DEFAULT_RING_BUFFER_PACKETS;

        public Factory(Context context, boolean rtpMode, MulticastLockHolder lockHolder) {
            this.context = context.getApplicationContext();
            this.rtpMode = rtpMode;
            this.lockHolder = lockHolder;
        }

        public Factory setSocketTimeoutMs(int socketTimeoutMs) {
            this.socketTimeoutMs = socketTimeoutMs;
            return this;
        }

        public Factory setReceiveBufferBytes(int receiveBufferBytes) {
            this.receiveBufferBytes = receiveBufferBytes;
            return this;
        }

        public Factory setMaxPacketSize(int maxPacketSize) {
            this.maxPacketSize = maxPacketSize;
            return this;
        }

        public Factory setRingBufferPackets(int ringBufferPackets) {
            this.ringBufferPackets = ringBufferPackets;
            return this;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new MulticastDataSource(context, rtpMode, maxPacketSize, socketTimeoutMs,
                    receiveBufferBytes, ringBufferPackets, lockHolder);
        }
    }
}
