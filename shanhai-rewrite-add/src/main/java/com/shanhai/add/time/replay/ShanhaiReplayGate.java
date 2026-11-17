package com.shanhai.add.time.replay;

import java.util.HashMap;
import java.util.Map;

import com.shanhai.add.time.ShanhaiClockMode;

/**
 * 山海重构 · <b>倒流状态机 + 世界写入闸门 + 回放专用写后门</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么倒流的"闸门"要独立成一个纯逻辑类</h2>
 * 倒流期间世界必须同时满足四件事，而它们分散在 11 处判据里（mixin 硬网 / 事件层 / 客户端钉子）：
 * <table border="1">
 *   <caption>三个模式 × 四个投影（自检逐格断言这张表）</caption>
 *   <tr><th>mode</th><th>世界 tick 冻结</th><th>允许写盘</th><th>拒绝玩家改世界</th><th>回放自己的写</th></tr>
 *   <tr><td>LIVE</td><td>否</td><td>是</td><td>否</td><td>不需要</td></tr>
 *   <tr><td>PAUSED</td><td><b>是</b></td><td><b>是</b></td><td><b>是</b></td><td>不需要</td></tr>
 *   <tr><td>REPLAY</td><td><b>是</b></td><td><b>否</b>（noSave）</td><td><b>是</b></td><td><b>必须放行</b></td></tr>
 * </table>
 * 把它剥成纯 Java 之后，离线自检可以在<b>不开游戏</b>的情况下把这张表逐格断言一遍
 * （设计稿 §附录A 漏掉的 5 处 + 原 7 处判据，全部收敛到 {@link #frozen} / {@link #deniesWorldWrite}
 * 两个函数上，极性只有一份）。
 *
 * <h2>2. 🔴 回放专用写后门（{@code beginReplayWrite/endReplayWrite}）</h2>
 * 硬网 {@code ShanhaiLevelSetBlockClockMixin} 挂在 {@code Level#setBlock} 上，
 * 而我们自己回退方块<b>也走同一条路</b> ⇒ 没有后门的话倒流一行都写不进去
 * （设计 §4.1 那张表里唯一标 ❗ 的那条）。
 *
 * <p>实现是 <b>{@link ThreadLocal} 可重入计数</b>而不是一个 boolean：
 * <ul>
 *   <li>倒流只在<b>服务端主线程</b>发生；用 ThreadLocal ⇒ 别的线程（区块 IO/网络）永远看不到这个豁免；</li>
 *   <li>计数而不是 boolean ⇒ 回放嵌套调用（例如 BE 的 {@code load} 里又写了一次）不会把标记提前清掉。</li>
 * </ul>
 *
 * <h2>3. 转移表</h2>
 * <pre>
 *   LIVE → PAUSED ✅   LIVE → REPLAY ✅   LIVE → LIVE ✗（幂等，不 bump epoch）
 *   PAUSED → LIVE ✅   PAUSED → REPLAY ✅ PAUSED → PAUSED ✗
 *   REPLAY → LIVE ✅   REPLAY → PAUSED ✅ REPLAY → REPLAY ✗（已被 {@code start()} 拦下）
 * </pre>
 * 注意 REPLAY→PAUSED 是"允许但默认不走"：收尾一律回 LIVE（设计 §10-③ 推荐 A，倒流是个效果不是另一个暂停）。
 */
public final class ShanhaiReplayGate {

    /**
     * 回放写入的线程本地可重入计数。
     *
     * <p>{@code > 0} ⇒ 当前线程正在替<b>倒流自己</b>写世界 ⇒ 硬网必须放行。
     */
    private static final ThreadLocal<int[]> WRITE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    // ------------------------------------------------------------------ 三态投影

    /** 世界是否应当停住（= 原来那 11 处判据的唯一真值来源）。 */
    public static boolean frozen(ShanhaiClockMode mode) {
        return mode == ShanhaiClockMode.PAUSED || mode == ShanhaiClockMode.REPLAY;
    }

