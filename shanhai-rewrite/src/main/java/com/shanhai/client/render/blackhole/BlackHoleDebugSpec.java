package com.shanhai.client.render.blackhole;

/**
 * 山海重构 · <b>黑洞调试坐标（{@code [debug] blackhole_center}）的解析器</b>。
 *
 * <h2>为什么单独一个类</h2>
 * 它是<b>纯逻辑</b>：零 MC、零 Forge、零 GL、零 LWJGL 依赖，只吃一个 {@code String}。
 * 好处是它能在<b>没有游戏</b>的情况下用 {@code javac + java} 跑值表（见
 * {@code temp/bh-spike/selftest/BlackHoleSelfTest.java} 的 {@code spec} 用例组）。
 *
 * <h2>约定（与用户任务书逐字对齐）</h2>
 * <ul>
 *   <li>格式 = <b>三个十进制数，空白分隔</b>（{@code "x y z"}，单位 = 世界方块坐标，即方块中心）。</li>
 *   <li><b>空串 / 全空白 = 不画</b>（任务书：默认空 = 不画）。</li>
 *   <li>解析失败（个数不对 / 非数字 / NaN / Infinity）⇒ {@code null} ⇒ 调用方按「没配」处理，
 *       并打一行带原始值的 WARN —— <b>绝不猜一个默认坐标</b>（那会让"配错了"看起来像"配对了"）。</li>
 * </ul>
 *
 * <p>⚠️ 刻意<b>不支持</b>逗号分隔（{@code "1,2,3"}）：那会被 {@link Double#parseDouble} 当成
 * 一个数而静默得到 {@code (1,2,3)} 之外的东西；直接拒绝比悄悄歪掉好。
 */
public final class BlackHoleDebugSpec {

    /** 日志里用的稳定原因串（不含空白，便于 grep / 断言）。 */
    public static final String REASON_EMPTY = "center_empty";
    /** 见类注释：格式不对。 */
    public static final String REASON_MALFORMED = "center_malformed";

    private BlackHoleDebugSpec() {}

    /**
     * 解析 {@code "x y z"}。
     *
     * @param raw 配置文件里的原始字符串（可为 {@code null}）
     * @return 长度 3 的数组；<b>空 / 空白 / 格式不对一律 {@code null}</b>
     */
    public static double[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        final String[] parts = s.split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        final double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            final double v;
            try {
                v = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (!Double.isFinite(v)) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    /** 日志用：把坐标打成固定两位小数（{@code Locale.ROOT}，避免某些区域设置用逗号当小数点）。 */
    public static String format(double[] c) {
        if (c == null || c.length < 3) {
            return "none";
        }
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", c[0], c[1], c[2]);
    }

    // ------------------------------------------------------------------ 整数坐标补 0.5（裁决 ③）

    /**
     * 「整数坐标自动 +0.5」的结果。
     *
     * <p>为什么只补<b>小数部分恰好为 0</b> 的那一维：用户填 {@code 120 64 -33} 时他心里的坐标是
     * <b>方块 120,64,-33 的中心</b> = {@code 120.5 64.5 -33.5}；而填 {@code 120.37} 时他给的是
     * <b>精确坐标</b>（比如 F3+C 抄的），那时候再补就是错的。⇒ 判据是"这一维是不是整数"，
     * <b>逐维判</b>，不搞一刀切。
     */
    public static final class Snap {
        /** 补完之后的坐标（永远是新数组，不改入参）。 */
        public final double[] coords;
        /** 每一维补了多少（0.0 = 没补）。 */
        public final double dx;
        public final double dy;
        public final double dz;
        /** 是否发生了任何一维的补正。 */
        public final boolean snapped;

        Snap(double[] coords, double dx, double dy, double dz) {
            this.coords = coords;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.snapped = dx != 0.0D || dy != 0.0D || dz != 0.0D;
        }
    }

    /** 维度容差：{@code 1e-9} 之内仍算"整数"（{@code 64.0} 与 {@code 64} 视为同一件事）。 */
    private static final double INT_EPS = 1e-9D;

    /**
     * 把"整数维"补成方块中心（+0.5）。
     *
     * @param c 目标坐标（长度 ≥3；为 {@code null} 时返回 {@code null}）
     * @return 补正结果
     */
    public static Snap snapToCenter(double[] c) {
        if (c == null || c.length < 3) {
            return null;
        }
        final double[] out = {c[0], c[1], c[2]};
        final double dx = snapOne(out, 0);
        final double dy = snapOne(out, 1);
        final double dz = snapOne(out, 2);
        return new Snap(out, dx, dy, dz);
    }

    private static double snapOne(double[] v, int i) {
        final double f = v[i] - Math.floor(v[i]);
        if (f <= INT_EPS || f >= 1.0D - INT_EPS) {
            v[i] = v[i] + 0.5D;           // 整数（含负数）⇒ 补到方块中心
            return 0.5D;
        }
        return 0.0D;
    }
}
