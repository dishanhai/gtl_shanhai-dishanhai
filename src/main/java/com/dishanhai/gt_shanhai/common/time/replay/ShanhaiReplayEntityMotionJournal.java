package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>实体运动时间线（第 2 层）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11 真机实测的新缺口）</h2>
 * <blockquote>「我放置锚点之后猪还跑了一段，但是我倒流的时候没看到猪倒着跑，而是静止不动了」</blockquote>
 * ⇒ 之前的实体层只做"<b>删/重建</b>"（存在性），不写位置。倒流期间世界被冻结，
 * 于是活着的猪一动不动，走到它的记录点才"啪"一下消失。用户要的是<b>沿原路倒着走回去</b>。
 *
 * <h2>2. 记什么（只记变化）</h2>
 * 每 tick 对范围内的非玩家实体采样 {@code (uuid, dim, x, y, z, yRot, xRot)}，
 * <b>只有真的动了才记</b>（位移 &gt; {@link #MOVE_EPS} 或朝向差 &gt; {@link #ROT_EPS}）：
 * <ul>
 *   <li>静止 100 tick ⇒ 1 条（首帧基线），不是 100 条；</li>
 *   <li>移动 10 tick ⇒ 11 条（基线 + 10 次移动）。</li>
 * </ul>
 * 同一个 tick 同 uuid 只留<b>最后</b>一条（那一 tick 末尾的位置就是"这一 tick 的样子"）。
 *
 * <h2>3. 回放怎么用</h2>
 * 一次外层 tick 取走落到该 tick 的桶，按<b>记录顺序的逆序</b>（LIFO）逐条
 * {@code moveTo(pos, yRot, xRot)} + {@code setOldPosAndRot()}（照工程时钟那套避免插值抖动）。
 * 回放期间实体<b>不 tick</b>（世界 tick 被整段取消），位置只由我们写 ⇒ 观感就是"倒着走"。
 *
 * <h2>4. 与"出现/消失"层的配合</h2>
 * 顺序固定在 {@link ShanhaiReplayPacer}：先删/建（存在性），再写位置（运动）。
 * <b>本 tick 被删掉的实体不再写位置</b>（它已经不存在了 —— 写位置会让客户端收到"幽灵实体移动"）。
 */
public final class ShanhaiReplayEntityMotionJournal {

    /** 采样半径（格）：只采任一玩家这么近的实体（判据 c 就是它）。 */
    public static final double RANGE = 128.0D;

    /** 位移阈值（格）：小于它算"没动"（原版微抖/重力沉降不记）。 */
    public static final double MOVE_EPS = 0.01D;

    /** 朝向阈值（度）：小于它算"没转"。 */
    public static final double ROT_EPS = 0.5D;

    /**
     * 这个位移向量在不在采样半径内。
     *
     * <p>抽成纯函数是为了能离线断言"128 格外的实体不被采样"（判据 c）——
     * 否则这条判据只能靠进游戏看日志。
     */
    public static boolean inRange(double dx, double dy, double dz) {
        final double limit = RANGE * RANGE;
        return dx * dx + dy * dy + dz * dz <= limit;
    }

    /**
     * 一条位置采样。
     *
     * @param tick      世界 tick
     * @param dim       维度小整数
     * @param uuidMost  实体 UUID 高 64 位
     * @param uuidLeast 实体 UUID 低 64 位
     * @param x         位置
     * @param y         位置
     * @param z         位置
     * @param yRot      偏航角（度）
     * @param xRot      俯仰角（度）
     */
    public record Motion(int tick, int dim, long uuidMost, long uuidLeast,
                         float x, float y, float z, float yRot, float xRot) {

        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim
                    + " uuid=" + Long.toHexString(this.uuidMost) + ":" + Long.toHexString(this.uuidLeast)
                    + " pos=" + this.x + "/" + this.y + "/" + this.z
                    + " rot=" + this.yRot + "/" + this.xRot;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    private final ShanhaiReplayTimeRing<Motion> ring;

    public ShanhaiReplayEntityMotionJournal(int capacity) {
        this.ring = new ShanhaiReplayTimeRing<>(capacity);
    }

    /**
     * 真的动了吗（判据 b：静止不记、移动才记）。
     *
     * @return {@code true} = 位移或朝向超过阈值
     */
    public static boolean changed(float x0, float y0, float z0, float yRot0, float xRot0,
                                  float x1, float y1, float z1, float yRot1, float xRot1) {
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double dz = z1 - z0;
        if (dx * dx + dy * dy + dz * dz > MOVE_EPS * MOVE_EPS) {
            return true;
        }
        return Math.abs(yRot1 - yRot0) > ROT_EPS || Math.abs(xRot1 - xRot0) > ROT_EPS;
    }

    /** 同一个 tick 同 uuid 只留最后一条（那一 tick 末尾的位置）。 */
    public boolean record(int tick, int dim, long uuidMost, long uuidLeast,
                          float x, float y, float z, float yRot, float xRot) {
        final Motion motion = new Motion(tick, dim, uuidMost, uuidLeast, x, y, z, yRot, xRot);
        return this.ring.add(tick, uuidMost * 31L + uuidLeast, motion,
                (older, newer) -> older.dim() == newer.dim()
                        && older.uuidMost() == newer.uuidMost() && older.uuidLeast() == newer.uuidLeast()
                        ? newer : null,
                ShanhaiReplayConst.MAX_PER_TICK);
    }

    public int size() {
        return this.ring.size();
    }

    public boolean isEmpty() {
        return this.ring.isEmpty();
    }

    public int newestTick() {
        return this.ring.newestTick();
    }

    public int oldestTick() {
        return this.ring.oldestTick();
    }

    public long droppedOldest() {
        return this.ring.droppedOldest();
    }

    public long overflow() {
        return this.ring.overflow();
    }

    public List<Motion> snapshotNewestFirst() {
        return this.ring.snapshotNewestFirst();
    }

    public List<Motion> takeNewestTick() {
        return this.ring.takeNewestTick();
    }

    public void clear() {
        this.ring.clear();
    }

    public String summary() {
        return this.ring.summary();
    }

    // ------------------------------------------------------------------ 回退

    /** 回退落点（运行期 = {@code ShanhaiReplayEngine}；自检 = 记录调用序的假体）。 */
    public interface Sink {

        /**
         * 把实体摆到过去那个位置。
         *
         * @return {@code false} = 找不到这只实体（区块没加载 / 已经没了）
         */
        boolean move(int dim, long uuidMost, long uuidLeast,
                     float x, float y, float z, float yRot, float xRot);

        /**
         * 这只实体<b>现在</b>的位置（判据 A3：重建之后位置有没有真的落成记录里的那个）。
         *
         * @return {@code null} = 查不到（区块没加载 / 不是这只 / 落点不提供）
         */
        default float[] appliedPos(int dim, long uuidMost, long uuidLeast) {
            return null;
        }

        /**
         * 判据 A1：这一轮倒流里被"重建"过的实体，之后再写位置时属于<b>写给了重建后的实例</b>。
         *
         * <p>默认 {@code false}（自检假体不需要关心）。
         */
        default boolean rebuiltSinceStart(long uuidMost, long uuidLeast) {
            return false;
        }
    }

    /**
     * @param moved  真写回位置条数
     * @param failed 找不到实体/写失败的条数
     */
    public record Result(int moved, int failed) {

        public static Result empty() {
            return new Result(0, 0);
        }
    }

    /** 按给定顺序（LIFO）把位置写回去。 */
    public static Result apply(List<Motion> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int moved = 0;
        int failed = 0;
        for (Motion m : newestFirst) {
            if (sink.move(m.dim(), m.uuidMost(), m.uuidLeast(), m.x(), m.y(), m.z(), m.yRot(), m.xRot())) {
                ++moved;
            } else {
                ++failed;
            }
        }
        return new Result(moved, failed);
    }

    /** 实体的合并键（与 {@link #record} 里 ring 的 key 一致；pacer 用它过滤"本 tick 被删掉的"）。 */
    public static long keyOf(long uuidMost, long uuidLeast) {
        return uuidMost * 31L + uuidLeast;
    }
}
