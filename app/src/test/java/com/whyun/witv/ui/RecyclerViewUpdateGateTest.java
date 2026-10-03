package com.whyun.witv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class RecyclerViewUpdateGateTest {

    /** 可以把 isComputingLayout 掰成 true 的 RecyclerView，用来模拟布局/滚动进行中 */
    private static final class FakeRecyclerView extends RecyclerView {
        boolean computingLayout;

        FakeRecyclerView(Context context) {
            super(context);
        }

        @Override
        public boolean isComputingLayout() {
            return computingLayout;
        }
    }

    private FakeRecyclerView channelList;
    private FakeRecyclerView groupList;

    @Before
    public void setUp() {
        // View.post 在未附着到窗口时只会入 RunQueue 而不会执行，
        // 所以这里必须真的把视图挂进 Activity，否则测的就不是生产环境的行为。
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        channelList = new FakeRecyclerView(activity);
        groupList = new FakeRecyclerView(activity);
        root.addView(channelList);
        root.addView(groupList);
    }

    // ============================================================
    // isBusy
    // ============================================================

    @Test
    public void isBusyFalseWhenNothingIsLayingOut() {
        assertFalse(RecyclerViewUpdateGate.isBusy(channelList, groupList));
    }

    @Test
    public void isBusyTrueIfAnyViewIsLayingOut() {
        groupList.computingLayout = true;
        assertTrue(RecyclerViewUpdateGate.isBusy(channelList, groupList));

        groupList.computingLayout = false;
        channelList.computingLayout = true;
        assertTrue(RecyclerViewUpdateGate.isBusy(channelList, groupList));
    }

    @Test
    public void isBusyTolerantOfNulls() {
        assertFalse(RecyclerViewUpdateGate.isBusy((RecyclerView[]) null));
        assertFalse(RecyclerViewUpdateGate.isBusy(null, null));

        channelList.computingLayout = true;
        assertTrue(RecyclerViewUpdateGate.isBusy(null, channelList));
    }

    // ============================================================
    // postponeIfBusy
    // ============================================================

    /**
     * 关键约定：不忙时**不执行** retry，只返回 false 让调用方继续。
     * retry 通常就是调用方自身，执行它会无限递归。
     */
    @Test
    public void doesNotRunRetryWhenIdle() {
        AtomicInteger runs = new AtomicInteger();

        boolean postponed = RecyclerViewUpdateGate.postponeIfBusy(
                channelList, runs::incrementAndGet, channelList, groupList);

        assertFalse(postponed);
        assertEquals(0, runs.get());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(0, runs.get());
    }

    @Test
    public void postponesRetryToNextFrameWhenBusy() {
        channelList.computingLayout = true;
        AtomicInteger runs = new AtomicInteger();

        boolean postponed = RecyclerViewUpdateGate.postponeIfBusy(
                channelList, runs::incrementAndGet, channelList, groupList);

        assertTrue(postponed);
        // 必须是异步的——同步执行就等于没修
        assertEquals(0, runs.get());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(1, runs.get());
    }

    @Test
    public void postponesWhenOnlyTheOtherListIsBusy() {
        groupList.computingLayout = true;
        AtomicInteger runs = new AtomicInteger();

        assertTrue(RecyclerViewUpdateGate.postponeIfBusy(
                channelList, runs::incrementAndGet, channelList, groupList));
        assertEquals(0, runs.get());
    }

    /**
     * 模拟真实时序：布局进行中触发更新 → 推迟；下一帧布局已结束 → 重试时放行。
     */
    @Test
    public void retryProceedsOnceLayoutFinished() {
        channelList.computingLayout = true;
        AtomicInteger completed = new AtomicInteger();

        Runnable update = new Runnable() {
            @Override
            public void run() {
                if (RecyclerViewUpdateGate.postponeIfBusy(
                        channelList, this, channelList, groupList)) {
                    return;
                }
                completed.incrementAndGet();
            }
        };

        update.run();
        assertEquals(0, completed.get());

        // 布局结束后排到的那一帧才真正执行
        channelList.computingLayout = false;
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(1, completed.get());
    }

    @Test
    public void runsInlineWhenHostIsNullBecauseThereIsNowhereToPost() {
        channelList.computingLayout = true;
        AtomicInteger runs = new AtomicInteger();

        boolean postponed = RecyclerViewUpdateGate.postponeIfBusy(
                null, runs::incrementAndGet, channelList);

        assertFalse(postponed);
        assertEquals(0, runs.get());
    }
}
