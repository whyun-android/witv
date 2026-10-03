package com.whyun.witv.player;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * WiFi 组播锁的引用计数持有者。
 *
 * <p>Android 的 WiFi 驱动默认会在网卡层丢掉目的 MAC 不是本机的组播帧，不持有
 * {@link WifiManager.MulticastLock} 时 {@code udp://}/{@code rtp://} 组播一个包都收不到。
 * 组播锁很耗电，所以只在真正收组播时持有，停止播放立即释放。
 */
public final class MulticastLockHolder {

    private static final String TAG = "MulticastLock";
    private static final String LOCK_TAG = "witv-multicast";

    private final Context appContext;

    @Nullable
    private WifiManager.MulticastLock lock;
    private int refCount;

    public MulticastLockHolder(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 获取组播锁；可重入，与 {@link #release()} 成对调用。 */
    public synchronized void acquire() {
        refCount++;
        if (refCount > 1) {
            return;
        }
        try {
            WifiManager wifiManager =
                    (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                Log.w(TAG, "WifiManager unavailable; multicast may be filtered by the driver");
                return;
            }
            WifiManager.MulticastLock created = wifiManager.createMulticastLock(LOCK_TAG);
            created.setReferenceCounted(false);
            created.acquire();
            lock = created;
            Log.i(TAG, "Multicast lock acquired");
        } catch (Exception e) {
            // 缺少 CHANGE_WIFI_MULTICAST_STATE 或厂商 ROM 异常时不应中断播放：
            // 有线网络本来就不需要组播锁。
            Log.w(TAG, "Failed to acquire multicast lock: " + e.getMessage());
        }
    }

    /** 释放一次引用，计数归零时真正释放锁。 */
    public synchronized void release() {
        if (refCount == 0) {
            return;
        }
        refCount--;
        if (refCount > 0) {
            return;
        }
        if (lock != null) {
            try {
                if (lock.isHeld()) {
                    lock.release();
                }
                Log.i(TAG, "Multicast lock released");
            } catch (Exception e) {
                Log.w(TAG, "Failed to release multicast lock: " + e.getMessage());
            }
            lock = null;
        }
    }

    /** 仅供测试/诊断：当前是否真正持有锁 */
    public synchronized boolean isHeld() {
        return lock != null && lock.isHeld();
    }
}
