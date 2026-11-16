package com.dishanhai.gt_shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.List;

/**
 * 「<b>清空一批样板总成</b>」这件事的纯核（不 import 任何 MC/GT 类 ⇒ 裸 JVM 可跑断言）。
 *
 * <h2>一、两个入口共用它（用户口径）</h2>
 * <pre>
 *   ① 面板里「清空物品内的 N 块样板」      ⇒ 走 {@code HostPatternMoverActions#clearItem}（只碰物品 NBT，不经过本类）
 *   ② 右键主机 ⇒ 面板里「清空这台主机及其模块的全部样板」 ⇒ <b>本类</b>
 *      先 {@link #plan}（只读，给二次确认页显示"几个总成 / 共几块"）
 *      再 {@link #run}（真清；逐槽置空，上游内部自己会通知 AE）
 * </pre>
 *
 * <h2>二、🔴 二次确认为什么是"结构性"的</h2>
 * {@link #plan} 一个字节都不写（只有 {@code occupied} 读数），所以「取消」这条路走完，
 * 机器指纹必然逐字节不变；真清只发生在 {@link #run}。
 * 自检（第 21 组）就是拿这个当判据，并配一条"真清 ⇒ 指纹必须变"的负对照。
 *
 * <h2>三、清不了的那几格怎么办</h2>
 * 逐格 {@code try { clear(slot) }}：单格失败只影响那一格，计入 {@link Result#failed()} 并继续；
 * 「已清空」只报<b>真的清掉</b>的块数（不虚报）。
 */
public final class HostPatternMoverClear {

    /** 一台总成的<b>可清</b>槽视图（生产实现包 {@code InternalInventory}；断言实现是假容器）。 */
    public interface Slots {

        /** 日志用的标号（坐标）。 */
        String label();

        /** 槽数。 */
        int size();

        /**
         * 这一格有没有东西。
         *
         * <p>⚠️ 读崩 ⇒ 按<b>有东西</b>处理吗？不 —— 按<b>没有</b>处理（{@link #plan} 只用于显示与预估，
         * 真实的块数由 {@link #run} 逐格现读；两者不一致时以 {@link #run} 为准）。
         */
        boolean occupied(int slot);

        /** 把这一格置空；成功 ⇒ true。 */
        boolean clear(int slot);
    }

    /**
     * 一次"只算不写"的预估（二次确认页显示的就是它）。
     *
     * @param assemblies 收集到几台样板总成
     * @param slots      这些总成的槽数总和
     * @param occupied   其中<b>有样板</b>的格数（= 将清空的块数）
     */
    public record Plan(int assemblies, int slots, int occupied) {

        public static final Plan EMPTY = new Plan(0, 0, 0);

        public String describe() {
            return "assemblies=" + this.assemblies + " slots=" + this.slots + " occupied=" + this.occupied;
        }
    }

    /**
     * 一次真清的账。
     *
     * @param assemblies 扫了几台总成
     * @param occupied   清之前<b>有样板</b>的格数
     * @param cleared    真的清掉的块数
     * @param failed     读了/写了但没清掉的格数（不静默）
     */
    public record Result(int assemblies, int occupied, int cleared, int failed) {

        public static final Result EMPTY = new Result(0, 0, 0, 0);

        /** 一块都没漏（清之前有的 = 清掉的）。 */
        public boolean complete() {
            return this.cleared == this.occupied && this.failed == 0;
        }

        public String describe() {
            return "assemblies=" + this.assemblies + " occupied=" + this.occupied
                    + " cleared=" + this.cleared + " failed=" + this.failed;
        }
    }

    private HostPatternMoverClear() {}

    /** 只算不写：收集到几台总成、共几格有样板。 */
    public static Plan plan(List<? extends Slots> targets) {
        if (targets == null) {
            return Plan.EMPTY;
        }
        int assemblies = 0;
        int slots = 0;
        int occupied = 0;
        for (Slots t : targets) {
            if (t == null) {
                continue;
            }
            assemblies++;
            final int size;
            try {
                size = Math.max(0, t.size());
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < size; i++) {
                slots++;
                try {
                    if (t.occupied(i)) {
                        occupied++;
                    }
                } catch (Throwable ignored) {
                    // 读崩 ⇒ 这一格不计入预估（真清时会再试一次，结果记在 failed 里）
                }
            }
        }
        return new Plan(assemblies, slots, occupied);
    }

    /**
     * 真清：逐槽置空。
     *
     * <p>🔴 清之前先<b>现读</b>这一格（{@link Slots#occupied}）—— 空槽不写（写了就是无意义的
     * {@code onPatternChange}，会白触发一次 AE 重推）。
     */
    public static Result run(List<? extends Slots> targets) {
        if (targets == null) {
            return Result.EMPTY;
        }
        int assemblies = 0;
        int occupied = 0;
        int cleared = 0;
        int failed = 0;
        for (Slots t : targets) {
            if (t == null) {
                continue;
            }
            assemblies++;
            final int size;
            try {
                size = Math.max(0, t.size());
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < size; i++) {
                final boolean has;
                try {
                    has = t.occupied(i);
                } catch (Throwable ex) {
                    failed++;
                    continue;
                }
                if (!has) {
                    continue;
                }
                occupied++;
                try {
                    if (t.clear(i)) {
                        cleared++;
                    } else {
                        failed++;
                    }
                } catch (Throwable ex) {
                    failed++;
                }
            }
        }
        return new Result(assemblies, occupied, cleared, failed);
    }

    /**
     * 机器指纹：每格一个字符（{@code -} 空 / {@code #} 有）＋ 非空格数。
     *
     * <p>「取消 ⇒ 一个字节都不写」这条判据就是拿它前后各取一次比。
     */
    public static String fingerprint(List<? extends Slots> targets) {
        if (targets == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(128);
        int nonEmpty = 0;
        for (Slots t : targets) {
            if (t == null) {
                continue;
            }
            final int size;
            try {
                size = Math.max(0, t.size());
            } catch (Throwable ignored) {
                continue;
            }
            sb.append('[');
            for (int i = 0; i < size; i++) {
                boolean has = false;
                try {
                    has = t.occupied(i);
                } catch (Throwable ignored) {
                    has = false;
                }
                sb.append(has ? '#' : '-');
                if (has) {
                    nonEmpty++;
                }
            }
            sb.append(']');
        }
        return sb + "|nonEmpty=" + nonEmpty;
    }

    /** 把一批目标的总块数算出来（面板/日志用；= {@link #plan} 的 {@code occupied}）。 */
    public static int occupied(List<? extends Slots> targets) {
        return plan(targets).occupied();
    }

    /** 空的实现（拿不到任何总成时用，避免 {@code null} 满天飞）。 */
    public static List<Slots> none() {
        return new ArrayList<>();
    }
}
