package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>「反向破坏粒子」的初速度</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 13:26 录像之后）</h2>
 * <blockquote>「还有<b>一些粒子特效是倒流结束那一瞬间出现的，而不是随着方块出现而出现，而且也不是倒放的</b>」</blockquote>
 * ⇒ 两件事：① 粒子必须<b>随方块逐格</b>出现（不是攒到最后）；② 粒子要<b>倒放</b>：
 * 正常破坏是"碎块从方块中心向外炸开"，倒放就该是<b>碎块从四面聚回方块中心</b>。
 *
 * <h2>2. 为什么这里只算速度</h2>
 * 原版 {@code ServerLevel#sendParticles(xd,yd,zd)} 的那三个参数是<b>位置偏移</b>，不是速度
 * （{@code ClientLevel#addParticle} 里它们被当 {@code random.nextGaussian()*xd} 的偏移用），
 * 服务端<b>没有</b>"指定粒子初速度"的公开通道 ⇒ 方向只能在客户端定。
 * 所以把这段算术剥成纯函数：自检可以断言"每个分量的方向都指向格心"（与"从格心向外"点积 ≤ 0），
 * 而不需要开游戏。
 *
 * <h2>3. 方向约定（判据）</h2>
 * 粒子出生点 {@code (px,py,pz)}，格心 {@code (cx,cy,cz)} ⇒ 速度 = {@code (c - p) * k}：
 * <ul>
 *   <li>与"从格心向外"的单位向量点积 <b>≤ 0</b>（即朝着格心走）；</li>
 *   <li>退化情形（粒子正好在格心）⇒ 给一个朝上的小速度（仍然不向外）。</li>
 * </ul>
 */
public final class ShanhaiReplayParticleVelocity {

    /** 聚拢速度系数（每格距离给多少速度；0.12 让 8~12 tick 内收进中心）。 */
    public static final double GATHER = 0.12D;

    /** 最近处的最小上抬（避免粒子正好生在格心时速度为 0 卡住不动）。 */
    public static final double MIN_UP = 0.05D;

    /** 速度向量（{@code x/y/z} 三轴）。 */
    public record Velocity(double x, double y, double z) {

        public String toText() {
            return String.format(java.util.Locale.ROOT, "%.3f/%.3f/%.3f", this.x, this.y, this.z);
        }
    }

    /**
     * 算"回到格心"的初速度。
     *
     * @param px 粒子出生点（相对格心最好，但绝对坐标也支持：内部先差分）
     * @param cx 格心
     */
    public static Velocity inward(double px, double py, double pz,
                                  double cx, double cy, double cz, double k) {
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-9D) {
            return new Velocity(0.0D, MIN_UP, 0.0D);
        }
        final double scale = Math.max(0.0D, k) / len;
        double vx = dx * scale;
        double vy = dy * scale + MIN_UP;   // 略微向上：碎块"浮"回去，观感更像倒放
        double vz = dz * scale;
        return new Velocity(vx, vy, vz);
    }

    /** 生产系数。 */
    public static Velocity inward(double px, double py, double pz, double cx, double cy, double cz) {
        return inward(px, py, pz, cx, cy, cz, GATHER);
    }

    /**
     * 判据（自检 X5）：速度与"从格心向外"的方向点积 ≤ 0（= 朝格心，不是炸开）。
     *
     * @return 归一化点积（{@code > 0} = 向外 ⇒ 判红）
     */
    public static double outwardDot(double px, double py, double pz,
                                    double cx, double cy, double cz, Velocity v) {
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-9D || v == null) {
            return 0.0D;
        }
        return (v.x() * dx + v.y() * dy + v.z() * dz) / len;
    }

    private ShanhaiReplayParticleVelocity() {
    }
}
