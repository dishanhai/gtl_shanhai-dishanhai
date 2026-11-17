package com.shanhai.add.time.replay;

import java.util.Locale;

/**
 * 山海重构 · <b>倒流进度（百分比 / 进度条 / 动作栏文案）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>为什么单独一个类</h2>
 * 进度口径（"剩余百分比"按<b>实际窗口</b>算，不是按 1200 算）是用户能直接看到的东西，
 * 也是最容易算错的东西（除零、超界、窗口被 overflow 缩短）。
 * 剥成纯函数之后，离线自检能把边界逐条钉住（0% / 50% / 100% / 空窗口 / 负数 / 超界）。
 *
 * <p>显示形态：<b>动作栏</b>（{@code Player#displayClientMessage(Component, true)}），
 * 不新开 HUD、不加客户端渲染代码（设计 §9 的"零新增 GUI 验收"）。
 */
public final class ShanhaiReplayProgress {

    /** 进度条的格数（动作栏宽度有限，6 格够用）。 */
    public static final int BAR_CELLS = 6;

    private static final char BAR_FULL = '█';
    private static final char BAR_EMPTY = '░';

    /**
     * 已回退百分比。
     *
     * @param done  已经走掉的 tick 数
     * @param total 这次倒流的总 tick 数（{@code <= 0} ⇒ 视为 0%）
     * @return {@code 0..100} 的整数，<b>钳位</b>（超界输入不会抛、也不会出负号）
     */
    public static int percent(int done, int total) {
        if (total <= 0) {
            return 0;
        }
        if (done <= 0) {
            return 0;
        }
        if (done >= total) {
            return 100;
        }
        return (int) ((long) done * 100L / (long) total);
    }

    /** 百分比 → 进度条（{@code ███░░░}）。 */
    public static String bar(int percent) {
        final int p = Math.max(0, Math.min(100, percent));
        final int full = (int) Math.round((double) p * BAR_CELLS / 100.0D);
        final StringBuilder sb = new StringBuilder(BAR_CELLS);
        for (int i = 0; i < BAR_CELLS; i++) {
            sb.append(i < full ? BAR_FULL : BAR_EMPTY);
        }
        return sb.toString();
    }

    /**
     * 动作栏那一行。
     *
     * @param done        已回退 tick 数
     * @param total       总 tick 数
     * @param remaining   还剩多少外层 tick（= 观感剩余时长）
     * @param speed       每外层 tick 回退几个 journal tick（P1 恒 1）
     * @return 例：{@code 倒流 ████░░ 62% · 剩 23s}
     */
    public static String line(int done, int total, int remaining, int speed) {
        final int pct = percent(done, total);
        final int leftTicks = Math.max(0, remaining);
        final int sp = Math.max(1, speed);
        final int seconds = (int) Math.ceil((double) leftTicks / (double) (ShanhaiReplayConst.TICKS_PER_SECOND * sp));
        return String.format(Locale.ROOT, "倒流 %s %d%% · 剩 %ds", bar(pct), pct, seconds);
    }

    private ShanhaiReplayProgress() {
    }
}