    /**
     * 存档的那个"闸门"是否打开。
     *
     * <p>🔴 只有 REPLAY 关闭：倒流期间磁盘必须停在"倒流前"那一次 save
     * （{@code MinecraftServer#saveChunks} 判的就是 {@code ServerLevel#noSave}），
     * 这样<b>崩整个 JVM 也不会留半截存档</b>（设计 §8.1）。
     */
    public static boolean savingAllowed(ShanhaiClockMode mode) {
        return mode != ShanhaiClockMode.REPLAY;
    }

    /** 是否拒绝玩家的世界写（挖/放/丢/容器/爆炸/指令）—— 与原版暂停口径一致，REPLAY 同样拒。 */
    public static boolean deniesPlayerWorldWrite(ShanhaiClockMode mode) {
        return frozen(mode);
    }

    /**
     * 硬网 {@code Level#setBlock} 是否拒绝这一笔。
     *
     * @param mode       当前时钟模式
     * @param clientSide 这一笔是不是发生在客户端 {@code ClientLevel}（表现写一律放行，见
     *                   {@code ShanhaiClockRules#denyWorldWrite}）
     */
    public static boolean deniesWorldWrite(ShanhaiClockMode mode, boolean clientSide) {
        if (!frozen(mode)) {
            return false;
        }
        if (clientSide) {
            return false;
        }
        return !inReplayWrite();
    }

    /**
     * 模式转移是否允许（自转移一律不允许 —— 那正是 {@code ShanhaiWorldClock#setMode} 的幂等口径）。
     *
     * @return {@code false} = 这次切换是"幂等请求"，不该 bump epoch
     */
    public static boolean transitionAllowed(ShanhaiClockMode from, ShanhaiClockMode to) {
        if (from == null || to == null) {
            return false;
        }
        return from != to;
    }

    // ------------------------------------------------------------------ 回放写入后门

    /** 进入一次"替倒流自己写世界"的临界区（可重入）。 */
    public static void beginReplayWrite() {
        ++WRITE_DEPTH.get()[0];
    }

    /** 退出一次临界区（与 {@link #beginReplayWrite()} 成对；下溢不会变负）。 */
    public static void endReplayWrite() {
        final int[] depth = WRITE_DEPTH.get();
        if (depth[0] > 0) {
            --depth[0];
        }
    }

    /** 当前线程是否处在回放写临界区里。 */
    public static boolean inReplayWrite() {
        return WRITE_DEPTH.get()[0] > 0;
    }

    /** 当前线程的临界区嵌套深度（只给 status/日志判读用）。 */
    public static int replayWriteDepth() {
        return WRITE_DEPTH.get()[0];
    }

    /** 换世界/停机时把当前线程的标记清干净（防止异常路径漏掉 {@code endReplayWrite}）。 */
    public static void resetReplayWrite() {
        WRITE_DEPTH.get()[0] = 0;
    }

    // ------------------------------------------------------------------ 判读

    /** 一行摘要（status 用）。 */
    public static String describe(ShanhaiClockMode mode) {
        return "frozen=" + frozen(mode)
                + " save=" + (savingAllowed(mode) ? "on" : "off")
                + " denyWrite=" + deniesPlayerWorldWrite(mode)
                + " replayWriteDepth=" + replayWriteDepth();
    }

    /**
     * 三态投影表（离线自检与 status 共用一份，避免"文档写一套、代码跑另一套"）。
     *
     * @return 行文本，例：{@code LIVE frozen=false save=on denyWrite=false}
     */
    public static Map<String, String> table() {
        final Map<String, String> rows = new HashMap<>();
        for (ShanhaiClockMode m : ShanhaiClockMode.values()) {
            rows.put(m.name(), "frozen=" + frozen(m)
                    + " save=" + (savingAllowed(m) ? "on" : "off")
                    + " denyWrite=" + deniesPlayerWorldWrite(m));
        }
        return rows;
    }

    private ShanhaiReplayGate() {
    }
}
