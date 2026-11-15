package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>「粒子真倒放」的收敛模型</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 13:52 录像）</h2>
 * <blockquote>「他这个粒子显然不是倒流的好吧」</blockquote>
 * 上一版只改了<b>初速方向</b>，粒子仍然受重力下坠、贴图年龄往前走、寿命自然耗尽
 * ⇒ 看起来还是"正向冒一下"。
 *
 * <h2>2. 真倒放要同时满足三条</h2>
 * <ol>
 *   <li><b>速度每 tick 都重新指向中心</b>（不是只给一次初速）；</li>
 *   <li><b>重力 = 0</b>（否则又被拉下来）；</li>
 *   <li><b>年龄反演</b>：{@code age} 递减、寿命从短到长（观感是"碎块缩回去后消失"）。</li>
 * </ol>
 *
 * <h2>3. 判据（自检）</h2>
 * 给 3 个偏离中心的粒子跑 5 tick ⇒ <b>到中心的距离必须单调减小</b>（{@link #distanceDecreased}）；
 * 负对照：不改速度（保持原版向外）⇒ 距离不单调 ⇒ 判红。
 */
public final class ShanhaiReplayParticleConverge {

    /** 每 tick 把速度重新指向中心的系数（0.25 ⇒ 3~5 tick 收回来）。 */
    public static final double K = 0.25D;

    /** 距离小于它就算"已收回"（调用方可以 {@code remove()}）。 */
    public static final double DONE = 0.35D;

    /** 一步：给出这一 tick 应当写进去的速度（指向中心，含微小上抬）。 */
    public static double[] step(double px, double py, double pz,
                                double cx, double cy, double cz) {
        final double dx = cx - px;
        final double dy = cy - py;
        final double dz = cz - pz;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-9D) {
            return new double[]{0.0D, 0.0D, 0.0D};
        }
        return new double[]{dx * K, dy * K, dz * K};
    }

    /** 到中心的距离（判据量）。 */
    public static double distance(double px, double py, double pz,
                                  double cx, double cy, double cz) {
        final double dx = cx - px;
        final double dy = cy - py;
        final double dz = cz - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 这一 tick 是否真的"更靠近中心"（严格减小，允许 1e-6 的数值噪声）。 */
    public static boolean distanceDecreased(double before, double after) {
        return after < before - 1.0E-6D;
    }

    /** 是否已经收回（可以 remove）。 */
    public static boolean done(double distance) {
        return distance <= DONE;
    }

    /** 一行判据文本。 */
    public static String line(int ok, int total) {
        return "particle_converge ok=" + ok + "/" + total;
    }

    private ShanhaiReplayParticleConverge() {
    }
}
