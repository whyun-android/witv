package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocation;
import androidx.media3.exoplayer.upstream.Allocator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SwitchableLoadControlTest {

    private SwitchableLoadControl loadControl;

    /**
     * DefaultLoadControl 会用 timeline + mediaPeriodId 反查 MediaItem 的 scheme 来区分
     * 本地播放与流式播放，所以不能传 {@link Timeline#EMPTY}。
     */
    private static final Timeline TIMELINE = new SinglePeriodTimeline(
            /* durationUs= */ C.TIME_UNSET,
            /* isSeekable= */ false,
            /* isDynamic= */ true,
            /* useLiveConfiguration= */ true,
            /* manifest= */ null,
            MediaItem.fromUri("udp://239.1.1.1:1234"));
    private static final MediaSource.MediaPeriodId PERIOD_ID =
            new MediaSource.MediaPeriodId(TIMELINE.getUidOfPeriod(0));

    @Before
    public void setUp() {
        loadControl = new SwitchableLoadControl();
        // DefaultLoadControl 的 per-player 状态在 onPrepared 时建立，后续调用都依赖它；
        // onTracksSelected 则确定 targetBufferBytes，缺它时基于字节数的判定没有意义。
        loadControl.onPrepared(PlayerId.UNSET);
        loadControl.onTracksSelected(parameters(0L), TrackGroupArray.EMPTY,
                new ExoTrackSelection[0]);
    }

    @After
    public void tearDown() {
        loadControl.onReleased(PlayerId.UNSET);
    }

    private static LoadControl.Parameters parameters(long bufferedDurationUs) {
        return new LoadControl.Parameters(
                PlayerId.UNSET,
                TIMELINE,
                PERIOD_ID,
                /* playbackPositionUs= */ 0L,
                bufferedDurationUs,
                /* playbackSpeed= */ 1.0f,
                /* playWhenReady= */ true,
                /* rebuffering= */ false,
                /* targetLiveOffsetUs= */ C.TIME_UNSET,
                /* lastRebufferRealtimeMs= */ C.TIME_UNSET);
    }

    @Test
    public void defaultsToStreamingProfile() {
        assertEquals(SwitchableLoadControl.Profile.STREAMING, loadControl.getProfile());
    }

    @Test
    public void profileForUrlPicksMulticastForUdpAndRtp() {
        assertEquals(SwitchableLoadControl.Profile.MULTICAST,
                SwitchableLoadControl.profileForUrl("udp://239.1.1.1:1234"));
        assertEquals(SwitchableLoadControl.Profile.MULTICAST,
                SwitchableLoadControl.profileForUrl("rtp://239.1.1.1:1234"));
        assertEquals(SwitchableLoadControl.Profile.STREAMING,
                SwitchableLoadControl.profileForUrl("http://a.com/live.m3u8"));
    }

    /**
     * 「不重建播放器就能换缓冲档」的前提：两个委托背后是同一个内存池，
     * 换档不会把播放器正在用的内存换掉。
     */
    @Test
    public void bothProfilesAllocateFromTheSamePool() {
        int segment = loadControl.getSharedAllocator().getIndividualAllocationLength();

        Allocation first = loadControl.getAllocator(PlayerId.UNSET).allocate();
        assertNotNull(first);
        assertEquals(segment, loadControl.getSharedAllocator().getTotalBytesAllocated());

        loadControl.setProfile(SwitchableLoadControl.Profile.MULTICAST);
        Allocation second = loadControl.getAllocator(PlayerId.UNSET).allocate();
        assertNotNull(second);
        assertEquals(2 * segment, loadControl.getSharedAllocator().getTotalBytesAllocated());
    }

    /**
     * 核心差异：缓冲 3 秒时，组播档（起播门槛 1.5s）应该起播，
     * HLS 档（起播门槛 6s）还要继续等。
     */
    @Test
    public void multicastProfileStartsPlaybackEarlierThanStreaming() {
        LoadControl.Parameters threeSeconds = parameters(3_000_000L);

        loadControl.setProfile(SwitchableLoadControl.Profile.STREAMING);
        assertFalse(loadControl.shouldStartPlayback(threeSeconds));

        loadControl.setProfile(SwitchableLoadControl.Profile.MULTICAST);
        assertTrue(loadControl.shouldStartPlayback(threeSeconds));
    }

    @Test
    public void neitherProfileStartsPlaybackWithEmptyBuffer() {
        LoadControl.Parameters empty = parameters(0L);

        loadControl.setProfile(SwitchableLoadControl.Profile.STREAMING);
        assertFalse(loadControl.shouldStartPlayback(empty));

        loadControl.setProfile(SwitchableLoadControl.Profile.MULTICAST);
        assertFalse(loadControl.shouldStartPlayback(empty));
    }

    /**
     * 组播档开启了 prioritizeTimeOverSizeThresholds，装载判定纯按时长，
     * 因此 maxBuffer=30s 这个配置是可直接断言的：
     * 40 秒必须停装，5 秒必须继续装。（HLS 档的装载判定掺了字节阈值与状态迟滞，
     * 属于 DefaultLoadControl 内部行为，不在这里断言。）
     */
    @Test
    public void multicastProfileStopsLoadingAtItsOwnMaxBuffer() {
        loadControl.setProfile(SwitchableLoadControl.Profile.MULTICAST);

        assertTrue(loadControl.shouldContinueLoading(parameters(5_000_000L)));
        assertFalse(loadControl.shouldContinueLoading(parameters(40_000_000L)));
    }

    /**
     * {@link LoadControl} 的大部分方法是 default 方法，漏覆写会在运行时抛异常而不是编译失败。
     * 这里把 ExoPlayer 实际会调用的那组方法全走一遍，确保转发完整。
     */
    @Test
    public void forwardsEveryMethodExoPlayerCalls() {
        LoadControl.Parameters params = parameters(1_000_000L);

        for (SwitchableLoadControl.Profile profile : SwitchableLoadControl.Profile.values()) {
            loadControl.setProfile(profile);
            assertNotNull(loadControl.getAllocator(PlayerId.UNSET));
            loadControl.getBackBufferDurationUs(PlayerId.UNSET);
            loadControl.retainBackBufferFromKeyframe(PlayerId.UNSET);
            loadControl.shouldContinueLoading(params);
            loadControl.shouldStartPlayback(params);
            loadControl.shouldContinuePreloading(PlayerId.UNSET, TIMELINE, PERIOD_ID,
                    /* bufferedDurationUs= */ 0L);
            loadControl.onTracksSelected(params, TrackGroupArray.EMPTY, new ExoTrackSelection[0]);
        }
        loadControl.onStopped(PlayerId.UNSET);
    }
}
