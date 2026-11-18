package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode;
import com.dishanhai.gt_shanhai.common.time.ShanhaiWorldClock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

/**
 * 山海重构 · <b>世界级标量记录器（第 3 层）+ 整服快照（游戏规则 / 计分板 = 第 4 层）</b>。
 *
 * <h2>1. 记什么（变了才记，逐 tick 连续）</h2>
 * <table border="1">
 *   <caption>标量与写回能力</caption>
 *   <tr><th>key</th><th>读</th><th>写回</th></tr>
 *   <tr><td>{@code day_time}</td><td>{@code ServerLevel#getDayTime()}</td><td>✅ {@code setDayTime}</td></tr>
 *   <tr><td>{@code raining} / {@code thundering}</td><td>{@code Level#isRaining()/isThundering()}</td>
 *       <td>✅ {@code setWeatherParameters}</td></tr>
 *   <tr><td>{@code savedata:gamerules}</td><td>{@code GameRules#createTag()}（整份）</td>
 *       <td>✅ {@code assignFrom(new GameRules(Dynamic(NbtOps,tag)), server)}</td></tr>
 *   <tr><td>{@code savedata:scoreboard}</td><td>脏标记（{@code ServerScoreboard#addDirtyListener}）触发整份快照</td>
 *       <td>✅ 清空 objectives/teams 后再 load 快照</td></tr>
 * </table>
 *
 * <h2>2. 为什么雨强（rain_level）不记</h2>
 * 它是 0..1 的<b>插值量</b>（每 tick 向目标靠拢），没有"直接设成 0.37"的公开 API；
 * 真正可写的是"在下雨/在打雷"这两个开关，雨强由原版按开关自己插值。
 * 记一个写不回去的插值量只会在报告里制造假的 {@code UNSUPPORTED}。
 *
 * <h2>3. 为什么游戏规则记"整份"</h2>
 * {@code GameRules} 没有公开的"逐条写回"入口（{@code Value#deserialize} 是 {@code protected}），
 * 但有 {@code createTag()} + {@code assignFrom(...)} 这一对公开 API ⇒ 记整份快照、整份写回，
 * 反而比逐条记更不容易写坏（原子性）。
 *
 * <h2>4. 收尾必须重新基线</h2>
 * 倒流会把时间/天气/规则改回去；{@code stop()} 之后必须 {@link #rebaseline()} 一次，
 * 否则下一 tick 会把"我们自己改的那一下"当成新历史记进（已经清空的）时间线。
 */
public final class ShanhaiReplayScalarRecorder {

    /** 计分板整份快照的 key。 */
    public static final String KEY_SCOREBOARD = "savedata:scoreboard";
    /** 游戏规则整份快照的 key。 */
    public static final String KEY_GAMERULES = "savedata:gamerules";

    private static final int SCOREBOARD_KEY = ShanhaiReplayScalarJournal.Keys.idOf(KEY_SCOREBOARD);
    private static final int GAMERULES_KEY = ShanhaiReplayScalarJournal.Keys.idOf(KEY_GAMERULES);

    private static final ShanhaiReplayScalarJournal JOURNAL =
            new ShanhaiReplayScalarJournal(ShanhaiReplayConst.MAX_SCALAR_RECORDS);

    /** 上一次读到的值：{@code (dim<<32)|keyId} → 值。 */
    private static final Map<Long, String> LAST = new HashMap<>();

    /** 计分板上一次快照的池槽号（{@code -1} = 还没有基线）。 */
    private static int scoreboardSlot = -1;
    /** 计分板脏标记（{@code ServerScoreboard#addDirtyListener} 打上）。 */
    private static volatile boolean scoreboardDirty;
    /** 游戏规则上一次快照的 tag（比较用）与池槽号。 */
    private static CompoundTag lastRulesTag;
    private static int rulesSlot = -1;

    private static Object boundServer;
    private static long scalarRecords;
    private static long scoreboardCaptures;
    private static long ruleCaptures;
    private static long lastWarnMs;

