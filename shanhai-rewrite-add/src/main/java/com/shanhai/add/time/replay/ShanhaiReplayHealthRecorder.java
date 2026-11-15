package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.ShanhaiWorldClock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 山海重构 · <b>生物血量记录器</b>（"倒流之后血量要回来"这一层的数据源）。
 *
 * <h2>1. 用户口径（2026-11-14 12:53 录像之后）</h2>
 * <blockquote>「倒流之后，生物血量没有恢复，而且回血我要也要每 tick 那样记录」</blockquote>
 *
 * <h2>2. 范围与开销（与位置层同一条纪律，见 {@link ShanhaiReplayEntityMotionRecorder}）</h2>
 * 只采<b>任一玩家 {@link ShanhaiReplayHealthJournal#RANGE} 格内</b>的非玩家 {@code LivingEntity}
 * （{@code ServerLevel#getEntitiesOfClass} 走实体分区索引，不是全表扫描）。
 * 只记<b>变化 &gt; {@link ShanhaiReplayHealthJournal#HEALTH_EPS}</b> 的那几条 ⇒ 满血站着不涨条数。
 *
 * <h2>3. 时间戳与位置层同源</h2>
 * 用 {@code entity.level().getGameTime()}（与位置层/方块层同一口径）。两层若用不同时间源，
 * 回退时会出现"位置对上了、血量差一拍"。
 *
 * <h2>4. 失败方向</h2>
 * 全程自吞：这一层坏了只是"血量不回来"，绝不能让实体 tick 或服务端 tick 受影响。
 */
public final class ShanhaiReplayHealthRecorder {

    /** 采样半径（格）：与位置层同一个数。 */
    public static final double RANGE = ShanhaiReplayHealthJournal.RANGE;

    /** 跟踪上限（每 tick 只遍历这么多个候选）。 */
    private static final int MAX_TRACKED = 4_096;

    private static final ShanhaiReplayHealthJournal JOURNAL =
            new ShanhaiReplayHealthJournal(ShanhaiReplayConst.MAX_HEALTH_RECORDS);

    /** uuid → 上一次采样（血量 + 维度 + 弱引用）。 */
    private static final Map<UUID, Sample> LAST = new HashMap<>();
    /** 这一 tick 见过的（用来判"出范围/消失"）。 */
    private static final Set<UUID> SEEN = new HashSet<>();

    private static long scans;
    private static long sampled;
    private static long recorded;
    private static long skippedSame;
    private static long boundaries;
    private static long deathEdges;
    /** 死亡边界读到 0/负（已改成"最后正血量"）的次数。 */
    private static long deathEdgeNonPositive;
    /** 最近一条死亡边界的正血量（{@code health_death_edge_pos=}）。 */
    private static float deathEdgePositive;
    private static long lastWarnMs;

    private record Sample(int dim, float health, boolean inRange,
                          java.lang.ref.WeakReference<LivingEntity> ref) {
    }

    public static ShanhaiReplayHealthJournal journal() {
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
        scans = 0L;
        sampled = 0L;
        recorded = 0L;
        skippedSame = 0L;
        boundaries = 0L;
        deathEdges = 0L;
    }

    // ------------------------------------------------------------------ 每 tick 采集
    // 🔴 本层<b>不再自己发实体查询</b>：由 {@link ShanhaiReplayEntityMotionRecorder#scan} 复用同一批
    //    {@code getEntitiesOfClass} 结果（两层必须采同一只实体，且不把实体索引查询翻倍）。

    /** 一拍开始（清 SEEN）。由位置层在枚举之前调。 */
    public static void beginTick() {
        SEEN.clear();
        ++scans;
    }

    /** 一拍结束（出范围/消失收尾）。由位置层在枚举之后调。 */
    public static void endTick() {
        try {
            sweepOutOfRange();
        } catch (Throwable t) {
            warnThrottled("生物血量收尾失败（这一拍不会补边界条）", t);
        }
    }

    /**
     * <b>强制</b>记一条（不看"变化 &gt; 0.001"）—— 用于<b>死亡/消失边界</b>。
     *
     * <p>用户原话：「被 kill 的生物血量没回来」。原因很直接：被杀那一刻血量是
     * {@code 20→18→…→0} 的最后一步，但"消失"发生在同一拍，采样表里可能还留着
     * 上一条（差值被阈值吃掉）或根本没有这一只 ⇒ 回退时它没有"死亡前那一格血"可回。
     * 所以消失路径必须<b>补一条</b>，哪怕和上一条一模一样。
     */
    public static void recordNow(int dim, LivingEntity entity, long tick) {
        if (entity == null) {
            return;
        }
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return;
        }
        try {
            float health = entity.getHealth();
            if (!(health > 0.0F)) {
                // 🔴 ⑤ 死亡边界不能记 0：用户「我杀的生物还是没活过来」就是这里——
                //    记 0 ⇒ 回放写 0 ⇒ 又写死一次。改成"最后已知的正血量"（没有就用上限）。
                final Sample last = LAST.get(uuid);
                final float fallback = last != null && last.health() > 0.0F
                        ? last.health() : entity.getMaxHealth();
                ++deathEdgeNonPositive;
                health = fallback;
            }
            deathEdgePositive = health;
            SEEN.add(uuid);
            if (JOURNAL.record((int) tick, dim, uuid.getMostSignificantBits(),
                    uuid.getLeastSignificantBits(), health)) {
                ++recorded;
                ++deathEdges;
            }
            LAST.put(uuid, new Sample(dim, health, true, new java.lang.ref.WeakReference<>(entity)));
        } catch (Throwable t) {
            warnThrottled("死亡边界血量采样失败（这一只回退时可能没有血可回）", t);
        }
    }

    /** 死亡/消失边界补采的条数（判据 B：必须 &gt; 0）。 */
    public static long deathEdges() {
        return deathEdges;
    }

    /** 死亡边界读到 0 的次数（改成最后正血量之前本会是 0）。 */
    public static long deathEdgeNonPositive() {
        return deathEdgeNonPositive;
    }

    /** 死亡边界记下的正血量（判据：必须 &gt; 0）。 */
    public static String deathEdgeLine() {
        return "health_death_edge_pos=" + String.format(java.util.Locale.ROOT, "%.2f", deathEdgePositive)
                + " health_death_edge_nonpositive=" + deathEdgeNonPositive;
    }

    /** 采样一只生物（由位置层逐只转发）。 */
    public static void sampleLiving(int dim, LivingEntity entity) {
        ++sampled;
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return;
        }
        SEEN.add(uuid);
        final float health = entity.getHealth();
        final Sample last = LAST.get(uuid);
        if (last != null && !last.inRange()) {
            ++boundaries;     // 重新进范围 ⇒ 一定记一条（否则回退时会"凭空满血/残血"）
        } else if (last != null && last.dim() == dim
                && !ShanhaiReplayHealthJournal.changed(last.health(), health)) {
            ++skippedSame;
            LAST.put(uuid, new Sample(dim, health, true, new java.lang.ref.WeakReference<>(entity)));
            return;
        }
        if (LAST.size() >= MAX_TRACKED && last == null) {
            return;
        }
        final long tick = entity.level().getGameTime();
        if (JOURNAL.record((int) tick, dim, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                health)) {
            ++recorded;
        }
        LAST.put(uuid, new Sample(dim, health, true, new java.lang.ref.WeakReference<>(entity)));
    }

    /**
     * 出范围/消失的收尾：已经没了的直接忘掉（存在性归实体层）；还活着但出范围的用"最后血量"补一条。
     */
    private static void sweepOutOfRange() {
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
            if (JOURNAL.record((int) entity.level().getGameTime(), s.dim(),
                    entry.getKey().getMostSignificantBits(), entry.getKey().getLeastSignificantBits(),
                    s.health())) {
                ++recorded;
                ++boundaries;
            }
            LAST.put(entry.getKey(), new Sample(s.dim(), s.health(), false, s.ref()));
        }
        for (UUID uuid : gone) {
            LAST.remove(uuid);
        }
    }

    // ------------------------------------------------------------------ 判读

    /** 一行摘要（status / 日志用）。 */
    public static String summary() {
        return "health_recorded=" + recorded + " health_tracked=" + LAST.size()
                + " health_skipped_same=" + skippedSame + " health_boundaries=" + boundaries
                + " journal[" + JOURNAL.summary() + "]";
    }

    private static void warnThrottled(String message, Throwable t) {
        final long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) {
            return;
        }
        lastWarnMs = now;
        if (t == null) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message);
        } else {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}", message, t);
        }
    }

    private ShanhaiReplayHealthRecorder() {
    }
}
