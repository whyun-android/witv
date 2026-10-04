package com.whyun.witv.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    /**
     * 开着 VPN 时 getActiveNetwork() 返回的是 VPN，它的 LinkProperties 给的是隧道地址。
     * 把 tun0 的地址写到界面上，局域网里的手机根本连不上，而物理网卡的地址其实还好好的。
     */
    @Test
    public void rejectsVpnAndOtherVirtualInterfacesAsEntryPoint() {
        assertFalse(DeviceIpUtil.isUsableInterface("tun0"));
        assertFalse(DeviceIpUtil.isUsableInterface("tap0"));
        assertFalse(DeviceIpUtil.isUsableInterface("ppp0"));
        assertFalse(DeviceIpUtil.isUsableInterface("p2p-wlan0-0"));
        assertTrue(DeviceIpUtil.isUsableInterface("eth0"));
        assertTrue(DeviceIpUtil.isUsableInterface("wlan0"));
        // 拿不到接口名时不武断排除，交给后面的地址判断
        assertTrue(DeviceIpUtil.isUsableInterface(null));
    }

    /** 界面据此决定还画不画二维码：把 0.0.0.0 编成码，扫出来是个连不上的地址。 */
    @Test
    public void reportsWhetherAddressWasActuallyResolved() {
        assertTrue(DeviceIpUtil.isResolved("192.168.6.133"));
        assertFalse(DeviceIpUtil.isResolved(DeviceIpUtil.UNKNOWN_ADDRESS));
        assertFalse(DeviceIpUtil.isResolved(null));
        assertFalse(DeviceIpUtil.isResolved("   "));
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
