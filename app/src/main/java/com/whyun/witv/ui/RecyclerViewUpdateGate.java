package com.whyun.witv.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 把「在 RecyclerView 布局/滚动计算期间执行会抛异常」的更新推迟到下一帧。
 *
 * <p>{@code setAdapter()} 与 {@code notify*()} 在 RecyclerView 正在布局或滚动时调用都是非法的。
 * 电视端很容易踩到这个雷，因为焦点变化会在回收过程中同步回调：
 *
 * <ol>
 *   <li>频道列表惯性滚动，回收掉当前带焦点的那一行；</li>
 *   <li>{@code ViewGroup.removeViewInternal} 发现焦点没了，调 {@code rootViewRequestFocus()}；</li>
 *   <li>焦点从根节点重新分发，落到分组列表的某一项上，同步触发它的
 *       {@code OnFocusChangeListener}；</li>
 *   <li>监听器里换频道列表的 Adapter —— 而第 1 步的回收还没结束，于是
 *       {@code IllegalStateException: Cannot call removeView(At) within removeView(At)}。</li>
 * </ol>
 */
final class RecyclerViewUpdateGate {

    private RecyclerViewUpdateGate() {
    }

    /** 给定的 RecyclerView 中是否有正在布局或滚动计算的（null 视为不忙）。 */
    static boolean isBusy(@Nullable RecyclerView... views) {
        if (views == null) {
            return false;
        }
        for (RecyclerView view : views) {
            if (view != null && view.isComputingLayout()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列表正忙则把 {@code retry} 推迟到下一帧，否则什么都不做。
     *
     * <p>注意本方法在「不忙」时**不会**执行 {@code retry}——{@code retry} 通常就是调用方自身，
     * 执行它会无限递归。正确用法是：
     *
     * <pre>
     * if (RecyclerViewUpdateGate.postponeIfBusy(host, () -&gt; doIt(arg), listA, listB)) {
     *     return;
     * }
     * // ...真正的更新逻辑
     * </pre>
     *
     * @param host    用于 {@code post} 的视图；为 null 时无法推迟，只能让调用方继续执行
     * @param retry   推迟后要重新执行的入口（会再走一遍本检查）
     * @param guarded 需要检查是否处于布局/滚动中的列表
     * @return true 表示已推迟，调用方应立即返回；false 表示当前可以安全更新
     */
    static boolean postponeIfBusy(@Nullable RecyclerView host,
                                  @NonNull Runnable retry,
                                  @Nullable RecyclerView... guarded) {
        if (host != null && isBusy(guarded)) {
            host.post(retry);
            return true;
        }
        return false;
    }
}