    /** 🔴 天气写回节流阀（"下雨鬼畜"的唯一根治点；判据 3 就在它上面）。 */
    private static final ShanhaiReplayWeatherGate WEATHER = new ShanhaiReplayWeatherGate();

    /** 判定核（判据 1/2）：稳态 {@code dayTime} +1 与自然倒计时都<b>不记</b>。 */
    private static long dayTimeRecorded;
    private static long dayTimeSkippedSteady;
    private static long flagRecorded;
    private static long flagSkippedSame;
    private static long writeOnlySkipped;

    public static ShanhaiReplayWeatherGate weatherGate() {
        return WEATHER;
    }

    /**
     * 一行节流读数（真机判读用）。
     *
     * <p>判读：{@code day_time steady=0} 说明稳态流逝没被灌进时间线；
     * {@code weather_writes} 与"回写的 tick 数"同量级 ⇒ 没有每 tick 反复拨天气（客户端不会抖）。
     */
    public static String throttleLine() {
        return "day_time_recorded=" + dayTimeRecorded + " day_time_skip_steady=" + dayTimeSkippedSteady
                + " flag_recorded=" + flagRecorded + " flag_skip_same=" + flagSkippedSame
                + " write_only_skipped=" + writeOnlySkipped + " " + WEATHER.summary();
    }

    public static ShanhaiReplayScalarJournal journal() {
        return JOURNAL;
    }

    public static long scalarRecords() {
        return scalarRecords;
    }

    public static long scoreboardCaptures() {
        return scoreboardCaptures;
    }

    public static long ruleCaptures() {
        return ruleCaptures;
    }

    /** 计分板脏回调（注册到 {@code ServerScoreboard#addDirtyListener}）。 */
    public static void markScoreboardDirty() {
        scoreboardDirty = true;
    }

    public static void reset() {
        LAST.clear();
        JOURNAL.clear();
        scoreboardSlot = -1;
        rulesSlot = -1;
        lastRulesTag = null;
        scoreboardDirty = false;
        // 🔴 boundServer 故意不清：计分板脏监听是注册在 ServerScoreboard 上的，
        //    每清一次就重注册一次 ⇒ 监听器会随"每次倒流"线性增长（内存泄漏）。
        //    换服务端时 bind() 用对象身份比较，会重新注册到新实例上。
        scalarRecords = 0L;
        scoreboardCaptures = 0L;
        ruleCaptures = 0L;
        dayTimeRecorded = 0L;
        dayTimeSkippedSteady = 0L;
        flagRecorded = 0L;
        flagSkippedSame = 0L;
        writeOnlySkipped = 0L;
        WEATHER.reset();
    }

    /** 收尾后重新基线（见类注释 §4）。 */
    public static void rebaseline() {
        LAST.clear();
        scoreboardSlot = -1;
        rulesSlot = -1;
        lastRulesTag = null;
        scoreboardDirty = false;
    }

