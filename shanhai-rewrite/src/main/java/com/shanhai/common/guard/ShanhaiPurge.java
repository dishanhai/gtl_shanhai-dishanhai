package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海 · <b>③ 攻击端（开关，默认开）</b>：受保护玩家挨打 ⇒ 反手抹掉攻击者；主动触发 ⇒ 抹掉全服实体。
 *
 * <h2>两个触发</h2>
 * <pre>
 *   触发 A（挨打反击）  {@code LivingAttackEvent}（{@code receiveCanceled = true}！）
 *                       ⇒ 取消伤害之后，抹除【攻击者本人】。
 *                       ⚠️ 召唤物/投射物一律不处理；玩家一律不处理（安全默认）。
 *                       ⚠️ 为什么必须 {@code receiveCanceled = true}：
 *                          {@code ShanhaiGuardEvents#onLivingAttack} 会先把这个事件取消，
 *                          而 Forge 的 EventBus 对"已取消事件"只发给声明了 receiveCanceled 的监听器
 *                          ⇒ 不声明的话，守护越成功这条反击越永远不触发。
 *   触发 B（主动抹除）  {@code /shanhai purge}（或全息板第 1 格「清除实体」点一次）⇒ 抹除【全服所有实体】。
 *                       🔴 安全默认：跳过所有玩家（含自己）与 {@code EntityType.PLAYER}；
 *                          跳过的数量记日志、也进结束播报。
 * </pre>
 *
 * <h2>🆕 持续压制（suppression list）</h2>
 * 用户现场：{@code /shanhai purge} 每次抹掉 6~75 只，但<b>过一会它又回来继续打</b>（对方自带复活机制）。
 * 所以上面两个触发点在<b>真抹掉</b>之后各加一件事：把那一只的 {@code EntityType} 记进
 * {@link ShanhaiGuardSuppression} 的窗口表（默认 60 秒、每再抹一次 +5 秒、上限 5 分钟、不持久化）；
 * 窗口内每 {@value ShanhaiGuardSuppressWindow#SCAN_EVERY_TICKS} tick 扫一遍实体表，
 * 命中的类型<b>走同一个 {@link #ladder}</b> 再抹一次，并按秒合并成
 * {@code [SHANHAI-GUARD] suppress type=… removed=… window_left_ms=…} ＋ 左下角「已压制 … ×N」。
 * <p>🔴 只压制"被我们抹过的类型"：兜里只有 {@code noteRemoved} 一个入口，<b>没有任何 mod 名/类名/id 常量</b>。
 *
 * <h2>移除阶梯（逐级判成功，成功就停）</h2>
 * <pre>
 *   L1  entity.discard()
 *   L2  核实：isRemoved()==true 且 level.getEntity(id)==null
 *   L3  entity.setRemoved(DISCARDED)          ← final 方法，绕过被重写的 remove()
 *   L4  给所有玩家发 ClientboundRemoveEntitiesPacket(entity.getId())
 *   L5  反射拿 ServerLevel 的实体管理器，把它从 id/uuid 两本账里摘掉（只在 L1~L3 都失败时用）
 *   L6  同一只被重申超过 20 次 ⇒ 隔离：传送到极远坐标 ＋ noAi ＋ 隐身 ＋ 每 tick 重申 L3
 * </pre>
 *
 * <h2>日志必须能分辨来源</h2>
 * 每一次都记 {@code purge_channel=L1|L3|L4|L5|L6}（{@link ShanhaiGuardRemoval#purgeLogLine}）。
 *
 * <h2>播报（左下角 = action bar，同 tick 合并）</h2>
 * <pre>
 *   L1        ⇒ 已抹除 &lt;名字&gt;
 *   L3 / L4   ⇒ 已抹除 &lt;名字&gt;（强制）
 *   L5        ⇒ 已抹除 &lt;名字&gt;（底层）
 *   L6        ⇒ 已隔离 &lt;名字&gt;
 *   多只      ⇒ 已抹除 &lt;名字&gt; 等 N 只
 * </pre>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID)
public final class ShanhaiPurge {

    private ShanhaiPurge() {}

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    /** 日志类别：一次抹除动作。 */
    public static final String KIND_PURGE = "抹除";
    /** 日志类别：挨打反击那一路。 */
    public static final String KIND_COUNTER = "反击抹除";

    /** 🔴 隔离坐标（远超原版世界边界 3.0e7；只用于"删不掉"的那一只）。 */
    public static final double ISOLATE_X = 2.9999e7;
    /** 见 {@link #ISOLATE_X}。 */
    public static final double ISOLATE_Y = 4096.0;
    /** 见 {@link #ISOLATE_X}。 */
    public static final double ISOLATE_Z = 2.9999e7;

    // ═══════════════════════════════ 状态 ═══════════════════════════════

    /** 同一只被重申了几次（键 = UUID）。 */
    private static final Map<UUID, Integer> REASSERTS = new HashMap<>();

    /** 已隔离的实体（键 = UUID；值 = 它所在的世界）。 */
    private static final Map<UUID, ServerLevel> ISOLATED = new LinkedHashMap<>();

    /** 本 tick 的播报缓冲（{@code ServerTickEvent(END)} 统一发一次）。 */
    private static final List<Line> PENDING = new ArrayList<>();

    /** 攻击端排队（在事件里直接删实体太危险，统一排到 tick 末做）。 */
    private static final List<Queued> QUEUED = new ArrayList<>();

    // ── 🆕 持续压制（B 件）：节拍、闸门、待播汇总 ──

    /** 当前看到的服务器实例（换实例 = 新的一局 ⇒ 取证计数与压制表都清零）。 */
    private static MinecraftServer SERVER_SEEN = null;

    /** 5 tick 节拍（自己数，不依赖可能被模组改写语义的 {@code getTickCount}）。 */
    private static int SUPPRESS_TICK = 0;

    /** 压制日志/播报的"每秒最多一次"闸门（毫秒时间戳）。 */
    private static long LAST_SUPPRESS_FLUSH = 0L;

    /** 待播的压制汇总（人话名字 ⇒ 这一秒又抹掉几只）。 */
    private static final Map<String, Integer> SUPPRESS_PENDING = new LinkedHashMap<>();

    /** 一条待播报（verb ＋ 名字组件 ＋ 后缀）。 */
    private static final class Line {

        final String verb;
        final Component name;
        final String extra;
        final String plain;

        Line(String verb, Component name, String extra, String plain) {
            this.verb = verb;
            this.name = name;
            this.extra = extra;
            this.plain = plain;
        }
    }

    /** 一条排队的抹除（攻击端）。 */
    private static final class Queued {

        final ServerLevel level;
        final Entity entity;
        final ServerPlayer owner;
        final String trigger;

        Queued(ServerLevel level, Entity entity, ServerPlayer owner, String trigger) {
            this.level = level;
            this.entity = entity;
            this.owner = owner;
            this.trigger = trigger;
        }
    }

    /** 一次主动抹除的读数。 */
    public static final class Result {

        /** 扫到多少只（不含玩家）。 */
        public int scanned;
        /** 抹掉多少只。 */
        public int removed;
        /** 跳过多少个玩家。 */
        public int skippedPlayers;
        /** 逐级解决的只数（键 = purge_channel）。 */
        public final Map<String, Integer> channels = new LinkedHashMap<>();
        /** 这一轮没解决（进了 L6 重申表）的只数。 */
        public int unresolved;

        /** 一行读数（命令回显/日志）。 */
        public String line() {
            return "scanned=" + scanned + " removed=" + removed + " skipped_players=" + skippedPlayers
                    + " unresolved=" + unresolved + " channels=" + channels;
        }
    }

    // ═══════════════════════════════ 触发 A：挨打反击 ═══════════════════════════════

    /**
     * 受保护玩家受到攻击 ⇒ 取消伤害之后反手抹掉攻击者本人。
     *
     * <p>🔴 {@code receiveCanceled = true} 是必须的（理由见类注释触发 A 那一段）。
     */
    @SubscribeEvent(receiveCanceled = true)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!ShanhaiGuardSwitches.attackSide()) {
            return;   // 开关关着：一个字节都不改（默认开）
        }
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        try {
            if (!ShanhaiGuardEvents.isGuarded(victim)) {
                return;
            }
            final Entity attacker = event.getSource().getEntity();       // 真正动手的那个
            final Entity direct = event.getSource().getDirectEntity();   // 直接命中的那个（可能是箭）
            if (attacker == null) {
                // 环境伤害（掉虚空/熔岩/毒…）没有攻击者 ⇒ 无事可做，也绝不乱删东西
                return;
            }
            if (direct != null && direct != attacker) {
                // 🔴 召唤物 / 投射物一律不处理（用户口径）
                logCounter("skipped_summon victim=" + victim.getName().getString()
                        + " direct=" + describe(direct) + " attacker=" + describe(attacker));
                return;
            }
            if (attacker instanceof Player) {
                // 🔴 安全默认：玩家一律不处理（含自己）
                logCounter("skipped_player victim=" + victim.getName().getString()
                        + " attacker=" + describe(attacker));
                return;
            }
            if (!(attacker instanceof LivingEntity)) {
                logCounter("skipped_not_living attacker=" + describe(attacker));
                return;
            }
            final ServerLevel level = victim.serverLevel();
            if (level == null || attacker.level() != level) {
                return;
            }
            // 排到 tick 末再删：现在是伤害流程中间，就地删实体容易踩到各种遍历
            QUEUED.add(new Queued(level, attacker, victim, "发生攻击（LivingAttackEvent）"));
            noteOwner(victim);   // 本 tick 的播报发给挨打的那位
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge 反击判据抛异常（原样放行）：" + t);
        }
    }

    // ═══════════════════════════════ tick 末：排队 + 播报 + 重申 ═══════════════════════════════

    /**
     * 服务端 tick 末：
     * <pre>
     *   ① 处理攻击端排队的那几次抹除
     *   ② 给已隔离的那几只重申 L3（每 tick）
     *   ③ 把本 tick 的播报合并成一行发出去（左下角）
     * </pre>
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            drainQueue();
            reassertIsolated();
            tickSuppression(server);   // 🆕 每 5 tick：把压制窗口内的类型再抹一次（同一套阶梯）
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge tick 末处理抛异常（跳过这一轮）：" + t);
        } finally {
            flushAnnouncements(server);
        }
    }

    /** 处理攻击端排队的那些。 */
    private static void drainQueue() {
        if (QUEUED.isEmpty()) {
            return;
        }
        final List<Queued> batch = new ArrayList<>(QUEUED);
        QUEUED.clear();
        for (final Queued q : batch) {
            final String name = describe(q.entity);
            final String channel = ladder(q.level, q.entity, q.owner);
            // 🆕 持续压制·来源②：挨打反击抹掉的那个攻击者的类型 ⇒ 上压制表
            //    （不论这一轮有没有解决都记：没解决的那一只恰恰最需要每 5 tick 再抹一次）
            ShanhaiGuardSuppression.noteRemoved(q.entity.getType(), q.owner, "counter");
            final String line = LOG.admit(KIND_COUNTER, System.currentTimeMillis());
            if (line != null) {
                ShanhaiMod.LOGGER.warn(line + " ／ 触发=" + q.trigger
                        + " ／ 目标=" + name + " ／ 结果="
                        + (channel == null ? "这一轮没解决（进重申表）" : "已解决")
                        + (channel == null ? "" : " ／ " + ShanhaiGuardRemoval.purgeLogLine(
                                channel, name, q.entity.getId(), true)));
            }
        }
    }

    /** 给已隔离的那几只重申 L3（并补发一次移除包）。 */
    private static void reassertIsolated() {
        if (ISOLATED.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<UUID, ServerLevel>> it = ISOLATED.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, ServerLevel> e = it.next();
            final ServerLevel level = e.getValue();
            for (final Entity entity : snapshot(level)) {
                if (!entity.getUUID().equals(e.getKey())) {
                    continue;
                }
                if (entity.isRemoved()) {
                    it.remove();   // 真死透了 ⇒ 从隔离表里摘掉
                    break;
                }
                try {
                    entity.setRemoved(Entity.RemovalReason.DISCARDED);
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn(PREFIX + "purge 隔离重申 L3 抛异常：" + t);
                }
                sendRemovePacket(level.getServer(), entity.getId());
                break;
            }
        }
    }

    // ═══════════════════════════════ 🆕 持续压制（B 件）：每 5 tick 复查 ═══════════════════════════════

    /**
     * 每服务端 tick 末调一次（在 {@link #onServerTick} 里，紧跟"重申隔离"之后）：
     * <pre>
     *   ① 服务器实例变了（新的一局）⇒ 取证计数与压制表都清零，然后返回（这一 tick 不扫）
     *   ② 每 {@link ShanhaiGuardSuppressWindow#SCAN_EVERY_TICKS} tick 才真扫一次（不是每 tick）
     *   ③ 日志与左下角播报按"每秒最多一次"合并（数字是这一秒的总和；规格：不要每 tick 刷）
     * </pre>
     *
     * <p>🔴 「每局最多打 3 次」的"一局"= 一个服务器实例：换世界/重进存档 ⇒
     * {@link ShanhaiGuardCallerTrace#resetAll()} 让取证额度回到 3 次。
     */
    private static void tickSuppression(MinecraftServer server) {
        if (server != SERVER_SEEN) {
            SERVER_SEEN = server;
            SUPPRESS_TICK = 0;
            LAST_SUPPRESS_FLUSH = 0L;
            SUPPRESS_PENDING.clear();
            ShanhaiGuardCallerTrace.resetAll();
            ShanhaiGuardSuppression.reset();
            ShanhaiMod.LOGGER.info(PREFIX + "suppress_session_reset（新的一局：取证额度与压制表已清零）／ "
                    + ShanhaiGuardSuppression.statusLine());
            return;
        }
        if (++SUPPRESS_TICK % ShanhaiGuardSuppressWindow.SCAN_EVERY_TICKS != 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        final ServerPlayer to = ShanhaiGuardSuppression.owner(server);
        ShanhaiGuardSuppression.scanAndRemove(server, now, to);
        // 这一轮一只都没抹到、也没有攒着的东西 ⇒ 一行日志都不打（窗口自己会到期）
        if (!ShanhaiGuardSuppression.hasPending()) {
            return;
        }
        if (now - LAST_SUPPRESS_FLUSH < ShanhaiGuardSuppressWindow.FLUSH_INTERVAL_MS) {
            return;   // 一秒还没到 ⇒ 攒着，下一秒合并成一行（数字是这一秒的总和）
        }
        LAST_SUPPRESS_FLUSH = now;
        for (final ShanhaiGuardSuppression.Stat stat : ShanhaiGuardSuppression.drain(now)) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardSuppressWindow.logLine(
                    ShanhaiGuardSuppression.typeId(stat.type), stat.removed, stat.windowLeftMs));
            SUPPRESS_PENDING.merge(stat.name, stat.removed, Integer::sum);
        }
    }

    // ═══════════════════════════════ 触发 B：主动抹除 ═══════════════════════════════

    /**
     * 🔴 <b>主动触发：抹除全服所有实体</b>（用户原话「范围是游戏内所有实体」）。
     *
     * <p>安全默认：<b>跳过所有玩家（含自己）与 {@code EntityType.PLAYER}</b>，跳过的数量记日志、
     * 也进结束播报「跳过玩家 M 个」。
     *
     * @param owner 播报收件人（可为 {@code null}：控制台执行时只进日志）
     * @param dryRun true = 只数不抹（{@code /shanhai purge count}）
     */
    public static Result purgeAll(MinecraftServer server, ServerPlayer owner, boolean dryRun) {
        final Result result = new Result();
        if (server == null) {
            return result;
        }
        // 先把全服实体取成快照：边遍历边删实体会在底层 Map 上踩 ConcurrentModificationException
        final List<ServerLevel> levels = new ArrayList<>();
        final List<Entity> targets = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            levels.add(level);
            targets.addAll(snapshot(level));
        }
        // ① 执行前一行总数（日志 ＋ 左下角）
        logPurge("purge_start scope=all_levels levels=" + levels.size()
                + " total_entities=" + targets.size() + " dry_run=" + dryRun);
        announceLine("抹除开始：范围内", Component.literal(String.valueOf(targets.size())), " 只", null, owner);
        if (dryRun) {
            int players = 0;
            for (final Entity e : targets) {
                if (isPlayerLike(e)) {
                    players++;
                }
            }
            result.scanned = targets.size() - players;
            result.skippedPlayers = players;
            logPurge("purge_dry_run " + result.line());
            return result;
        }
        // ② 逐只抹（玩家一律跳过）
        for (final Entity entity : targets) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if (isPlayerLike(entity)) {
                result.skippedPlayers++;
                continue;
            }
            final ServerLevel level = (ServerLevel) entity.level();
            result.scanned++;
            final String name = describe(entity);
            final String channel = ladder(level, entity, owner);
            if (channel == null) {
                result.unresolved++;
                continue;
            }
            result.removed++;
            result.channels.merge(channel, 1, Integer::sum);
            // 🆕 持续压制·来源①：`/shanhai purge` 真抹掉的每个实体的类型 ⇒ 上压制表
            //    （dryRun 走的是上面那条 early return，一行都不会到这里）
            ShanhaiGuardSuppression.noteRemoved(entity.getType(), owner, "purge");
            logPurge(ShanhaiGuardRemoval.purgeLogLine(channel, name, entity.getId(), true));
        }
        // ③ 执行后一行（日志 ＋ 左下角）
        logPurge("purge_done " + result.line());
        announceLine("已抹除", Component.literal(String.valueOf(result.removed)),
                " 只，跳过玩家 " + result.skippedPlayers + " 个", null, owner);
        return result;
    }

    /**
     * 🔴 <b>移除阶梯</b>（L1 → L2 核实 → L3 → L4 → L5 → L6）。
     *
     * @return 实际解决掉用的通道名（{@link ShanhaiGuardRemoval#CH_L1} 等）；
     *         {@code null} = 这一轮没解决（已进重申表，超过 20 次会转隔离）
     */
    public static String ladder(ServerLevel level, Entity entity, ServerPlayer owner) {
        return ladder(level, entity, owner, true);
    }

    /**
     * 同 {@link #ladder(ServerLevel, Entity, ServerPlayer)}，但<b>可以关掉左下角播报</b>。
     *
     * <p>为什么需要它：持续压制在窗口内每 5 tick 就把这些类型重抹一遍（每秒 4 次）——
     * 若还让阶梯播「已抹除 X」，左下角会被刷屏，而规格要的是另外那一句「已压制 X ×N」。
     * <b>只有"播不播报"这一个差异</b>：通道、核实、L5 反射摘账、L6 隔离、重申计数<b>一行都没变</b>
     * ⇒ 这就是同一套阶梯，不是复制第二套。
     */
    static String ladder(ServerLevel level, Entity entity, ServerPlayer owner, boolean announce) {
        if (level == null || entity == null || entity.isRemoved()) {
            return null;
        }
        final int id = entity.getId();
        final UUID uuid = entity.getUUID();
        final String name = describe(entity);

        // ---- L1：discard()（走可被模组重写的 remove） ----
        try {
            entity.discard();
        } catch (Throwable t) {
            logPurge("purge_l1_threw id=" + id + " err=" + t);
        }
        if (verified(level, entity, id)) {
            announceStep(entity, ShanhaiGuardRemoval.CH_L1, owner, announce);
            return ShanhaiGuardRemoval.CH_L1;
        }

        // ---- L3：setRemoved(DISCARDED)（final，绕过被重写的 remove） ----
        try {
            entity.setRemoved(Entity.RemovalReason.DISCARDED);
        } catch (Throwable t) {
            logPurge("purge_l3_threw id=" + id + " err=" + t);
        }
        if (verified(level, entity, id)) {
            announceStep(entity, ShanhaiGuardRemoval.CH_L3, owner, announce);
            return ShanhaiGuardRemoval.CH_L3;
        }

        // ---- L4：给所有玩家发移除包（客户端先看不见） ----
        sendRemovePacket(level.getServer(), id);
        if (verified(level, entity, id)) {
            announceStep(entity, ShanhaiGuardRemoval.CH_L4, owner, announce);
            return ShanhaiGuardRemoval.CH_L4;
        }

        // ---- L5：反射把实体从实体管理器的 id/uuid 两本账里摘掉 ----
        if (detachFromEntityStorage(level, entity)) {
            if (verified(level, entity, id)) {
                announceStep(entity, ShanhaiGuardRemoval.CH_L5, owner, announce);
                return ShanhaiGuardRemoval.CH_L5;
            }
        }

        // ---- L6：重申计数；超过 20 次 ⇒ 隔离 ----
        final int n = REASSERTS.merge(uuid, 1, Integer::sum);
        if (ShanhaiGuardRemoval.shouldIsolate(n)) {
            isolate(level, entity, n);
            announceStep(entity, ShanhaiGuardRemoval.CH_L6, owner, announce);
            return ShanhaiGuardRemoval.CH_L6;
        }
        logPurge("purge_unresolved entity=" + name + " id=" + id + " reasserts=" + n
                + "（L1/L3/L5 都没成功；超过 " + ShanhaiGuardRemoval.ISOLATE_AFTER_REASSERTS + " 次转隔离）");
        return null;
    }

    /** 阶梯里那 5 处播报的唯一出口（{@code announce=false} ⇒ 一个字都不播；见 {@link #ladder} 的 4 参版）。 */
    private static void announceStep(Entity entity, String channel, ServerPlayer owner, boolean announce) {
        if (announce) {
            announceRemoved(entity, channel, owner);
        }
    }

    /** L2：核实「真的没了」= {@code isRemoved()==true} 且 {@code level.getEntity(id)==null}。 */
    private static boolean verified(ServerLevel level, Entity entity, int id) {
        try {
            return entity.isRemoved() && level.getEntity(id) == null;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge 核实抛异常（当作没解决）：" + t);
            return false;
        }
    }

    /** L4：给所有玩家发一次移除包。 */
    private static void sendRemovePacket(MinecraftServer server, int id) {
        if (server == null) {
            return;
        }
        final ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(id);
        for (final ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == null || p.hasDisconnected() || p.connection == null) {
                continue;
            }
            try {
                p.connection.send(packet);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "purge L4 发包失败 player=" + p.getName().getString()
                        + " err=" + t);
            }
        }
    }

    // ═══════════════════════════════ L5 反射摘账 ═══════════════════════════════

    /**
     * 反射拿 {@code ServerLevel} 的实体管理器，把这只实体从 <b>id/uuid 两本账</b>里摘掉。
     *
     * <p>🔴 反射用的是<b>字符串</b>，不会被 reobf 重映射 ⇒ 开发名与生产 SRG 名<b>两个都试</b>
     * （与 {@code ShanhaiGuardValueLayer} 取 {@code DATA_HEALTH_ID} 同款做法）。
     * 名字是 javap ／ TSRG 抄来的，不是猜的：
     * <pre>
     *   ServerLevel.entityManager                     → f_143244_
     *   PersistentEntitySectionManager.visibleEntityStorage → f_157494_
     *   PersistentEntitySectionManager.knownUuids      → f_157491_
     *   EntityLookup.byId                              → f_156807_
     *   EntityLookup.byUuid                            → f_156808_
     * </pre>
     */
    private static boolean detachFromEntityStorage(ServerLevel level, Entity entity) {
        try {
            final Object manager = readField(level, ServerLevel.class,
                    new String[] { "entityManager", "f_143244_" });
            if (manager == null) {
                logPurge("purge_l5_unavailable reason=no_entity_manager");
                return false;
            }
            final Object lookup = readField(manager, manager.getClass(),
                    new String[] { "visibleEntityStorage", "f_157494_" });
            if (lookup == null) {
                logPurge("purge_l5_unavailable reason=no_visible_entity_storage");
                return false;
            }
            final Object byId = readField(lookup, lookup.getClass(), new String[] { "byId", "f_156807_" });
            final Object byUuid = readField(lookup, lookup.getClass(), new String[] { "byUuid", "f_156808_" });
            boolean touched = false;
            if (byId instanceof Map<?, ?> map) {
                touched |= map.remove(entity.getId()) != null;
            }
            if (byUuid instanceof Map<?, ?> map) {
                touched |= map.remove(entity.getUUID()) != null;
            }
            final Object knownUuids = readField(manager, manager.getClass(),
                    new String[] { "knownUuids", "f_157491_" });
            if (knownUuids instanceof java.util.Set<?> set) {
                @SuppressWarnings("unchecked")
                final java.util.Set<Object> raw = (java.util.Set<Object>) set;
                touched |= raw.remove(entity.getUUID());
            }
            logPurge("purge_l5_detach id=" + entity.getId() + " uuid=" + entity.getUUID()
                    + " byId=" + (byId != null) + " byUuid=" + (byUuid != null)
                    + " knownUuids=" + (knownUuids != null) + " touched=" + touched);
            return touched;
        } catch (Throwable t) {
            logPurge("purge_l5_threw id=" + entity.getId() + " err=" + t);
            return false;
        }
    }

    /** 按候选名字列表反射读一个字段（读不到返回 null；每次都尝试，不缓存失败）。 */
    private static Object readField(Object target, Class<?> owner, String[] names) {
        Class<?> c = owner;
        while (c != null) {
            for (final String name : names) {
                try {
                    final Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {
                    // 试下一个名字 / 上一层
                } catch (IllegalAccessException e) {
                    // setAccessible 被拒（模块化/安全管理器）：当作读不到，由调用方走"不可用"分支
                    ShanhaiMod.LOGGER.warn(PREFIX + "purge 反射字段不可读 name=" + name
                            + " err=" + e);
                    return null;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // ═══════════════════════════════ L6 隔离 ═══════════════════════════════

    /**
     * 隔离（同一只被重申超过 20 次）：
     * <b>传送到极远坐标 ＋ noAi ＋ 隐身 ＋ 每 tick 重申 L3</b>。
     */
    private static void isolate(ServerLevel level, Entity entity, int reasserts) {
        try {
            entity.teleportTo(ISOLATE_X, ISOLATE_Y, ISOLATE_Z);
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
            }
            entity.setInvisible(true);
            entity.setRemoved(Entity.RemovalReason.DISCARDED);
            sendRemovePacket(level.getServer(), entity.getId());
            ISOLATED.put(entity.getUUID(), level);
            logPurge("purge_isolated entity=" + describe(entity) + " id=" + entity.getId()
                    + " reasserts=" + reasserts + " at=" + ISOLATE_X + "," + ISOLATE_Y + "," + ISOLATE_Z
                    + "（传送＋noAi＋隐身＋每 tick 重申 L3）");
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge 隔离失败（下一轮还会再试）：" + t);
        }
    }

    // ═══════════════════════════════ 工具 ═══════════════════════════════

    /** 一只实体一律跳过（玩家 ／ 玩家类型）。 */
    private static boolean isPlayerLike(Entity entity) {
        return entity instanceof Player || entity.getType() == EntityType.PLAYER;
    }

    /**
     * 一个世界里的全部实体<b>快照</b>（先拷出来，绝不边遍历边删）。
     *
     * <p>包内可见：{@link ShanhaiGuardSuppression} 每 5 tick 的复查走的就是这一份
     * —— 同一套遍历代码，不复制第二份。
     */
    static List<Entity> snapshot(ServerLevel level) {
        final List<Entity> out = new ArrayList<>();
        if (level == null) {
            return out;
        }
        try {
            for (final Entity e : level.getAllEntities()) {
                if (e != null) {
                    out.add(e);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge 取实体表抛异常（这一轮按已有的部分走）：" + t);
        }
        return out;
    }

    /** 实体的人话名（日志用；不依赖客户端语言）。 */
    private static String describe(Entity entity) {
        if (entity == null) {
            return "(null)";
        }
        final net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return (id == null ? "?" : id.toString()) + "#" + entity.getId();
    }

    // ═══════════════════════════════ 播报（左下角，同 tick 合并） ═══════════════════════════════

    private static void announceRemoved(Entity entity, String channel, ServerPlayer owner) {
        final String extra = ShanhaiGuardRemoval.announceRemoved("", channel);
        // announceRemoved("", ch) 的形状是「已抹除 （强制）」⇒ 从里面把后缀抠出来
        final int cut = extra.indexOf('（');
        final String suffix = cut >= 0 ? extra.substring(cut) : "";
        final String verb = ShanhaiGuardRemoval.CH_L6.equals(channel) ? "已隔离" : "已抹除";
        PENDING.add(new Line(verb, entity.getDisplayName(), suffix, describe(entity)));
    }

    /** 主动抹除里那两行（开始/结束）也走同一个缓冲，保证"同 tick 只发一行"。 */
    private static void announceLine(String verb, Component middle, String suffix, String plain,
                                     ServerPlayer owner) {
        PENDING.add(new Line(verb, middle, suffix, plain));
    }

    /**
     * 把本 tick 攒下的播报<b>合并成一行</b>发到左下角（action bar）。
     *
     * <p>合并口径：同一（动词＋后缀）的一组只发一条；{@code 1 只} 发
     * 「动词 名字 后缀」，{@code N 只} 发「动词 第一个名字 等 N 只 后缀」。
     *
     * <p>🆕 持续压制的「已压制 X ×N」也在这里发（它自己那一份 {@link #SUPPRESS_PENDING}，
     * <b>不</b>并进"等 N 只"那套分组 —— 规格要的形状就是「已压制 名字 ×N」）。
     */
    private static void flushAnnouncements(MinecraftServer server) {
        final List<Line> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        // 按（动词＋后缀）分组（LinkedHashMap 保序，读数稳定）
        final Map<String, List<Line>> groups = new LinkedHashMap<>();
        for (final Line line : batch) {
            final StringBuilder plain = new StringBuilder(line.verb).append(' ').append(line.plain);
            if (line.extra != null && !line.extra.isEmpty()) {
                plain.append(line.extra);
            }
            logPurge("purge_announce " + plain);
            groups.computeIfAbsent(line.verb + "|" + line.extra, k -> new ArrayList<>()).add(line);
        }
        final Map<String, Integer> suppress = new LinkedHashMap<>(SUPPRESS_PENDING);
        SUPPRESS_PENDING.clear();
        if (groups.isEmpty() && suppress.isEmpty()) {
            return;
        }
        // 收件人：本批第一条的 owner（服务端玩家）⇒ 没有就只进日志
        final ServerPlayer to = pickRecipient(server);
        if (to != null) {
            sendGrouped(to, groups);
        }
        // 🆕 压制播报的收件人：最近一次触发压制的那位（sticky，与 LAST_OWNER 不共用）
        if (!suppress.isEmpty()) {
            final ServerPlayer owner = ShanhaiGuardSuppression.owner(server);
            for (final Map.Entry<String, Integer> e : suppress.entrySet()) {
                final String text = ShanhaiGuardSuppressWindow.announce(e.getKey(), e.getValue());
                logPurge("purge_announce " + text);
                if (owner == null) {
                    continue;   // 没有收件人（控制台 / 已登出）⇒ 只进日志
                }
                try {
                    owner.displayClientMessage(Component.literal(text), true);   // 左下角
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn(PREFIX + "suppress 播报发送失败：" + t);
                }
            }
        }
    }

    /** 把一组「动词 名字 后缀」发到左下角（原来那段循环，原样搬进来，行为不变）。 */
    private static void sendGrouped(ServerPlayer to, Map<String, List<Line>> groups) {
        for (final Map.Entry<String, List<Line>> e : groups.entrySet()) {
            final List<Line> lines = e.getValue();
            final Line first = lines.get(0);
            final Component text;
            if (lines.size() == 1) {
                text = Component.literal(first.verb + " ").append(first.name)
                        .append(first.extra == null ? "" : first.extra);
            } else {
                text = Component.literal(first.verb + " ").append(first.name)
                        .append(" 等 " + lines.size() + " 只")
                        .append(first.extra == null ? "" : first.extra);
            }
            try {
                to.displayClientMessage(text, true);   // true = action bar（左下角）
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "purge 播报发送失败：" + t);
            }
        }
    }

    /**
     * 播报收件人：<b>最近一次触发者</b>（挨打的那位 / 执行命令的那位）。
     *
     * <p>为什么不在 {@link Line} 里带 owner：需求要的是"同一 tick 合并成一行"，
     * 一个 tick 里的触发者通常只有一个；多玩家同 tick 触发时按"先来先发"取第一个，
     * 其余在日志里（日志才是权威读数）。
     */
    private static ServerPlayer LAST_OWNER = null;

    private static ServerPlayer pickRecipient(MinecraftServer server) {
        final ServerPlayer owner = LAST_OWNER;
        LAST_OWNER = null;
        if (owner != null && !owner.hasDisconnected()) {
            return owner;
        }
        return null;
    }

    /** 记下"这一批播报该给谁"（由触发点调用）。 */
    public static void noteOwner(ServerPlayer owner) {
        if (owner != null) {
            LAST_OWNER = owner;
        }
    }

    private static void logCounter(String why) {
        final String line = LOG.admit(KIND_COUNTER, System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.info(line + " ／ " + why);
        }
    }

    /** 抹除相关的日志（走自己的闸门；同一秒最多一行，带末次原因）。 */
    private static void logPurge(String why) {
        final String line = LOG.admit(KIND_PURGE, System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.warn(line + " ｜ 末次：" + why);
        } else {
            // 🔴 闸门压掉时也必须留下"末次原因"的可查痕迹（诊断行只进 debug 会等于没有）
            ShanhaiMod.LOGGER.debug(PREFIX + "purge（闸门合并中）：" + why);
        }
    }
}
