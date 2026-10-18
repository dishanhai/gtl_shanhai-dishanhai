package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/**
 * 山海 · <b>持续压制（B 件）的执行体：把"我们抹过的类型"记进窗口表，并在窗口内每 5 tick 复查一次</b>。
 *
 * <h2>它接在哪</h2>
 * <pre>
 *   来源①  {@code ShanhaiPurge#purgeAll}   —— {@code /shanhai purge} 真抹掉的那一只的类型
 *   来源②  {@code ShanhaiPurge#drainQueue} —— 「挨打反击」抹掉的那个攻击者的类型
 *   执行   {@code ShanhaiPurge#onServerTick} 每 5 tick 调一次 {@link #scanAndRemove}
 * </pre>
 * ⇒ <b>没有第三个入口</b>，而且这两个入口本身都还要求"身上带模块 / 受保护玩家被攻击"，
 * 所以"平时世界里正常生物"永远不会被无端压制。
 *
 * <h2>🔴 fail-closed 三条（规格红线）</h2>
 * <ol>
 *   <li><b>只压制"被我们抹过的类型"</b>：兜里只有一个 {@link #noteRemoved} 入口，没有"按名字猜"的通道；</li>
 *   <li><b>任何 mod 名/类名/id 常量都不在本类里</b>
 *       （判据可机器验：本文件里 grep 任何一个具体 mod 的名字 ⇒ 0 命中）；</li>
 *   <li><b>玩家永不上榜、永不被扫</b>：{@link #noteRemoved} 直接拒 {@code EntityType.PLAYER}，
 *       扫描时再拒一次 {@code instanceof Player} / 玩家类型（两道，任一道生效即可）。</li>
 * </ol>
 *
 * <h2>为什么"扫一次"要复用 {@code ShanhaiPurge#ladder}</h2>
 * 规格原话：「走现有 L1→L6 阶梯，同一套代码别复制」。压制扫描里每一只都直接调
 * {@code ShanhaiPurge.ladder(level, entity, owner, false)} —— 通道、核实、L6 隔离、重申表全都不另写一份；
 * 最后一个参数 {@code announce=false} 只是<b>不许它往左下角刷"已抹除 X"</b>
 * （压制有自己那一句「已压制 X ×N」，见 {@link #drain}）。
 *
 * <h2>状态不持久化</h2>
 * 全在内存（{@link ShanhaiGuardSuppressWindow.WindowTable}）；换世界（新的服务器实例）时
 * {@code ShanhaiPurge#tickSuppression} 会调 {@link #reset()} 清空 —— 存档里不留任何东西。
 */
public final class ShanhaiGuardSuppression {

    private ShanhaiGuardSuppression() {}

    /** 窗口表：{@code EntityType<?> ⇒ 截止时间(ms)}（游戏侧的那一份 {@code Map<EntityType<?>, Long>}）。 */
    private static final ShanhaiGuardSuppressWindow.WindowTable<EntityType<?>> TABLE =
            new ShanhaiGuardSuppressWindow.WindowTable<>();

    /**
     * 自上次"落一行日志/播一句"以来，每种类型又被抹掉几只 ＋ 它的人话名字（第一只的名字）。
     * <p>为什么攒着：规格要"不要每 tick 刷"，而窗口内每 5 tick 就扫一次（= 每秒 4 次）
     * ⇒ 按 {@link ShanhaiGuardSuppressWindow#FLUSH_INTERVAL_MS} 合并成每秒一行/一句，数字是这一秒的总和。
     */
    private static final Map<EntityType<?>, Stat> SINCE_FLUSH = new LinkedHashMap<>();

    /** 最近一次"触发压制"的玩家（播报收件人；<b>不消费</b> —— 与 {@code ShanhaiPurge} 的 LAST_OWNER 不同）。 */
    private static UUID OWNER = null;

    /** 一次合并窗口里，某个类型的结果。 */
    public static final class Stat {

        /** 类型。 */
        public final EntityType<?> type;
        /** 人话名字（取自这一批里第一只实体的显示名；空则退回注册名）。 */
        public final String name;
        /** 这一批一共又抹掉几只（内部累加用，读的人只看 {@link #drain} 给出的快照）。 */
        public int removed;
        /** 现在的窗口剩余毫秒。 */
        public final long windowLeftMs;

