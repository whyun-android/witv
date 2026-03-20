package com.whyun.witv.player;

import android.content.Context;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;

import com.whyun.witv.data.db.entity.ChannelSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PlayerManagerTest {

    private PlayerManager playerManager;
    private TestCallback callback;
    private Context context;

    static class TestCallback implements PlayerManager.Callback {
        int sourceSwitchingCount = 0;
        int lastSwitchIndex = -1;
        int lastSwitchTotal = -1;
        boolean allSourcesFailed = false;
        int allSourcesFailedCount = 0;
        int playbackStartedCount = 0;
        int lastPlaybackSourceIndex = -1;
        String lastError = null;

        @Override
        public void onSourceSwitching(int newIndex, int total) {
            sourceSwitchingCount++;
            lastSwitchIndex = newIndex;
            lastSwitchTotal = total;
        }

        @Override
        public void onAllSourcesFailed() {
            allSourcesFailed = true;
            allSourcesFailedCount++;
        }

        @Override
        public void onPlaybackStarted(int sourceIndex, int total) {
            playbackStartedCount++;
            lastPlaybackSourceIndex = sourceIndex;
        }

        @Override
        public void onError(String message) {
            lastError = message;
        }

        void reset() {
            sourceSwitchingCount = 0;
            lastSwitchIndex = -1;
            lastSwitchTotal = -1;
            allSourcesFailed = false;
            allSourcesFailedCount = 0;
            playbackStartedCount = 0;
            lastPlaybackSourceIndex = -1;
            lastError = null;
        }
    }

    /**
     * Bypasses PlayerView (which requires Media3 theme resources unavailable in
     * Robolectric) by injecting an ExoPlayer directly via reflection and wiring
     * up the player listener.
     */
    private void initializePlayerViaReflection() throws Exception {
        ExoPlayer player = new ExoPlayer.Builder(context).build();

        Field playerField = PlayerManager.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(playerManager, player);

        Field listenerField = PlayerManager.class.getDeclaredField("playerListener");
        listenerField.setAccessible(true);
        player.addListener((androidx.media3.common.Player.Listener) listenerField.get(playerManager));
    }

    private Player.Listener getPlayerListener() throws Exception {
        Field listenerField = PlayerManager.class.getDeclaredField("playerListener");
        listenerField.setAccessible(true);
        return (Player.Listener) listenerField.get(playerManager);
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        playerManager = new PlayerManager(context);
        callback = new TestCallback();
        playerManager.setCallback(callback);
    }

    @After
    public void tearDown() {
        playerManager.release();
    }

    // ============================================================
    // playChannel: null / empty sources (no player init needed)
    // ============================================================

    @Test
    public void playChannelWithNullSourcesTriggersAllFailed() {
        playerManager.playChannel(null);
        assertTrue(callback.allSourcesFailed);
    }

    @Test
    public void playChannelWithEmptySourcesTriggersAllFailed() {
        playerManager.playChannel(new ArrayList<>());
        assertTrue(callback.allSourcesFailed);
    }

    // ============================================================
    // playChannel: state management (needs player)
    // ============================================================

    @Test
    public void playChannelSetsInitialState() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1)
        );

        playerManager.playChannel(sources);

        assertEquals(0, playerManager.getCurrentSourceIndex());
        assertEquals(2, playerManager.getSourceCount());
    }

    @Test
    public void playChannelWithSingleSource() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Collections.singletonList(
                new ChannelSource(1, "http://stream.example.com/live.m3u8", 0)
        );

        playerManager.playChannel(sources);

        assertEquals(0, playerManager.getCurrentSourceIndex());
        assertEquals(1, playerManager.getSourceCount());
        assertFalse(callback.allSourcesFailed);
    }

    @Test
    public void playChannelWithMultipleSources() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1),
                new ChannelSource(1, "http://c.com/3.m3u8", 2)
        );

        playerManager.playChannel(sources);

        assertEquals(3, playerManager.getSourceCount());
        assertEquals(0, playerManager.getCurrentSourceIndex());
    }

    @Test
    public void playChannelResetsState() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> first = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(first);
        assertEquals(2, playerManager.getSourceCount());

        List<ChannelSource> second = Collections.singletonList(
                new ChannelSource(2, "http://c.com/3.m3u8", 0)
        );
        playerManager.playChannel(second);

        assertEquals(0, playerManager.getCurrentSourceIndex());
        assertEquals(1, playerManager.getSourceCount());
    }

    // ============================================================
    // manualSwitchSource
    // ============================================================

    @Test
    public void manualSwitchToValidIndex() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1),
                new ChannelSource(1, "http://c.com/3.m3u8", 2)
        );
        playerManager.playChannel(sources);

        playerManager.manualSwitchSource(2);
        assertEquals(2, playerManager.getCurrentSourceIndex());
    }

    @Test
    public void manualSwitchToNegativeIndexIsIgnored() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(sources);

        playerManager.manualSwitchSource(-1);
        assertEquals(0, playerManager.getCurrentSourceIndex());
    }

    @Test
    public void manualSwitchToOutOfBoundsIndexIsIgnored() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(sources);

        playerManager.manualSwitchSource(5);
        assertEquals(0, playerManager.getCurrentSourceIndex());
    }

    // ============================================================
    // getPlayer lifecycle
    // ============================================================

    @Test
    public void getPlayerReturnsNonNullAfterInit() throws Exception {
        initializePlayerViaReflection();
        assertNotNull(playerManager.getPlayer());
    }

    @Test
    public void getPlayerReturnsNullAfterRelease() throws Exception {
        initializePlayerViaReflection();
        playerManager.release();
        assertNull(playerManager.getPlayer());
    }

    // ============================================================
    // pause / resume safety
    // ============================================================

    @Test
    public void pauseDoesNotCrash() throws Exception {
        initializePlayerViaReflection();
        playerManager.pause();
    }

    @Test
    public void resumeDoesNotCrash() throws Exception {
        initializePlayerViaReflection();
        playerManager.resume();
    }

    @Test
    public void pauseAfterReleaseDoesNotCrash() throws Exception {
        initializePlayerViaReflection();
        playerManager.release();
        playerManager.pause();
    }

    @Test
    public void resumeAfterReleaseDoesNotCrash() throws Exception {
        initializePlayerViaReflection();
        playerManager.release();
        playerManager.resume();
    }

    // ============================================================
    // release
    // ============================================================

    @Test
    public void releaseCleanup() throws Exception {
        initializePlayerViaReflection();
        playerManager.release();
        assertNull(playerManager.getPlayer());
    }

    @Test
    public void doubleReleaseDoesNotCrash() throws Exception {
        initializePlayerViaReflection();
        playerManager.release();
        playerManager.release();
    }

    // ============================================================
    // source timeout → auto-switch
    // ============================================================

    @Test
    public void timeoutTriggersSourceSwitch() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(sources);
        assertEquals(0, playerManager.getCurrentSourceIndex());

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);

        assertEquals(1, playerManager.getCurrentSourceIndex());
        assertEquals(1, callback.sourceSwitchingCount);
    }

    @Test
    public void allSourcesTimeoutTriggersAllFailed() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> sources = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(sources);

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);
        assertFalse(callback.allSourcesFailed);

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);
        assertTrue(callback.allSourcesFailed);
    }

    // ============================================================
    // Channel switch: old timeout should not affect new channel
    // ============================================================

    @Test
    public void oldTimeoutInvalidatedOnChannelSwitch() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> channelA = Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://a.com/2.m3u8", 1)
        );
        playerManager.playChannel(channelA);
        assertEquals(0, playerManager.getCurrentSourceIndex());

        ShadowLooper.idleMainLooper(10_000, TimeUnit.MILLISECONDS);
        assertEquals(0, playerManager.getCurrentSourceIndex());

        List<ChannelSource> channelB = Collections.singletonList(
                new ChannelSource(2, "http://b.com/1.m3u8", 0)
        );
        playerManager.playChannel(channelB);
        callback.reset();

        ShadowLooper.idleMainLooper(5_000, TimeUnit.MILLISECONDS);

        assertEquals(0, playerManager.getCurrentSourceIndex());
        assertEquals(1, playerManager.getSourceCount());
        assertFalse(callback.allSourcesFailed);
        assertEquals(0, callback.sourceSwitchingCount);
    }

    @Test
    public void newChannelGetsOwnTimeout() throws Exception {
        initializePlayerViaReflection();

        List<ChannelSource> channelA = Collections.singletonList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0)
        );
        playerManager.playChannel(channelA);

        ShadowLooper.idleMainLooper(10_000, TimeUnit.MILLISECONDS);

        List<ChannelSource> channelB = Arrays.asList(
                new ChannelSource(2, "http://b.com/1.m3u8", 0),
                new ChannelSource(2, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(channelB);
        callback.reset();

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);

        assertEquals(1, playerManager.getCurrentSourceIndex());
        assertEquals(1, callback.sourceSwitchingCount);
    }

    // ============================================================
    // Channel switch: stale error callback should be ignored
    // ============================================================

    @Test
    public void staleErrorCallbackIgnoredAfterChannelSwitch() throws Exception {
        initializePlayerViaReflection();

        playerManager.playChannel(Collections.singletonList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0)
        ));

        List<ChannelSource> channelB = Arrays.asList(
                new ChannelSource(2, "http://b.com/1.m3u8", 0),
                new ChannelSource(2, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(channelB);
        callback.reset();

        PlaybackException staleError = new PlaybackException(
                "Stale error from channel A",
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        );
        getPlayerListener().onPlayerError(staleError);

        assertEquals(0, playerManager.getCurrentSourceIndex());
        assertEquals(2, playerManager.getSourceCount());
        assertFalse(callback.allSourcesFailed);
        assertEquals(0, callback.sourceSwitchingCount);
    }

    @Test
    public void staleReadyCallbackIgnoredAfterChannelSwitch() throws Exception {
        initializePlayerViaReflection();

        playerManager.playChannel(Collections.singletonList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0)
        ));

        playerManager.playChannel(Arrays.asList(
                new ChannelSource(2, "http://b.com/1.m3u8", 0),
                new ChannelSource(2, "http://b.com/2.m3u8", 1)
        ));
        callback.reset();

        getPlayerListener().onPlaybackStateChanged(Player.STATE_READY);

        assertEquals(0, callback.playbackStartedCount);
    }

    // ============================================================
    // Channel switch after all sources failed
    // ============================================================

    @Test
    public void switchChannelAfterAllSourcesFailedWorks() throws Exception {
        initializePlayerViaReflection();

        playerManager.playChannel(Collections.singletonList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0)
        ));

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);
        assertTrue(callback.allSourcesFailed);
        callback.reset();

        List<ChannelSource> channelB = Arrays.asList(
                new ChannelSource(2, "http://b.com/1.m3u8", 0),
                new ChannelSource(2, "http://b.com/2.m3u8", 1)
        );
        playerManager.playChannel(channelB);

        assertEquals(0, playerManager.getCurrentSourceIndex());
        assertEquals(2, playerManager.getSourceCount());
        assertFalse(callback.allSourcesFailed);
    }

    @Test
    public void noStaleTimeoutAfterAllSourcesFailedAndSwitch() throws Exception {
        initializePlayerViaReflection();

        playerManager.playChannel(Arrays.asList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0),
                new ChannelSource(1, "http://a.com/2.m3u8", 1)
        ));

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);
        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);
        assertTrue(callback.allSourcesFailed);
        callback.reset();

        playerManager.playChannel(Collections.singletonList(
                new ChannelSource(2, "http://b.com/1.m3u8", 0)
        ));

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);
        assertEquals(1, callback.allSourcesFailedCount);
    }

    // ============================================================
    // Empty source list should cancel old timeout
    // ============================================================

    @Test
    public void playChannelWithEmptySourcesCancelsOldTimeout() throws Exception {
        initializePlayerViaReflection();

        playerManager.playChannel(Collections.singletonList(
                new ChannelSource(1, "http://a.com/1.m3u8", 0)
        ));

        playerManager.playChannel(new ArrayList<>());
        assertTrue(callback.allSourcesFailed);
        assertEquals(1, callback.allSourcesFailedCount);

        ShadowLooper.idleMainLooper(15_000, TimeUnit.MILLISECONDS);

        assertEquals(1, callback.allSourcesFailedCount);
    }
}
