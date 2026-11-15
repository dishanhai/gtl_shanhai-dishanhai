package com.shanhai.add.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>生物血量时间线（"倒流之后血量也要回来"）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 12:53 录像之后，原话）</h2>
 * <blockquote>「倒流之后，生物血量没有恢复，而且回血我要也要每 tick 那样记录」</blockquote>
 * ⇒ 位置层（{@link ShanhaiReplayEntityMotionJournal}）只记了 {@code x/y/z/朝向}，
 * <b>血量从来没进过历史</b>；而血量恰恰是"这只猪刚才被打死过"最直观的证据。
 *
 * <h2>2. 记什么（与位置层同一条纪律）</h2>
 * 每 tick 对 128 格内的非玩家 {@code LivingEntity} 采 {@code (uuid, dim, health)}，
 * <b>只有与上一次采样差 &gt; {@link #HEALTH_EPS} 才记</b>：
 * <ul>
 *   <li>满血站着 100 tick ⇒ 1 条（首帧基线），不是 100 条；</li>
 *   <li>被打 5 次（20→18→15→12→9，每 tick 一次）⇒ 基线 + 5 条；</li>
 *   <li>同一个 tick 同一只只留<b>最后</b>一条（那一 tick 末尾的血量才是"这一 tick 的样子"）。</li>
 * </ul>
 *
 * <h2>3. 回放怎么用</h2>
 * 与位置层<b>同一拍、同一个顺序（LIFO）</b>写回：{@code LivingEntity#setHealth}。
 * 顺序很要紧：{@code 20→18→15→12→9} 倒放必须是 {@code 9,12,15,18,20}，
 * 否则玩家会看到"血量先跳回去再被打一下"。
 *
 * <h2>4. 与位置层的分工</h2>
 * 两层<b>记录分开</b>（各自一条 ring、各自一组计数 {@code motion=…} / {@code health=…}），
 * 但在同一拍内紧挨着应用（先位置、后血量）—— 位置决定"你在哪看它"，血量决定"它看起来还剩多少"。
 */
public final class ShanhaiReplayHealthJournal {

    /** 血量变化阈值：小于它算"没变"（原版浮点回血抖动/客户端插值不记）。 */
    public static final double HEALTH_EPS = 0.001D;

    /** 采样半径（格）：与位置层同一个口径（{@link ShanhaiReplayEntityMotionJournal#RANGE}）。 */
    public static final double RANGE = ShanhaiReplayEntityMotionJournal.RANGE;

    /**
     * 一条血量采样。
     *
     * @param tick      世界 tick
     * @param dim       维度小整数
     * @param uuidMost  实体 UUID 高 64 位
     * @param uuidLeast 实体 UUID 低 64 位
     * @param health    血量
     */
    public record Health(int tick, int dim, long uuidMost, long uuidLeast, float health) {

        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim
                    + " uuid=" + Long.toHexString(this.uuidMost) + ":" + Long.toHexString(this.uuidLeast)
                    + " health=" + this.health;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    private final ShanhaiReplayTimeRing<Health> ring;

    public ShanhaiReplayHealthJournal(int capacity) {
        this.ring = new ShanhaiReplayTimeRing<>(capacity);
    }

    /**
     * 血量真的变了吗（判据：差 &gt; {@link #HEALTH_EPS} 才记）。
     *
     * <p>{@code NaN}（实体没这套字段/读到坏值）一律算"没变"，绝不许记一条写不回去的垃圾。
     */
    public static boolean changed(float before, float after) {
        if (Float.isNaN(before) || Float.isNaN(after)) {
            return false;
        }
        return Math.abs((double) after - (double) before) > HEALTH_EPS;
    }

    /** 同一个 tick 同 uuid 只留最后一条。 */
    public boolean record(int tick, int dim, long uuidMost, long uuidLeast, float health) {
        if (Float.isNaN(health)) {
            return false;
        }
        final Health entry = new Health(tick, dim, uuidMost, uuidLeast, health);
        return this.ring.add(tick, uuidMost * 31L + uuidLeast, entry,
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

    public List<Health> snapshotNewestFirst() {
        return this.ring.snapshotNewestFirst();
    }

    public List<Health> takeNewestTick() {
        return this.ring.takeNewestTick();
    }

    public void clear() {
        this.ring.clear();
    }

    public String summary() {
        return this.ring.summary();
    }

    // ------------------------------------------------------------------ 回退

    /** 血量落点（运行期 = {@code ShanhaiReplayEngine#heal}；自检 = 记录调用序的假体）。 */
    public interface Sink {

        /**
         * 把这只好生物摆回过去那个血量。
         *
         * @return {@code false} = 找不到这只生物 / 它已经不再是 LivingEntity / 写失败
         */
        boolean heal(int dim, long uuidMost, long uuidLeast, float health);
    }

    /**
     * @param applied 真写回条数
     * @param failed  找不到实体/写失败的条数
     */
    public record Result(int applied, int failed) {

        public static Result empty() {
            return new Result(0, 0);
        }

        public String toText() {
            return "health=" + this.applied + "/" + this.failed;
        }
    }

    /** 按给定顺序（LIFO）把血量写回去。 */
    public static Result apply(List<Health> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int applied = 0;
        int failed = 0;
        for (Health h : newestFirst) {
            final boolean ok;
            try {
                ok = sink.heal(h.dim(), h.uuidMost(), h.uuidLeast(), h.health());
            } catch (Throwable t) {
                ++failed;
                continue;
            }
            if (ok) {
                ++applied;
            } else {
                ++failed;
            }
        }
        return new Result(applied, failed);
    }

    /** 实体的合并键（与 {@link #record} 里 ring 的 key 一致）。 */
    public static long keyOf(long uuidMost, long uuidLeast) {
        return uuidMost * 31L + uuidLeast;
    }

    /** 一行判读文本（{@code /time status} 与日志共用）。 */
    public static String line(int records, int applied, int failed) {
        return "health_records=" + records + " health_applied=" + applied + " health_failed=" + failed;
    }
}
