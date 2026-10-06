package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JEI 拖动的<b>可机器判读数</b>（本工程红线：不许只写"已修"）。
 *
 * <h2>为什么要单独一个类</h2>
 * 拖动的现场在客户端、格子落点在 LDLib / JEI 的私有实现里，本机<b>开不了客户端</b>
 * （红线）⇒ 唯一能拿到的证据就是"哪一步被调到了、调到了几次"。这四个计数器把
 * 「拖不动」这条链拆成四个可以独立判红的环节：
 * <pre>
 *   phantom_calls     JEI 来问"这个面板有没有落点"的次数（LDLib mainGroup 的入口）
 *   phantom_targets   从这些调用里真的产生的落点数（== 0 就说明没落点 ⇒ JEI 连拖都不开始）
 *   drop_accepts      JEI 真的把东西丢进来了几次（== 0 就说明落点没被命中）
 *   drop_rejects      丢进来了但这个格子不收（类型不对 / 当前不是编辑屏）
 * </pre>
 *
 * <h2>🔴 判据（2026-10-05 现场读数）</h2>
 * 用户实例 {@code logs\latest.log} 里：{@code io_drag_offer=0}、{@code io_offer_applied=0}，
 * 而同一次会话里 {@code io_clear_applied=1}（右键删格子是通的）。
 * ⇒ "拖不动 + 放进去没反应"这两条<b>同源</b>：落点从来就没建起来过。
 * 所以下面 {@link #phantomTargets} 只要恒为 0，就能在<b>没有客户端</b>的情况下断言这条链断了。
 *
 * <p>日志节流：{@code getPhantomTargets} 每帧都会被调，所以只在<b>计数发生变化</b>时打一行
 * （连同 {@link #PREFIX}），既不给日志增压，也不会把关键那一拍淹掉。
 */
public final class ShanhaiDragStats {

    public static final String PREFIX = "[SHANHAI-DRAG]";

    private static final AtomicInteger phantomCalls = new AtomicInteger();
    private static final AtomicInteger phantomTargets = new AtomicInteger();
    private static final AtomicInteger dropAccepts = new AtomicInteger();
    private static final AtomicInteger dropRejects = new AtomicInteger();

    private static final AtomicLong lastLogMs = new AtomicLong(0L);

    private ShanhaiDragStats() {}

    /** JEI 问了一次落点（{@code mainGroup.getPhantomTargets} 走到我们面板）。 */
    public static void phantomCall(int targets) {
        phantomCalls.incrementAndGet();
        if (targets > 0) {
            phantomTargets.addAndGet(targets);
        }
        logIfDue();
    }

    /** 落点真的被丢中了。 */
    public static void dropAccepted() {
        dropAccepts.incrementAndGet();
        logIfDue();
    }

    /** 丢进来了但没收（类型不符 / 当前屏不对）。<b>不许静默</b>：带上原因。 */
    public static void dropRejected(String reason) {
        dropRejects.incrementAndGet();
        ShanhaiMod.LOGGER.info("{} drop_rejected reason={} totals(calls={} targets={} accepts={} rejects={})",
                PREFIX, reason, phantomCalls.get(), phantomTargets.get(), dropAccepts.get(), dropRejects.get());
    }

    private static void logIfDue() {
        final long now = System.currentTimeMillis();
        final long last = lastLogMs.get();
        if (now - last < 1000L) {
            return;                       // 每秒最多一行（每帧调用的东西不能每帧打日志）
        }
        if (!lastLogMs.compareAndSet(last, now)) {
            return;
        }
        ShanhaiMod.LOGGER.info("{} drag_totals phantom_calls={} phantom_targets={} drop_accepts={} drop_rejects={} "
                        + "(judgement: phantom_targets=0 => JEI never starts a drag; drop_accepts=0 => drops never land)",
                PREFIX, phantomCalls.get(), phantomTargets.get(), dropAccepts.get(), dropRejects.get());
    }

    /** 一行读数（面板/命令/自检用）。 */
    public static String statsLine() {
        return "phantom_calls=" + phantomCalls.get() + " phantom_targets=" + phantomTargets.get()
                + " drop_accepts=" + dropAccepts.get() + " drop_rejects=" + dropRejects.get();
    }

    public static int phantomTargetTotal() {
        return phantomTargets.get();
    }

    public static int dropAcceptTotal() {
        return dropAccepts.get();
    }

    /** 面板打开时清零（否则计数会跨会话累加，"这次拖动到底有没有落点"就说不清了）。 */
    public static void resetForNewPanel() {
        phantomCalls.set(0);
        phantomTargets.set(0);
        dropAccepts.set(0);
        dropRejects.set(0);
        lastLogMs.set(0L);
    }
}
