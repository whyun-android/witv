package com.whyun.witv.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

public class DeviceIpUtilTest {

    /**
     * 这个工具存在的直接原因：盒子接网线时，原来只读 WifiManager 的实现拿不到地址，
     * 界面上显示成 0.0.0.0:9979，用户无法访问 Web 管理页。
     */
    @Test
    public void picksEthernetAddress() {
        assertEquals("192.168.6.133", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates("eth0", "192.168.6.133")));
    }

    /** 有线与无线同时在线时，有线通常才是实际可达的那条。 */
    @Test
    public void prefersEthernetOverWifi() {
        assertEquals("192.168.6.133", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates(
                        "wlan0", "192.168.6.200",
                        "eth0", "192.168.6.133")));
    }

    @Test
    public void fallsBackToWifiWhenNoEthernet() {
        assertEquals("192.168.6.200", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates("wlan0", "192.168.6.200")));
    }

    /** 虚拟/点对点接口写到界面上用户连不上，必须排除。 */
    @Test
    public void skipsVirtualInterfaces() {
        assertEquals("192.168.6.200", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates(
                        "p2p0", "192.168.49.1",
                        "dummy0", "10.0.0.1",
                        "tun0", "10.8.0.2",
                        "wlan0", "192.168.6.200")));
    }

    @Test
    public void usesUnknownInterfaceOnlyAsLastResort() {
        assertEquals("192.168.6.133", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates(
                        "usb0", "192.168.42.1",
                        "eth0", "192.168.6.133")));
        // 没有已知网卡时，仍然给出能用的那个，而不是退回占位符
        assertEquals("192.168.42.1", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates("usb0", "192.168.42.1")));
    }

    @Test
    public void returnsPlaceholderWhenNothingUsable() {
        assertEquals(DeviceIpUtil.UNKNOWN_ADDRESS,
                DeviceIpUtil.pickDisplayAddress(Collections.emptyList()));
        assertEquals(DeviceIpUtil.UNKNOWN_ADDRESS, DeviceIpUtil.pickDisplayAddress(null));
        assertEquals(DeviceIpUtil.UNKNOWN_ADDRESS, DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates("lo", "127.0.0.1", "p2p0", "192.168.49.1")));
    }

    @Test
    public void matchesInterfaceNamesCaseInsensitively() {
        assertEquals("192.168.6.133", DeviceIpUtil.pickDisplayAddress(
                DeviceIpUtil.candidates(
                        "WLAN0", "192.168.6.200",
                        "ETH0", "192.168.6.133")));
    }

    @Test
    public void toleratesNullInterfaceName() {
        assertEquals("192.168.6.133", DeviceIpUtil.pickDisplayAddress(
                new ArrayList<>(DeviceIpUtil.candidates("eth0", "192.168.6.133"))));
    }
}
