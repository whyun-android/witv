package com.whyun.witv.player;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * 可在「HTTP/HLS 直播」与「UDP/RTP 组播」两套缓冲策略之间切换的 {@link LoadControl}。
 *
 * <p>两者的取舍完全相反：
 * <ul>
 *   <li>HLS 直播能从服务端回拉历史分片，所以主动离 live edge 远一点、缓冲厚一点，用延迟换抗抖动；</li>
 *   <li>组播是实时推流，服务端没有可回拉的缓冲——缓冲再厚也只是单纯增加开播等待，
 *       所以起播门槛要低得多（6s → 1.5s）。</li>
 * </ul>
 *
 * <p>两个委托共享同一个 {@link DefaultAllocator}，因此切换策略不会换掉播放器正在使用的内存池，
 * 可以在 {@code player.stop()} 之后、{@code prepare()} 之前安全切换，无需重建 ExoPlayer。
 * 生命周期回调同时转发给两个委托，保证未激活的那个状态不会漂移。
 *
 * <p>注意 {@code DefaultLoadControl.getAllocator()} 返回的是按 playerId 过滤的包装器，
 * 字节用量记在各自实例里。{@link #getAllocator} 固定取 streaming 委托的包装器，因此只有它的
 * 字节统计是准的——multicast 委托因此开启了 {@code prioritizeTimeOverSizeThresholds}，
 * 只按时长决策，不会去读自己看不到的字节计数。
 */
@OptIn(markerClass = UnstableApi.class)
public final class SwitchableLoadControl implements LoadControl {

    /** 缓冲策略 */
    public enum Profile {
        /** HTTP / HLS / DASH 等可回拉的流 */
        STREAMING,
        /** udp:// rtp:// 实时组播 */
        MULTICAST
    }

    // HLS 直播：厚缓冲换抗抖动
    private static final int STREAMING_MIN_BUFFER_MS = 35_000;
    private static final int STREAMING_MAX_BUFFER_MS = 90_000;
    private static final int STREAMING_BUFFER_FOR_PLAYBACK_MS = 6_000;
    private static final int STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 15_000;

    // 组播：实时流，优先压低开播等待
    private static final int MULTICAST_MIN_BUFFER_MS = 8_000;
    private static final int MULTICAST_MAX_BUFFER_MS = 30_000;
    private static final int MULTICAST_BUFFER_FOR_PLAYBACK_MS = 1_500;
    private static final int MULTICAST_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000;

    private final DefaultAllocator sharedAllocator;
    private final DefaultLoadControl streamingDelegate;
    private final DefaultLoadControl multicastDelegate;

    private volatile Profile profile = Profile.STREAMING;

    public SwitchableLoadControl() {
        DefaultAllocator sharedAllocator =
                new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
        this.sharedAllocator = sharedAllocator;
        this.streamingDelegate = new DefaultLoadControl.Builder()
                .setAllocator(sharedAllocator)
                .setBufferDurationsMs(
                        STREAMING_MIN_BUFFER_MS,
                        STREAMING_MAX_BUFFER_MS,
                        STREAMING_BUFFER_FOR_PLAYBACK_MS,
                        STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build();
        this.multicastDelegate = new DefaultLoadControl.Builder()
                .setAllocator(sharedAllocator)
                .setBufferDurationsMs(
                        MULTICAST_MIN_BUFFER_MS,
                        MULTICAST_MAX_BUFFER_MS,
                        MULTICAST_BUFFER_FOR_PLAYBACK_MS,
                        MULTICAST_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    /**
     * 切换缓冲策略。必须在 {@code player.prepare()} 之前调用（{@link PlayerManager} 在每次
     * 换源时、{@code stop()} 之后调用）。
     */
    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public Profile getProfile() {
        return profile;
    }

    /** 按播放地址挑选策略 */
    public static Profile profileForUrl(String url) {
        return MulticastUrlUtil.isMulticastStreamUrl(url) ? Profile.MULTICAST : Profile.STREAMING;
    }

    /** 两个委托共用的内存池；仅用于测试验证「切档不换池」这一前提。 */
    @VisibleForTesting
    DefaultAllocator getSharedAllocator() {
        return sharedAllocator;
    }

    private LoadControl active() {
        return profile == Profile.MULTICAST ? multicastDelegate : streamingDelegate;
    }

    // --- 生命周期：两个委托都要收到，避免未激活的一侧状态漂移 ---

    @Override
    public void onPrepared(@NonNull PlayerId playerId) {
        streamingDelegate.onPrepared(playerId);
        multicastDelegate.onPrepared(playerId);
    }

    @Override
    public void onStopped(@NonNull PlayerId playerId) {
        streamingDelegate.onStopped(playerId);
        multicastDelegate.onStopped(playerId);
    }

    @Override
    public void onReleased(@NonNull PlayerId playerId) {
        streamingDelegate.onReleased(playerId);
        multicastDelegate.onReleased(playerId);
    }

    @Override
    public void onTracksSelected(@NonNull Parameters parameters,
                                 @NonNull TrackGroupArray trackGroups,
                                 @NonNull ExoTrackSelection[] trackSelections) {
        streamingDelegate.onTracksSelected(parameters, trackGroups, trackSelections);
        multicastDelegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    // --- 决策：只问当前策略 ---

    @NonNull
    @Override
    public Allocator getAllocator(@NonNull PlayerId playerId) {
        // 固定取 streaming 委托：两者底层是同一个内存池，但字节用量统计各记各的，
        // 必须始终从同一侧取，播放器的分配才会被完整记账。
        return streamingDelegate.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(@NonNull PlayerId playerId) {
        return active().getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(@NonNull PlayerId playerId) {
        return active().retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinueLoading(@NonNull Parameters parameters) {
        return active().shouldContinueLoading(parameters);
    }

    @Override
    public boolean shouldContinuePreloading(@NonNull PlayerId playerId,
                                            @NonNull Timeline timeline,
                                            @NonNull MediaSource.MediaPeriodId mediaPeriodId,
                                            long bufferedDurationUs) {
        return active().shouldContinuePreloading(playerId, timeline, mediaPeriodId,
                bufferedDurationUs);
    }

    @Override
    public boolean shouldStartPlayback(@NonNull Parameters parameters) {
        return active().shouldStartPlayback(parameters);
    }
}
