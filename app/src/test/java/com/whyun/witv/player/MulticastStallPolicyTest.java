package com.whyun.witv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MulticastStallPolicyTest {

    @Test
    public void healthyStreamNeverRejoins() {
        MulticastStallPolicy policy = new MulticastStallPolicy(2);

        for (int i = 0; i < 1000; i++) {
            policy.onPacketReceived();
        }

        assertEquals(0, policy.getRejoinCount());
    }

    /** IGMP 成员被剪掉的场景：一次重入组就该恢复。 */
    @Test
    public void firstStallTriggersRejoin() {
        MulticastStallPolicy policy = new MulticastStallPolicy(2);

        assertTrue(policy.shouldRejoin());
        assertEquals(1, policy.getRejoinCount());
    }

    @Test
    public void recoveryResetsTheBudget() {
        MulticastStallPolicy policy = new MulticastStallPolicy(2);

        assertTrue(policy.shouldRejoin());
        assertTrue(policy.shouldRejoin());
        assertFalse(policy.shouldRejoin());

        // 流恢复后，下一次断流应当重新获得完整的重试预算
        policy.onPacketReceived();
        assertEquals(0, policy.getRejoinCount());
        assertTrue(policy.shouldRejoin());
        assertTrue(policy.shouldRejoin());
        assertFalse(policy.shouldRejoin());
    }

    /** 真死掉的流必须在有限次后放弃，否则频道会无限挂住而不换源。 */
    @Test
    public void deadStreamGivesUpAfterBudgetExhausted() {
        MulticastStallPolicy policy = new MulticastStallPolicy(2);

        assertTrue(policy.shouldRejoin());
        assertTrue(policy.shouldRejoin());
        for (int i = 0; i < 10; i++) {
            assertFalse(policy.shouldRejoin());
        }
        assertEquals(2, policy.getRejoinCount());
    }

    @Test
    public void zeroBudgetDisablesRejoin() {
        MulticastStallPolicy policy = new MulticastStallPolicy(0);

        assertFalse(policy.shouldRejoin());
        assertEquals(0, policy.getRejoinCount());
    }

    @Test
    public void negativeBudgetIsTreatedAsZero() {
        MulticastStallPolicy policy = new MulticastStallPolicy(-5);

        assertEquals(0, policy.getMaxRejoinAttempts());
        assertFalse(policy.shouldRejoin());
    }

    @Test
    public void resetClearsState() {
        MulticastStallPolicy policy = new MulticastStallPolicy(2);

        assertTrue(policy.shouldRejoin());
        assertTrue(policy.shouldRejoin());
        assertFalse(policy.shouldRejoin());

        policy.reset();

        assertEquals(0, policy.getRejoinCount());
        assertTrue(policy.shouldRejoin());
    }

    /**
     * 消费侧的等待必须长过收包线程的完整恢复过程，否则会在最后一次重新入组还没来得及收到包时
     * 就先行放弃，等于白配了重入组预算。
     */
    @Test
    public void consumerOutwaitsTheWholeRejoinBudget() {
        int timeout = MulticastDataSource.DEFAULT_SOCKET_TIMEOUT_MS;
        int attempts = MulticastStallPolicy.DEFAULT_MAX_REJOIN_ATTEMPTS;

        long readerGivesUpAtMs = (long) timeout * (attempts + 1);
        long consumerWaitMs = MulticastDataSource.consumerWaitMs(timeout, attempts);

        assertTrue("消费侧(" + consumerWaitMs + "ms) 必须长过收包线程放弃的时刻("
                        + readerGivesUpAtMs + "ms)",
                consumerWaitMs > readerGivesUpAtMs);
    }

    @Test
    public void consumerWaitHandlesZeroRejoinBudget() {
        // 不重入组时也至少要等满一个 socket 超时周期
        assertTrue(MulticastDataSource.consumerWaitMs(3_000, 0) > 3_000L);
        assertTrue(MulticastDataSource.consumerWaitMs(3_000, -1) > 3_000L);
    }

    @Test
    public void defaultBudgetCoversAboutNineSecondsAtThreeSecondTimeout() {
        // 3s 收包超时 × (1 次首发超时 + 2 次重入组) ≈ 9s 才判定断流，
        // 与默认 15s 的超时换源阈值量级相称
        assertEquals(2, MulticastStallPolicy.DEFAULT_MAX_REJOIN_ATTEMPTS);
        assertEquals(3_000, MulticastDataSource.DEFAULT_SOCKET_TIMEOUT_MS);
    }
}
