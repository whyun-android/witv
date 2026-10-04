package com.whyun.witv.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 解析本机用于展示的局域网地址，也就是用户要在浏览器里输入的那个 IP。
 *
 * <p>原先只读 {@code WifiManager.getConnectionInfo().getIpAddress()}，而电视盒子接网线是常态，
 * 有线连接时该接口恒返回 0，界面上就只剩 {@code 0.0.0.0:9978} 这种没法用的地址。
 */
public final class DeviceIpUtil {

    /** 解析不出任何可用地址时的占位值 */
    public static final String UNKNOWN_ADDRESS = "0.0.0.0";

    private DeviceIpUtil() {
    }

    /**
     * 当前设备的局域网 IPv4 地址。优先取系统认定的活动网络，取不到再枚举网卡。
     *
     * @return 形如 {@code 192.168.6.133}；解析失败返回 {@link #UNKNOWN_ADDRESS}
     */
    @NonNull
    public static String resolve(@Nullable Context context) {
        String fromActiveNetwork = fromActiveNetwork(context);
        if (fromActiveNetwork != null) {
            return fromActiveNetwork;
        }
        return pickDisplayAddress(enumerateCandidates());
    }

    /** 系统认定的活动网络地址最准：有线/无线同时在线时它就是实际出口。 */
    @Nullable
    private static String fromActiveNetwork(@Nullable Context context) {
        if (context == null) {
            return null;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return null;
            }
            LinkProperties props = cm.getLinkProperties(active);
            if (props == null) {
                return null;
            }
            for (LinkAddress linkAddress : props.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (isUsableIpv4(address)) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // 权限、厂商 ROM 异常等一律退回枚举方案
        }
        return null;
    }

    private static List<Candidate> enumerateCandidates() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (isUsableIpv4(address)) {
                        candidates.add(new Candidate(ni.getName(), address.getHostAddress()));
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return candidates;
    }

    private static boolean isUsableIpv4(@Nullable InetAddress address) {
        return address instanceof Inet4Address
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress();
    }

    /**
     * 从候选网卡里挑一个最适合展示给用户的地址。
     *
     * <p>有线优先于无线，是因为盒子两者同时在线时有线通常才是实际可达的那条；
     * {@code p2p} / {@code dummy} / {@code tun} 之类是虚拟或点对点接口，写到界面上用户连不上。
     */
    @NonNull
    @VisibleForTesting
    static String pickDisplayAddress(@Nullable List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return UNKNOWN_ADDRESS;
        }
        Candidate best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            int rank = rankOf(candidate.interfaceName);
            if (rank < bestRank) {
                bestRank = rank;
                best = candidate;
            }
        }
        return best != null ? best.address : UNKNOWN_ADDRESS;
    }

    /** 数值越小越优先；{@link Integer#MAX_VALUE} 表示不可用。 */
    private static int rankOf(@Nullable String interfaceName) {
        if (interfaceName == null) {
            return 3;
        }
        String name = interfaceName.toLowerCase(java.util.Locale.US);
        if (name.startsWith("lo") || name.startsWith("p2p") || name.startsWith("dummy")
                || name.startsWith("tun") || name.startsWith("docker") || name.startsWith("veth")) {
            return Integer.MAX_VALUE;
        }
        if (name.startsWith("eth")) {
            return 0;
        }
        if (name.startsWith("wlan")) {
            return 1;
        }
        return 2;
    }

    /** 一张网卡上的一个可用 IPv4 地址 */
    @VisibleForTesting
    static final class Candidate {
        final String interfaceName;
        final String address;

        Candidate(String interfaceName, String address) {
            this.interfaceName = interfaceName;
            this.address = address;
        }
    }

    /** 仅供测试构造候选列表 */
    @VisibleForTesting
    static List<Candidate> candidates(String... interfaceAndAddressPairs) {
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i + 1 < interfaceAndAddressPairs.length; i += 2) {
            list.add(new Candidate(interfaceAndAddressPairs[i], interfaceAndAddressPairs[i + 1]));
        }
        return Collections.unmodifiableList(list);
    }
}
