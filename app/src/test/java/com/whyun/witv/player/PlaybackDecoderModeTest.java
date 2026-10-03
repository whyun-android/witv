package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.media3.exoplayer.DefaultRenderersFactory;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class PlaybackDecoderModeTest {

    @Test
    public void defaultIsHardwareFirstWithSoftwareFallback() {
        assertEquals(PlaybackDecoderMode.AUTO, PlaybackDecoderMode.DEFAULT);
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                PlaybackDecoderMode.AUTO.getExtensionRendererMode());
    }

    @Test
    public void mapsToMedia3ExtensionRendererModes() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                PlaybackDecoderMode.PREFER_SOFTWARE.getExtensionRendererMode());
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                PlaybackDecoderMode.HARDWARE_ONLY.getExtensionRendererMode());
    }

    @Test
    public void idsAreUniqueAndStable() {
        Set<String> ids = new HashSet<>();
        for (PlaybackDecoderMode mode : PlaybackDecoderMode.values()) {
            assertNotNull(mode.getId());
            ids.add(mode.getId());
        }
        assertEquals(PlaybackDecoderMode.values().length, ids.size());
        // 这三个 id 是持久化值，改动会让已有用户的设置静默回落到默认
        assertEquals("auto", PlaybackDecoderMode.AUTO.getId());
        assertEquals("prefer_software", PlaybackDecoderMode.PREFER_SOFTWARE.getId());
        assertEquals("hardware_only", PlaybackDecoderMode.HARDWARE_ONLY.getId());
    }

    @Test
    public void fromIdRoundTrips() {
        for (PlaybackDecoderMode mode : PlaybackDecoderMode.values()) {
            assertEquals(mode, PlaybackDecoderMode.fromId(mode.getId()));
        }
    }

    @Test
    public void fromIdFallsBackToDefaultForUnknownInput() {
        assertEquals(PlaybackDecoderMode.DEFAULT, PlaybackDecoderMode.fromId(null));
        assertEquals(PlaybackDecoderMode.DEFAULT, PlaybackDecoderMode.fromId(""));
        assertEquals(PlaybackDecoderMode.DEFAULT, PlaybackDecoderMode.fromId("软解"));
        assertEquals(PlaybackDecoderMode.DEFAULT, PlaybackDecoderMode.fromId("AUTO"));
    }
}