        Stat(EntityType<?> type, String name, int removed, long windowLeftMs) {
            this.type = type;
            this.name = name;
            this.removed = removed;
            this.windowLeftMs = windowLeftMs;
        }
    }

    // ═══════════════════════════════ 来源① / 来源② 共用：上榜 ═══════════════════════════════

    /**
     * 🔴 <b>把"我们刚抹过的这个类型"记进压制表</b>（唯一入口）。
     *
     * @param type   被抹实体（或攻击者）的类型
     * @param owner  触发者（用于左下角播报的收件人；{@code null} = 只进日志）
     * @param source 来源串（{@code purge} / {@code counter}，进日志便于分辨）
     */
    public static void noteRemoved(EntityType<?> type, ServerPlayer owner, String source) {
        if (type == null || type == EntityType.PLAYER) {
            return;   // 🔴 fail-closed：玩家永不上榜
        }
        try {
            final long now = System.currentTimeMillis();
            final boolean fresh = !TABLE.isActive(type, now);
            final long left = TABLE.note(type, now);
            if (owner != null && !owner.hasDisconnected()) {
                OWNER = owner.getUUID();
            }
            if (fresh) {
                // 只在"刚上榜"那一次打日志（同一类型 60 秒内不会重复刷这一行）
                ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "suppress_enroll type=" + typeId(type)
                        + " source=" + source + " window_left_ms=" + left
                        + "（窗口内每 " + ShanhaiGuardSuppressWindow.SCAN_EVERY_TICKS + " tick 复查一次）");
            }
        } catch (Throwable t) {
            // 压制是"附加动作"：它坏了也不许影响主流程（purge / 反击）
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "suppress 上榜抛异常（跳过这一只）：" + t);
        }
    }

    // ═══════════════════════════════ 每 5 tick：扫 + 抹 ═══════════════════════════════

    /**
     * 🔴 <b>扫一遍当前服务器的所有维度，把还在窗口里的类型再抹一次</b>。
     *
     * <p>口径说明：规格写的是「当前维度」，这里扫的是 {@code server.getAllLevels()}（<b>所有</b>维度）——
     * 理由是"同一只 boss 会跟玩家跨维度"（追随/传送），只扫当前维度会漏。代价是每 5 tick 多遍历几张实体表
     * （一般 1~3 个维度，且窗口表为空时直接返回，一次都不遍历）。
     *
     * @param announceTo 传给抹除阶梯的"播报收件人"（阶梯这一步 <b>不播报</b>，见类注释）
     * @return 这一轮真抹掉几只
     */
    public static int scanAndRemove(MinecraftServer server, long nowMillis, ServerPlayer announceTo) {
        if (server == null) {
            return 0;
        }
        TABLE.prune(nowMillis);
        if (TABLE.size() == 0) {
            return 0;   // 表空 ⇒ 一次实体遍历都不做
        }
        int removed = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            for (final Entity entity : ShanhaiPurge.snapshot(level)) {
                if (entity == null || entity.isRemoved()) {
                    continue;
                }
                final EntityType<?> type;
                try {
                    type = entity.getType();
                } catch (Throwable ignored) {
                    continue;
                }
                // 🔴 第二道玩家闸门（第一道在 noteRemoved）
                if (type == null || type == EntityType.PLAYER || entity instanceof Player) {
                    continue;
                }
                if (!TABLE.isActive(type, nowMillis)) {
                    continue;
                }
                // 同一套 L1→L6 阶梯（最后一个 false = 别往左下角刷"已抹除 X"）
                final String channel = ShanhaiPurge.ladder(level, entity, announceTo, false);
                if (channel == null) {
                    continue;   // 这一轮没解决（已进重申表 / L6 隔离）⇒ 不算"又抹掉一只"
                }
                removed++;
                TABLE.note(type, nowMillis);   // 每抹一次 +5 秒（上限 5 分钟，见纯核）
                final Stat stat = SINCE_FLUSH.computeIfAbsent(type, k -> new Stat(k, displayOf(entity), 0, 0L));
                stat.removed++;
            }
        }
        return removed;
    }

    /** 这一秒里攒下东西了吗（给"每秒最多落一行"那条闸门用；不消费）。 */
    public static boolean hasPending() {
        return !SINCE_FLUSH.isEmpty();
    }

    /** 取出并清空"自上次落盘以来"的统计（调用方负责打日志/播报）。 */
    public static List<Stat> drain(long nowMillis) {
        if (SINCE_FLUSH.isEmpty()) {
            return List.of();
        }
        final List<Stat> out = new ArrayList<>(SINCE_FLUSH.size());
        for (final Map.Entry<EntityType<?>, Stat> e : SINCE_FLUSH.entrySet()) {
            final Stat old = e.getValue();
            final long left = TABLE.leftMillis(e.getKey(), nowMillis);
            out.add(new Stat(old.type, old.name, old.removed, left));
        }
        SINCE_FLUSH.clear();
        return out;
    }

    /** 播报收件人（最近一次触发压制的玩家；离线/已登出 ⇒ {@code null}）。 */
    public static ServerPlayer owner(MinecraftServer server) {
        final UUID id = OWNER;
        if (id == null || server == null) {
            return null;
        }
        final ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || player.hasDisconnected()) {
            OWNER = null;
            return null;
        }
        return player;
    }

    // ═══════════════════════════════ 读数 / 命令 / 清空 ═══════════════════════════════

    /** 一个类型名（注册名；取不到时退回 {@code toString()}，绝不编造）。 */
    public static String typeId(EntityType<?> type) {
        if (type == null) {
            return "?";
        }
        try {
            final ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id != null) {
                return id.toString();
            }
        } catch (Throwable ignored) {
            // 注册表还没起来 / 被替换 ⇒ 走下面的兜底
        }
        return String.valueOf(type);
    }

    /** 一只实体的人话名字（显示名；空则退回注册名）。 */
    private static String displayOf(Entity entity) {
        try {
            final String name = entity.getDisplayName().getString();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
            // 显示名读不到 ⇒ 退回注册名
        }
        return typeId(entity.getType());
    }

    /** {@code /shanhai suppress list} 的逐行回显。 */
    public static List<String> listLines(long nowMillis) {
        final List<String> out = new ArrayList<>();
        final Map<EntityType<?>, Long> snap = TABLE.snapshot(nowMillis);
        out.add("§7压制表：" + snap.size() + " 项 ／ 窗口 "
                + (ShanhaiGuardSuppressWindow.WINDOW_MS / 1000L) + " 秒起，每抹一次 +"
                + (ShanhaiGuardSuppressWindow.EXTEND_MS / 1000L) + " 秒，上限 "
                + (ShanhaiGuardSuppressWindow.WINDOW_MAX_MS / 1000L) + " 秒 ／ 每 "
                + ShanhaiGuardSuppressWindow.SCAN_EVERY_TICKS + " tick 复查一次");
        if (snap.isEmpty()) {
            out.add("§7（空 —— 我们还没抹过任何类型）");
            return out;
        }
        int i = 0;
        for (final Map.Entry<EntityType<?>, Long> e : snap.entrySet()) {
            i++;
            out.add("§7" + i + ". §f" + typeId(e.getKey()) + " §7剩 §f"
                    + ShanhaiGuardSuppressWindow.secondsLeft(e.getValue() - nowMillis) + " §7秒");
        }
        return out;
    }

    /** 清空压制表；返回清掉几项。 */
    public static int clear() {
        SINCE_FLUSH.clear();
        return TABLE.clear();
    }

    /** 换世界（新的服务器实例）时清空：不把上一局的压制带过来。 */
    public static void reset() {
        TABLE.clear();
        SINCE_FLUSH.clear();
        OWNER = null;
    }

    /** 一行读数（开机日志 / 命令回显）。 */
    public static String statusLine() {
        return "suppress active=" + TABLE.snapshot(System.currentTimeMillis()).size()
                + " window_ms=" + ShanhaiGuardSuppressWindow.WINDOW_MS
                + " extend_ms=" + ShanhaiGuardSuppressWindow.EXTEND_MS
                + " max_ms=" + ShanhaiGuardSuppressWindow.WINDOW_MAX_MS
                + " scan_ticks=" + ShanhaiGuardSuppressWindow.SCAN_EVERY_TICKS;
    }
}