    /** 绑定服务端：注册计分板脏监听（每个服务端一次）。 */
    public static void bind(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            final ServerScoreboard scoreboard = server.getScoreboard();
            if (scoreboard != null && scoreboard != boundServer) {
                scoreboard.addDirtyListener(ShanhaiReplayScalarRecorder::markScoreboardDirty);
            }
            if (server != boundServer) {
                boundServer = server;
                rebaseline();
            }
        } catch (Throwable t) {
            warn("计分板脏监听注册失败（计分板这一层不会记录）", t);
        }
    }

    // ------------------------------------------------------------------ 每 tick 采集

    /** 每个外层 tick 末：读标量（<b>离散事件才记</b>）+ 游戏规则整份比较 + 计分板脏标记。 */
    public static void endOfTick(MinecraftServer server) {
        if (server == null || !ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())) {
            return;
        }
        try {
            bind(server);
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                final int dim = ShanhaiReplayEngine.dimIdOf(level.dimension());
                if (dim < 0) {
                    continue;
                }
                // 🔴 天气写回阀的基线：LIVE 每 tick 喂一次"世界现在那一对"。
                //    REPLAY 期间不喂（记录器整体早退）⇒ 基线就停在"倒流开始那一刻"。
                WEATHER.seed(dim, level.isRaining(), level.isThundering());
                captureDayTime(dim, level);
                captureFlag(dim, level, ShanhaiReplayScalarJournal.Keys.RAINING, level.isRaining());
                captureFlag(dim, level, ShanhaiReplayScalarJournal.Keys.THUNDERING, level.isThundering());
            }
            captureGameRules(server);
            captureScoreboard(server);
        } catch (Throwable t) {
            warn("世界标量采集失败（这一拍的时间/天气不进历史）", t);
        }
    }

    /**
     * {@code dayTime}：<b>稳态不记</b>（每 tick +1 是自然流逝），跳变才记。
     *
     * <p>见 {@link ShanhaiReplayScalarGate#dayTime(long, long)}：判据 1/2 的口径在那里面。
     */
    private static void captureDayTime(int dim, ServerLevel level) {
        final long now = level.getDayTime();
        final long key = keyOf(dim, ShanhaiReplayScalarJournal.Keys.DAY_TIME);
        final String previous = LAST.put(key, Long.toString(now));
        if (previous == null) {
            return;   // 第一次见 = 建立基线，不记
        }
        final long prev;
        try {
            prev = Long.parseLong(previous);
        } catch (Throwable t) {
            return;
        }
        switch (ShanhaiReplayScalarGate.dayTime(prev, now)) {
            case RECORD -> {
                if (JOURNAL.record((int) level.getGameTime(), dim,
                        ShanhaiReplayScalarJournal.Keys.DAY_TIME, previous, Long.toString(now))) {
                    ++scalarRecords;
                    ++dayTimeRecorded;
                }
            }
            case SKIP_STEADY -> ++dayTimeSkippedSteady;
            default -> {
                // SKIP_SAME / SKIP_WRITE_ONLY：什么都不做
            }
        }
    }

    /** {@code raining} / {@code thundering}：<b>翻转</b>才记。 */
    private static void captureFlag(int dim, ServerLevel level, int keyId, boolean now) {
        final long key = keyOf(dim, keyId);
        final String value = Boolean.toString(now);
        final String before = LAST.put(key, value);
        if (before == null) {
            return;   // 基线
        }
        switch (ShanhaiReplayScalarGate.flag(Boolean.parseBoolean(before), now)) {
            case RECORD -> {
                if (JOURNAL.record((int) level.getGameTime(), dim, keyId, before, value)) {
                    ++scalarRecords;
                    ++flagRecorded;
                }
            }
            case SKIP_SAME -> ++flagSkippedSame;
            default -> {
                // 其它分支不会走到
            }
        }
    }

    private static void captureGameRules(MinecraftServer server) {
        final ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        final CompoundTag now = overworld.getGameRules().createTag();
        if (now == null) {
            return;
        }
        if (lastRulesTag == null) {
            lastRulesTag = now.copy();
            return;
        }
        if (now.equals(lastRulesTag)) {
            return;
        }
        final int slot = ShanhaiReplayPayloads.put(now.copy());
        if (slot != ShanhaiReplayJournal.NO_PAYLOAD && rulesSlot >= 0) {
            if (JOURNAL.record((int) overworld.getGameTime(), ShanhaiReplayScalarJournal.DIM_SERVER,
                    GAMERULES_KEY, Integer.toString(rulesSlot), Integer.toString(slot))) {
                ++ruleCaptures;
            }
        }
        rulesSlot = slot;
        lastRulesTag = now.copy();
    }

    private static void captureScoreboard(MinecraftServer server) {
        if (!scoreboardDirty) {
            return;
        }
        scoreboardDirty = false;
        final ServerScoreboard scoreboard = server.getScoreboard();
        if (scoreboard == null) {
            return;
        }
        final CompoundTag tag = scoreboard.createData().save(new CompoundTag());
        if (tag == null) {
            return;
        }
        final int slot = ShanhaiReplayPayloads.put(tag);
        if (slot == ShanhaiReplayJournal.NO_PAYLOAD) {
            return;
        }
        final long tick = server.overworld() == null ? 0L : server.overworld().getGameTime();
        if (scoreboardSlot >= 0) {
            JOURNAL.record((int) tick, ShanhaiReplayScalarJournal.DIM_SERVER, SCOREBOARD_KEY,
                    Integer.toString(scoreboardSlot), Integer.toString(slot));
        }
        scoreboardSlot = slot;
        ++scoreboardCaptures;
    }

    private static long keyOf(int dim, int keyId) {
        return ((long) dim << 32) | (keyId & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 把一条标量写回过去的值。
     *
     * @param dim   维度（{@link ShanhaiReplayScalarJournal#DIM_SERVER} = 整服）
     * @param keyId {@link ShanhaiReplayScalarJournal.Keys} 的 id
     * @param value 过去的值的文本形式
     */
    public static ShanhaiReplayScalarJournal.Outcome write(MinecraftServer server, int dim, int keyId,
                                                           String value) {
        if (server == null || value == null) {
            return ShanhaiReplayScalarJournal.Outcome.FAILED;
        }
        try {
            final String name = ShanhaiReplayScalarJournal.Keys.nameOf(keyId);
            if (KEY_SCOREBOARD.equals(name)) {
                return restoreScoreboard(server, value);
            }
            if (KEY_GAMERULES.equals(name)) {
                return restoreGameRules(server, value);
            }
            final ServerLevel level = levelOf(server, dim);
            if (level == null) {
                return ShanhaiReplayScalarJournal.Outcome.FAILED;
            }
            if ("day_time".equals(name)) {
                level.setDayTime(Long.parseLong(value));
                return ShanhaiReplayScalarJournal.Outcome.WRITTEN;
            }
            if ("raining".equals(name) || "thundering".equals(name)) {
                final boolean raining = "raining".equals(name)
                        ? Boolean.parseBoolean(value) : level.isRaining();
                final boolean thundering = "thundering".equals(name)
                        ? Boolean.parseBoolean(value) : level.isThundering();
                // 🔴 节流：值没变 / 这个维度这一 tick 已经写过 ⇒ 一律不再调 setWeatherParameters。
                //    没有这一条，"一拍 45 条标量"就是 45 次拨天气 = 客户端下雨鬼畜（真机实测）。
                final ShanhaiReplayWeatherGate.Decision decision = WEATHER.request(
                        dim, raining, thundering, (int) level.getGameTime());
                if (decision != ShanhaiReplayWeatherGate.Decision.WRITE) {
                    return ShanhaiReplayScalarJournal.Outcome.SKIPPED;
                }
                level.setWeatherParameters(6_000, raining ? 12_000 : 6_000, raining, thundering);
                return ShanhaiReplayScalarJournal.Outcome.WRITTEN;
            }
            if (ShanhaiReplayScalarGate.writeOnly(name)) {
                // 自然倒计时（rain_time / thunder_time / clear_weather_time）：只写回、不记录，
                // 而且写它没有任何意义（setWeatherParameters 会重新起算）⇒ 直接 skip，不算失败。
                ++writeOnlySkipped;
                return ShanhaiReplayScalarJournal.Outcome.SKIPPED;
            }
            // rain_level / thunder_level 是插值量，不记也不写（见类注释 §2）
            return ShanhaiReplayScalarJournal.Outcome.UNSUPPORTED;
        } catch (Throwable t) {
            warn("写回世界标量失败 key=" + ShanhaiReplayScalarJournal.Keys.nameOf(keyId), t);
            return ShanhaiReplayScalarJournal.Outcome.FAILED;
        }
    }

    /** 游戏规则整份回到过去（{@code createTag()} / {@code assignFrom} 这一对公开 API）。 */
    private static ShanhaiReplayScalarJournal.Outcome restoreGameRules(MinecraftServer server, String value) {
        final int slot = parseIntOr(value, ShanhaiReplayJournal.NO_PAYLOAD);
        final CompoundTag tag = ShanhaiReplayPayloads.get(slot);
        final ServerLevel overworld = server.overworld();
        if (tag == null || overworld == null) {
            return ShanhaiReplayScalarJournal.Outcome.FAILED;
        }
        try {
            final GameRules past = new GameRules(
                    new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, tag.copy()));
            overworld.getGameRules().assignFrom(past, server);
            return ShanhaiReplayScalarJournal.Outcome.WRITTEN;
        } catch (Throwable t) {
            warn("回退游戏规则失败 slot=" + slot, t);
            return ShanhaiReplayScalarJournal.Outcome.FAILED;
        }
    }

    /**
     * 计分板整份回到过去。
     *
     * <p>🔴 先清空 objectives / teams（玩家的分数挂在 objective 上，一起被清），
     * 再 {@code createData(tag)}（它的实现就是 {@code createData().load(tag)}）把快照灌回去 ——
     * 否则 load 是<b>合并</b>语义，快照之后新加的 objective/team 会留在世界上。
     */
    private static ShanhaiReplayScalarJournal.Outcome restoreScoreboard(MinecraftServer server, String value) {
        final int slot = parseIntOr(value, ShanhaiReplayJournal.NO_PAYLOAD);
        final CompoundTag tag = ShanhaiReplayPayloads.get(slot);
        final ServerScoreboard scoreboard = server.getScoreboard();
        if (tag == null || scoreboard == null) {
            return ShanhaiReplayScalarJournal.Outcome.FAILED;
        }
        try {
            for (net.minecraft.world.scores.Objective objective
                    : new ArrayList<>(scoreboard.getObjectives())) {
                scoreboard.removeObjective(objective);
            }
            for (net.minecraft.world.scores.PlayerTeam team
                    : new ArrayList<>(scoreboard.getPlayerTeams())) {
                scoreboard.removePlayerTeam(team);
            }
            scoreboard.createData(tag);
            return ShanhaiReplayScalarJournal.Outcome.WRITTEN;
        } catch (Throwable t) {
            warn("回退计分板失败 slot=" + slot, t);
            return ShanhaiReplayScalarJournal.Outcome.FAILED;
        }
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static ServerLevel levelOf(MinecraftServer server, int dim) {
        if (dim < 0) {
            return null;
        }
        final net.minecraft.resources.ResourceKey<Level> key = ShanhaiReplayEngine.dimKeyOf(dim);
        return key == null ? null : server.getLevel(key);
    }

    public static String summary() {
        return "scalars=" + scalarRecords + " gamerules=" + ruleCaptures
                + " scoreboard_captures=" + scoreboardCaptures
                + " " + throttleLine()
                + " journal[" + JOURNAL.summary() + "]";
    }

    /**
     * 收尾时把 {@code dayTime} 一次性减回到"退掉那么多 tick"之前。
     *
     * <p>为什么需要它：稳态 {@code dayTime} 每 tick +1 是<b>不记</b>的（否则时间线每 tick 一条，
     * 真机实测 8000 条只装下 178 tick）。不记就没有"写回驱动"，太阳不会跟着倒。
     * ⇒ 收尾时按<b>实际退掉的 tick 数</b>做一次减法：稳态时钟下这与逐 tick 记录完全等价，
     * 而且不经过日志、不会每 tick 调 {@code setDayTime}。
     *
     * @param ticks 这次真正退掉的 tick 数（{@code ShanhaiReplayPacer#doneTicks()}）
     */
    public static void catchUpDayTime(MinecraftServer server, int ticks) {
        if (server == null || ticks <= 0) {
            return;
        }
        try {
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                final long now = level.getDayTime();
                level.setDayTime(now - ticks);
            }
            // 🔴 我们刚改了 dayTime ⇒ 必须重新基线，否则下一拍会把这一下当成"跳变"记进新时间线
            rebaseline();
        } catch (Throwable t) {
            warn("收尾回拨 dayTime 失败（太阳位置会停在倒流前）", t);
        }
    }

    private static void warn(String message, Throwable t) {
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

    private ShanhaiReplayScalarRecorder() {
    }
}
