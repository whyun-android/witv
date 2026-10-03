package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MulticastUrlUtilTest {

    // ============================================================
    // schemeOf / isMulticastStreamUrl
    // ============================================================

    @Test
    public void schemeOfLowercasesAndTrims() {
        assertEquals("udp", MulticastUrlUtil.schemeOf("  UDP://239.1.1.1:1234 "));
        assertEquals("rtp", MulticastUrlUtil.schemeOf("Rtp://239.1.1.1:1234"));
        assertEquals("http", MulticastUrlUtil.schemeOf("http://a.com/x.m3u8"));
    }

    @Test
    public void schemeOfReturnsEmptyWithoutSeparator() {
        assertEquals("", MulticastUrlUtil.schemeOf("239.1.1.1:1234"));
        assertEquals("", MulticastUrlUtil.schemeOf(""));
        assertEquals("", MulticastUrlUtil.schemeOf(null));
    }

    @Test
    public void isMulticastStreamUrlOnlyForUdpAndRtp() {
        assertTrue(MulticastUrlUtil.isMulticastStreamUrl("udp://239.1.1.1:1234"));
        assertTrue(MulticastUrlUtil.isMulticastStreamUrl("rtp://239.1.1.1:1234"));
        assertTrue(MulticastUrlUtil.isMulticastStreamUrl("RTP://239.1.1.1:1234"));
        assertFalse(MulticastUrlUtil.isMulticastStreamUrl("http://a.com/live.m3u8"));
        assertFalse(MulticastUrlUtil.isMulticastStreamUrl("rtsp://a.com/live"));
        assertFalse(MulticastUrlUtil.isMulticastStreamUrl(null));
    }

    // ============================================================
    // normalize
    // ============================================================

    @Test
    public void normalizeStripsVlcStyleAt() {
        assertEquals("udp://239.1.1.1:1234",
                MulticastUrlUtil.normalize("udp://@239.1.1.1:1234"));
    }

    @Test
    public void normalizeStripsDoubleAt() {
        assertEquals("udp://239.1.1.1:1234",
                MulticastUrlUtil.normalize("udp://@@239.1.1.1:1234"));
    }

    @Test
    public void normalizeStripsSsmSourceAddress() {
        assertEquals("rtp://239.1.1.1:1234",
                MulticastUrlUtil.normalize("rtp://@192.168.1.1@239.1.1.1:1234"));
    }

    @Test
    public void normalizeLowercasesSchemeAndTrims() {
        assertEquals("rtp://239.1.1.1:1234",
                MulticastUrlUtil.normalize("  RTP://239.1.1.1:1234  "));
    }

    @Test
    public void normalizeLeavesNonMulticastUrlsAlone() {
        assertEquals("http://a.com/Live.m3u8?token=A@B",
                MulticastUrlUtil.normalize("http://a.com/Live.m3u8?token=A@B"));
        assertEquals("", MulticastUrlUtil.normalize(null));
    }

    @Test
    public void normalizeKeepsOriginalWhenNothingLeftAfterAt() {
        assertEquals("udp://@", MulticastUrlUtil.normalize("udp://@"));
    }

    // ============================================================
    // normalizeProxyBase
    // ============================================================

    @Test
    public void normalizeProxyBaseAddsHttpScheme() {
        assertEquals("http://192.168.1.1:4022",
                MulticastUrlUtil.normalizeProxyBase("192.168.1.1:4022"));
    }

    @Test
    public void normalizeProxyBaseStripsTrailingSlash() {
        assertEquals("http://192.168.1.1:4022",
                MulticastUrlUtil.normalizeProxyBase("http://192.168.1.1:4022///"));
    }

    @Test
    public void normalizeProxyBaseStripsPastedUdpOrRtpSuffix() {
        assertEquals("http://192.168.1.1:4022",
                MulticastUrlUtil.normalizeProxyBase("http://192.168.1.1:4022/rtp/"));
        assertEquals("http://192.168.1.1:4022",
                MulticastUrlUtil.normalizeProxyBase("http://192.168.1.1:4022/udp"));
    }

    @Test
    public void normalizeProxyBaseTreatsBlankAsUnset() {
        assertEquals("", MulticastUrlUtil.normalizeProxyBase(null));
        assertEquals("", MulticastUrlUtil.normalizeProxyBase("   "));
        assertEquals("", MulticastUrlUtil.normalizeProxyBase("http://"));
    }

    // ============================================================
    // toProxyUrl / resolvePlaybackUrl
    // ============================================================

    @Test
    public void toProxyUrlBuildsUdpxyPath() {
        assertEquals("http://192.168.1.1:4022/rtp/239.1.1.1:1234",
                MulticastUrlUtil.toProxyUrl("rtp://239.1.1.1:1234", "http://192.168.1.1:4022"));
        assertEquals("http://192.168.1.1:4022/udp/239.1.1.1:1234",
                MulticastUrlUtil.toProxyUrl("udp://239.1.1.1:1234", "http://192.168.1.1:4022"));
    }

    @Test
    public void toProxyUrlDropsTrailingPathAndQuery() {
        assertEquals("http://10.0.0.1:4022/rtp/239.1.1.1:1234",
                MulticastUrlUtil.toProxyUrl("rtp://239.1.1.1:1234/extra?x=1", "10.0.0.1:4022"));
    }

    @Test
    public void toProxyUrlReturnsInputWhenProxyUnset() {
        assertEquals("rtp://239.1.1.1:1234",
                MulticastUrlUtil.toProxyUrl("rtp://239.1.1.1:1234", ""));
        assertEquals("rtp://239.1.1.1:1234",
                MulticastUrlUtil.toProxyUrl("rtp://239.1.1.1:1234", null));
    }

    @Test
    public void toProxyUrlLeavesNonMulticastAlone() {
        assertEquals("http://a.com/live.m3u8",
                MulticastUrlUtil.toProxyUrl("http://a.com/live.m3u8", "http://10.0.0.1:4022"));
    }

    @Test
    public void resolvePlaybackUrlNormalizesThenProxies() {
        assertEquals("http://10.0.0.1:4022/rtp/239.1.1.1:1234",
                MulticastUrlUtil.resolvePlaybackUrl("rtp://@@239.1.1.1:1234", "10.0.0.1:4022/rtp"));
    }

    @Test
    public void resolvePlaybackUrlNormalizesOnlyWhenProxyUnset() {
        assertEquals("udp://239.1.1.1:1234",
                MulticastUrlUtil.resolvePlaybackUrl("udp://@239.1.1.1:1234", ""));
    }

    @Test
    public void resolvePlaybackUrlLeavesHttpSourcesUntouched() {
        assertEquals("http://a.com/live.m3u8",
                MulticastUrlUtil.resolvePlaybackUrl("http://a.com/live.m3u8", "10.0.0.1:4022"));
    }
}
