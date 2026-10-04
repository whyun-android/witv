package com.whyun.witv.player;

import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class PlaybackDecoderModeTest {

    @Test
    public void defaultIsHardwareFirstForBothTracks() {
        assertEquals(PlaybackDecoderMode.AUTO, PlaybackDecoderMode.DEFAULT);
        assertEquals(EXTENSION_RENDERER_MODE_ON,
                PlaybackDecoderMode.AUTO.getAudioExtensionRendererMode());
        assertEquals(EXTENSION_RENDERER_MODE_ON,
                PlaybackDecoderMode.AUTO.getVideoExtensionRendererMode());
    }

    /**
     * 这一档存在的全部理由：音频必须绕开杜比直通走软解，视频必须留在硬解上
     * （老盒子软解 1080p 跟不上实时会持续掉帧）。两者取值相同就失去了意义。
     */
    @Test
    public void softwareAudioKeepsVideoOnHardware() {
        assertEquals(EXTENSION_RENDERER_MODE_PREFER,
                PlaybackDecoderMode.SOFTWARE_AUDIO.getAudioExtensionRendererMode());
        assertEquals(EXTENSION_RENDERER_MODE_ON,
                PlaybackDecoderMode.SOFTWARE_AUDIO.getVideoExtensionRendererMode());
    }

    @Test
    public void preferSoftwareAppliesToBothTracks() {
        assertEquals(EXTENSION_RENDERER_MODE_PREFER,
                PlaybackDecoderMode.PREFER_SOFTWARE.getAudioExtensionRendererMode());
        assertEquals(EXTENSION_RENDERER_MODE_PREFER,
                PlaybackDecoderMode.PREFER_SOFTWARE.getVideoExtensionRendererMode());
    }

    @Test
    public void hardwareOnlyDisablesExtensionRenderersEntirely() {
        assertEquals(EXTENSION_RENDERER_MODE_OFF,
                PlaybackDecoderMode.HARDWARE_ONLY.getAudioExtensionRendererMode());
        assertEquals(EXTENSION_RENDERER_MODE_OFF,
                PlaybackDecoderMode.HARDWARE_ONLY.getVideoExtensionRendererMode());
    }

    @Test
    public void idsAreUniqueAndStable() {
        Set<String> ids = new HashSet<>();
        for (PlaybackDecoderMode mode : PlaybackDecoderMode.values()) {
            assertNotNull(mode.getId());
            ids.add(mode.getId());
        }
        assertEquals(PlaybackDecoderMode.values().length, ids.size());
        // 这些 id 是持久化值，改动会让已有用户的设置静默回落到默认
        assertEquals("auto", PlaybackDecoderMode.AUTO.getId());
        assertEquals("software_audio", PlaybackDecoderMode.SOFTWARE_AUDIO.getId());
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

    /** 设置页按 values() 顺序展示，顺序应当由保守到激进。 */
    @Test
    public void orderGoesFromConservativeToAggressive() {
        PlaybackDecoderMode[] values = PlaybackDecoderMode.values();
        assertEquals(PlaybackDecoderMode.AUTO, values[0]);
        assertEquals(PlaybackDecoderMode.SOFTWARE_AUDIO, values[1]);
        assertEquals(PlaybackDecoderMode.PREFER_SOFTWARE, values[2]);
        assertEquals(PlaybackDecoderMode.HARDWARE_ONLY, values[3]);
    }
}
