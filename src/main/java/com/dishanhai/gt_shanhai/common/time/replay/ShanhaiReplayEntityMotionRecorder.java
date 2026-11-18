package com.dishanhai.gt_shanhai.common.time.replay;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode;
import com.dishanhai.gt_shanhai.common.time.ShanhaiWorldClock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 山海重构 · <b>实体运动记录器</b>（"猪倒着走"这一层的数据源）。
 *
 * <h2>1. 用户口径</h2>
 * <blockquote>「我放置锚点之后猪还跑了一段，但是我倒流的时候没看到猪倒着跑，而是静止不动了」</blockquote>
 * ⇒ 之前只有"出现/消失"，位置从来没记过；回放时世界冻结 ⇒ 活着的猪一动不动。
 *
 * <h2>2. 范围收敛（这是唯一让它便宜的一条）</h2>
 * 只采<b>任一玩家 {@link #RANGE} 格内</b>的非玩家实体（{@code ServerLevel#getEntitiesOfClass}
 * 走的是实体分区索引，不是全表扫描）。进范围的那一帧一定会记一条（基线），
 * 出范围时再补一条"最后位置"（见 {@link #sweepOutOfRange}）：否则回退时它会从很远的地方瞬移进来。
 *
 * <h2>3. 只记变化</h2>
 * 位移 &le; {@link ShanhaiReplayEntityMotionJournal#MOVE_EPS} 且朝向差 &le; {@code ROT_EPS} ⇒ 不记。
 * 静止 100 tick 只有 1 条（首帧基线）。
 *
 * <h2>4. 失败方向</h2>
 * 全程自吞：这一层坏了只是"看不到倒着走"，绝不能让实体 tick 受影响。
 */
public final class ShanhaiReplayEntityMotionRecorder {

    /** 采样半径（格）：只采任一玩家这么近的实体。 */
    public static final double RANGE = ShanhaiReplayEntityMotionJournal.RANGE;

    /** 跟踪上限（每 tick 只遍历这么多个候选）。 */
    private static final int MAX_TRACKED = 4_096;

    private static final ShanhaiReplayEntityMotionJournal JOURNAL =
            new ShanhaiReplayEntityMotionJournal(ShanhaiReplayConst.MAX_ENTITY_MOTION_RECORDS);

    /** uuid → 上一次采样（含弱引用：区块卸载/实体消失后自动失效，绝不拖住世界内存）。 */
    private static final Map<UUID, Sample> LAST = new HashMap<>();
    /** 这一 tick 见过的（用来判"出范围/消失"）。 */
    private static final Set<UUID> SEEN = new HashSet<>();

    private static long capacityLosses;
    public static long captureLosses() { return capacityLosses; }

    private static long scans;
    private static long sampled;
    private static long recorded;
    private static long skippedSame;
    private static long boundaries;
    private static long lastWarnMs;

    /**
     * 按实体类型计数（2026-11-14 真机："motion=15426 是谁？"）。
     *
     * <p>用户原话：「猪还是<b>没有倒着走</b>」。而当时的日志只有条数，没有类型
     * ⇒ 没法回答"猪到底有没有被记"。这一份账本给出 {@code motion_top=} 与
     * {@code motion_mix recorded[mob/item/other]} + <b>单列猪的三段读数</b>。
     */
    private static final ShanhaiReplayMotionTally TALLY = new ShanhaiReplayMotionTally();

    /**
     * 上一次采样。
     *
     * @param dim      维度小整数
     * @param x/y/z    位置
     * @param yRot/xRot 朝向
     * @param inRange  {@code false} = 已经出过范围（不再采样，等它再进来）
     * @param ref      弱引用（判"是走了还是没了"）
     */
    private record Sample(int dim, float x, float y, float z, float yRot, float xRot,
                          boolean inRange, WeakReference<Entity> ref) {
    }

    public static ShanhaiReplayEntityMotionJournal journal() {
        return JOURNAL;
    }

    public static long recorded() {
        return recorded;
    }

    public static int tracked() {
        return LAST.size();
    }

    public static void reset() {
        LAST.clear();
        SEEN.clear();
        JOURNAL.clear();
        TALLY.reset();
        capacityLosses = 0L;
        scans = 0L;
        sampled = 0L;
        recorded = 0L;
        skippedSame = 0L;
        boundaries = 0L;
    }

    // ------------------------------------------------------------------ 按类型判读

    /** 实体类型 id（{@code minecraft:pig} 这种）；取不到就给 {@code unknown}（绝不因此不记）。 */
    public static String typeKeyOf(net.minecraft.world.entity.Entity entity) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.getType()).toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** 生物 / 掉落物 / 其它（口径只有一份：{@link ShanhaiReplayMotionTally#kindOf}）。 */
    public static ShanhaiReplayMotionTally.Kind kindOf(net.minecraft.world.entity.Entity entity) {
        return ShanhaiReplayMotionTally.kindOf(
                entity instanceof net.minecraft.world.entity.LivingEntity,
                entity instanceof net.minecraft.world.entity.item.ItemEntity);
    }

    /** 按类型计数（运行期写回/发包侧也往同一份账本里记，见 {@code ShanhaiReplayEngine}）。 */
    public static ShanhaiReplayMotionTally tally() {
        return TALLY;
    }

    /** {@code motion_top=…}（回放开始 / 结束各打一次）。 */
    public static String topLine() {
        return TALLY.topLine();
    }

    /** {@code motion_mix …}（含单列猪的三段读数）。 */
    public static String mixLine() {
        return TALLY.mixLine();
    }

    // ------------------------------------------------------------------ 每 tick 采集

    /** 每个外层 tick：把玩家周围实体的位置采一遍（LIVE 与 PAUSED 都采；只有 REPLAY 不采）。 */
    public static void scan(MinecraftServer server) {
        if (server == null
                || !ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())) {
            return;
        }
        ++scans;
        try {
            SEEN.clear();
            ShanhaiReplayHealthRecorder.beginTick();
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                final List<ServerPlayer> players = level.players();
                if (players.isEmpty()) {
                    continue;
                }
                final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
                if (dim < 0) {
                    continue;
                }
                for (ServerPlayer player : players) {
                    if (player == null) {
                        continue;
                    }
                    final List<Entity> near = level.getEntitiesOfClass(Entity.class,
                            player.getBoundingBox().inflate(RANGE),
                            e -> e != null && !(e instanceof Player) && !e.isRemoved()
                                    && e.tickCount > 0);
                    for (Entity entity : near) {
                        sample(dim, entity);
                        // 🔴 血量层复用同一批枚举结果（不再发第二次 getEntitiesOfClass）：
                        //    两层采的必须是同一只实体，否则回退时会出现"位置对上了、血量差一拍"。
                        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                            ShanhaiReplayHealthRecorder.sampleLiving(dim, living);
                        }
                    }
                }
            }
            sweepOutOfRange(server);
            ShanhaiReplayHealthRecorder.endTick();
        } catch (Throwable t) {
            warnThrottled("实体运动采样失败（这一拍不会记位置）", t);
        }
    }

    private static void sample(int dim, Entity entity) {
        ++sampled;
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return;
        }
        SEEN.add(uuid);
        final float x = (float) entity.getX();
        final float y = (float) entity.getY();
        final float z = (float) entity.getZ();
        final float yRot = entity.getYRot();
        final float xRot = entity.getXRot();
        final Sample last = LAST.get(uuid);
        if (last != null && !last.inRange()) {
            // 重新进范围 ⇒ 一定记一条（否则回退时会"从很远的地方瞬移进来"）
            ++boundaries;
        } else if (last != null && last.dim() == dim
                && !ShanhaiReplayEntityMotionJournal.changed(last.x(), last.y(), last.z(),
                        last.yRot(), last.xRot(), x, y, z, yRot, xRot)) {
            ++skippedSame;
            LAST.put(uuid, new Sample(dim, x, y, z, yRot, xRot, true, new WeakReference<>(entity)));
            return;
        }
        if (LAST.size() >= MAX_TRACKED && last == null) {
            capacityLosses++;
            return;
        }
        final long tick = entity.level().getGameTime();
        if (JOURNAL.record((int) tick, dim, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                x, y, z, yRot, xRot)) {
            ++recorded;
            // 🔴 按类型记账（判据：先证明猪有没有被记）
            TALLY.recorded(typeKeyOf(entity), kindOf(entity));
        }
        LAST.put(uuid, new Sample(dim, x, y, z, yRot, xRot, true, new WeakReference<>(entity)));
    }

    /**
     * 出范围/消失的收尾：
     * <ul>
     *   <li>实体已经没了（{@code isRemoved}/弱引用断了）⇒ 什么都不用做（存在性归实体出现/消失层）；</li>
     *   <li>实体还活着但出了范围 ⇒ 用<b>最后已知位置</b>补一条边界采样，然后标记"不在范围"。</li>
     * </ul>
     */
    private static void sweepOutOfRange(MinecraftServer server) {
        if (LAST.isEmpty()) {
            return;
        }
        final List<UUID> gone = new ArrayList<>();
        for (Map.Entry<UUID, Sample> entry : LAST.entrySet()) {
            if (SEEN.contains(entry.getKey())) {
                continue;
            }
            final Sample s = entry.getValue();
            final Entity entity = s.ref() == null ? null : s.ref().get();
            if (entity == null || entity.isRemoved()) {
                gone.add(entry.getKey());
                continue;
            }
            if (!s.inRange()) {
                continue;
            }
            // 还活着、但这一 tick 没在任何一个玩家的 128 格内 ⇒ 补一条"最后位置"
            final ServerLevel level = levelOf(server, s.dim());
            if (level != null) {
                final long tick = level.getGameTime();
                if (JOURNAL.record((int) tick, s.dim(),
                        entry.getKey().getMostSignificantBits(), entry.getKey().getLeastSignificantBits(),
                        s.x(), s.y(), s.z(), s.yRot(), s.xRot())) {
                    ++recorded;
                    ++boundaries;
                }
            }
            LAST.put(entry.getKey(), new Sample(s.dim(), s.x(), s.y(), s.z(), s.yRot(), s.xRot,
                    false, s.ref()));
        }
        for (UUID uuid : gone) {
            LAST.remove(uuid);
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, int dim) {
        if (server == null) {
            return null;
        }
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
                ShanhaiReplayEngine.dimKeyOf(dim);
        return key == null ? null : server.getLevel(key);
    }

    public static String summary() {
        return "motion_recorded=" + recorded + " motion_tracked=" + LAST.size()
                + " motion_skipped_same=" + skippedSame + " motion_boundaries=" + boundaries
                + " journal[" + JOURNAL.summary() + "]";
    }

    private static void warnThrottled(String message, Throwable t) {
        final long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) {
            return;
        }
        lastWarnMs = now;
        if (t == null) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message);
        } else {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message, t);
        }
    }

    private ShanhaiReplayEntityMotionRecorder() {
    }
}
