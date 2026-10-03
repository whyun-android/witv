package com.whyun.witv.player;

/**
 * 组播断流时「重新入组几次才放弃」的策略。
 *
 * <p>组播转发依赖 IGMP 成员关系：上游路由器周期性发 General Query，主机要回 Membership Report，
 * 否则交换机的 IGMP snooping 会把这个组剪掉、停止转发。Query/Report 任一方向丢失
 * （Wi-Fi 省电、AP 不转发组播管理帧、虚拟网络），就表现为「播几分钟后卡住，重进频道立刻恢复」
 * ——重进之所以有效，正是因为它重新 join 了一次，等于补发了一个 Membership Report。
 *
 * <p>所以收包超时时先原地重新入组，而不是直接报错换源。健康的流永远不会触发（只有连续
 * 几秒一个包都没有才会），真正死掉的流则在有限次尝试后照常报错，不会无限挂住。
 */
final class MulticastStallPolicy {

    /** 默认重入组次数。配合 3 秒收包超时，约 9 秒内仍无数据才判定断流。 */
    static final int DEFAULT_MAX_REJOIN_ATTEMPTS = 2;

    private final int maxRejoinAttempts;
    private int consecutiveRejoins;

    MulticastStallPolicy(int maxRejoinAttempts) {
        this.maxRejoinAttempts = Math.max(0, maxRejoinAttempts);
    }

    /** 收到任意数据即视为已恢复，重置计数。 */
    void onPacketReceived() {
        consecutiveRejoins = 0;
    }

    /**
     * 收包超时时调用。
     *
     * @return true 表示应当重新入组后继续等待；false 表示已用尽尝试次数，调用方应报错
     */
    boolean shouldRejoin() {
        if (consecutiveRejoins >= maxRejoinAttempts) {
            return false;
        }
        consecutiveRejoins++;
        return true;
    }

    /** 自上次收到数据以来已经重新入组的次数。 */
    int getRejoinCount() {
        return consecutiveRejoins;
    }

    int getMaxRejoinAttempts() {
        return maxRejoinAttempts;
    }

    /** 重新打开数据源时清空状态。 */
    void reset() {
        consecutiveRejoins = 0;
    }
}
