package com.shanhai.add.guard;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.common.guard.ShanhaiGuardCallerTrace;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardRemoval;
import com.shanhai.common.guard.ShanhaiGuardSwitches;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Enemy;
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
 *                       🆕 2026-11-08：改走 <b>安全清场通道（HurtOnly）</b> {@link #purgeAll} ——
 *                          "先写 0.1 血骗过 isDeadOrDying 前置 ＋ 原版 hurt"，<b>永不</b>
 *                          setRemoved/discard/改 Level 容器（旧阶梯对"替身 setRemoved 空实现"的
 *                          实体会把对方内部表搞成不一致 ⇒ 崩服；旧实现见 {@link #purgeLegacy}，
 *                          只在 {@code /shanhai_add purge legacy} 这种显式参数下才走）。
 * </pre>
 *
 * <h2>🔴 2026-11-01 收紧：只抹"真的造成过伤害的攻击者"</h2>
 * 现场一局 191 行「拦截 抹除」、186 次播报（每秒一行），82 只发光鱿鱼 / 18 只鱿鱼被抹 —— 全是没打中的被动生物。
 * 现在触发 A 多过三道闸门（判据本身抽到了纯核 {@link ShanhaiPurgeAdmission#judge}，离线逐条可验）：
 * <pre>
 *   passive      攻击者是 Mob、没把受害者当目标、也不是敌对实体 ⇒ 不抹（专治发光鱿鱼/鱿鱼）
 *   zero_amount  {@code event.getAmount() <= 0} ⇒ 这一下本来就不疼 ⇒ 不抹
 *   dup          这一只已经在排队 / 这个类型已经在压制窗口里 ⇒ 不重复
 *   其余不满足的也只记一行 {@code skip reason=…}（每 5 秒最多一行，见 {@link ShanhaiPurgeAdmission.SkipLog}）
 * </pre>
 * ⚠️ 口径说明：<b>不能</b>拿"是否真的掉了血"当判据 —— 守卫会把伤害全部取消，那样永远都不会抹；
 * 近似成「amount &gt; 0 且（攻击者把受害者当目标 或 攻击者是敌对实体）」，代价写进了
 * {@code temp\purge-tune\报告.md}。
 *
 * <h2>🔴 播报与条数上限（同一条修法）</h2>
 * <pre>
 *   purge_announce  由「每秒一行」改成【每 5 秒最多一行】，行内按类型合并 {@code 类型×N}
 *                   （窗口 = {@link ShanhaiPurgeAdmission#ANNOUNCE_WINDOW_MS}，写死在一个常量里）
 *   条数上限        同一类型每 {@value ShanhaiPurgeAdmission#QUOTA_WINDOW_MS} 毫秒最多抹
 *                   {@value ShanhaiPurgeAdmission#MAX_PER_TYPE_PER_WINDOW} 只，超出记 {@code capped=<n>}
 *                   —— 两条自动路径（挨打反击排队 ＋ 持续压制扫描）共用同一份配额。
 *                   ⚠️ 手动 {@code /shanhai purge} 是<b>显式清场</b>（"范围内所有实体"），不吃这个上限。
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
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID)
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

    /** 🆕 已排队、还没处理的攻击者（UUID）：同一只不会因为一秒里连打几下而重复排队。 */
    private static final Set<UUID> QUEUED_IDS = new HashSet<>();

    /**
     * 🆕 <b>每条额度</b>：同一类型每 {@value ShanhaiPurgeAdmission#QUOTA_WINDOW_MS} 毫秒最多抹
     * {@value ShanhaiPurgeAdmission#MAX_PER_TYPE_PER_WINDOW} 只。
     *
     * <p>🔴 两条自动路径<b>共用这一份</b>：{@link #drainQueue()}（挨打反击）与
     * {@code ShanhaiGuardSuppression#scanAndRemove}（持续压制）—— 否则"两条路各 4 只"会变成 8 只。
     */
    private static final ShanhaiPurgeAdmission.Quota<EntityType<?>> QUOTA =
            new ShanhaiPurgeAdmission.Quota<>();

    /** 🆕 播报合并器：{@code purge_announce} 每 5 秒最多一行、行内按类型合并（原来每秒一行）。 */
    private static final ShanhaiPurgeAdmission.Announcer ANNOUNCE = new ShanhaiPurgeAdmission.Announcer();

    /** 🆕 跳过日志（{@code skip reason=…}）：限流，每 5 秒最多一行。 */
    private static final ShanhaiPurgeAdmission.SkipLog SKIP = new ShanhaiPurgeAdmission.SkipLog();

    /** 🆕 跨 tick 攒到"5 秒窗口"的台词（左下角那一批；到点一次发完）。 */
    private static final List<Line> ANNOUNCE_LINES = new ArrayList<>();

    /**
     * 🆕 <b>安全清场通道（HurtOnly）的 5 秒日志窗口</b>：{@code purge_kill swept=… killed=… stuck=… skipped=…}
     * 每 5 秒最多一行，逐类型合并（沿用播报窗口，见 {@link ShanhaiPurgeAdmission#ANNOUNCE_WINDOW_MS}）。
     */
    private static final ShanhaiPurgeAdmission.KillWindow KILL_WINDOW =
            new ShanhaiPurgeAdmission.KillWindow();

    // ── 🆕 持续压制（B 件）：节拍、闸门、待播汇总 ──

    /** 当前看到的服务器实例（换实例 = 新的一局 ⇒ 取证计数与压制表都清零）。 */
    private static MinecraftServer SERVER_SEEN = null;

    /** 5 tick 节拍（自己数，不依赖可能被模组改写语义的 {@code getTickCount}）。 */
    private static int SUPPRESS_TICK = 0;

    /** 压制日志/播报的"每秒最多一次"闸门（毫秒时间戳）。 */
    private static long LAST_SUPPRESS_FLUSH = 0L;

    /** 待播的压制汇总（人话名字 ⇒ 这一秒又抹掉几只）。 */
    private static final Map<String, Integer> SUPPRESS_PENDING = new LinkedHashMap<>();

    /** 一条待播报（verb ＋ 名字组件 ＋ 后缀 ＋ 用于 `类型×N` 合并的人话类型名）。 */
    private static final class Line {

        final String verb;
        final Component name;
        final String extra;
        final String plain;
        /** 🆕 播报合并用的类型名（"发光鱿鱼" 这种；`purge_announce` 行内按它合并成 `类型×N`）。 */
        final String typeName;

        Line(String verb, Component name, String extra, String plain, String typeName) {
            this.verb = verb;
            this.name = name;
            this.extra = extra;
            this.plain = plain;
            this.typeName = typeName;
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

    /** 一次主动抹除的读数（两条通道共用：{@link #mode} 区分 {@code hurt} / {@code legacy}）。 */
    public static final class Result {

        /** 🆕 走的哪条通道：{@code hurt}（安全清场，默认）／{@code legacy}（旧的逐级阶梯）。 */
        public String mode = "hurt";
        /** 🆕 这一次是不是"只数不抹"。 */
        public boolean dryRun;
        /** 扫到多少只（不含玩家）。 */
        public int scanned;
        /** 抹掉多少只（两条通道都填：{@code hurt} 通道 = {@link #killed}）。 */
        public int removed;
        /** 跳过多少个玩家。 */
        public int skippedPlayers;
        /** 逐级解决的只数（键 = purge_channel；<b>只有 legacy 通道会填</b>）。 */
        public final Map<String, Integer> channels = new LinkedHashMap<>();
        /** 这一轮没解决（进了 L6 重申表）的只数（<b>只有 legacy 通道会填</b>）。 */
        public int unresolved;

        // ── 🆕 安全清场通道（HurtOnly）的三个读数 ──

        /** 复查确认没了的只数。 */
        public int killed;
        /** 打完还在（{@code stuck}）的只数 —— 留在原地，不处理。 */
        public int stuck;
        /** 跳过（不是生物 / 拿不到血）的只数，日志里是 {@code reason=not_living}。 */
        public int notLivingSkipped;

        /** 一行读数（命令回显/日志）。 */
        public String line() {
            if ("legacy".equals(mode)) {
                return "mode=legacy dry_run=" + dryRun + " scanned=" + scanned + " removed=" + removed
                        + " skipped_players=" + skippedPlayers
                        + " unresolved=" + unresolved + " channels=" + channels;
            }
            return "mode=hurt dry_run=" + dryRun + " scanned=" + scanned + " killed=" + killed
                    + " stuck=" + stuck + " skipped_not_living=" + notLivingSkipped
                    + " skipped_players=" + skippedPlayers;
        }
    }

    // ═══════════════════════════════ 触发 A：挨打反击 ═══════════════════════════════

    /**
     * 受保护玩家受到攻击 ⇒ 取消伤害之后反手抹掉攻击者本人。
     *
     * <p>🔴 {@code receiveCanceled = true} 是必须的（理由见类注释触发 A 那一段）。
     *
     * <p>🆕 这里<b>只做取值</b>：把这一下摊成 {@link ShanhaiPurgeAdmission.Attack}，要不要抹交给纯函数
     * {@link ShanhaiPurgeAdmission#judge}（离线可逐条验）。不满足的只记一行 {@code skip reason=…}（限流）。
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
            final Entity attacker = event.getSource().getEntity();       // 真正动手的那个
            final Entity direct = event.getSource().getDirectEntity();   // 直接命中的那个（可能是箭）
            final ShanhaiPurgeAdmission.Attack attack =
                    describeAttack(victim, attacker, direct, event.getAmount());
            final ShanhaiPurgeAdmission.Reason verdict = ShanhaiPurgeAdmission.judge(attack);
            if (verdict != ShanhaiPurgeAdmission.Reason.OK) {
                // 🔴 环境伤害（没攻击者）与非受保护玩家仍然完全静默：它们每 tick 都在发生，记了就是新的刷屏
                if (verdict.logsSkip()) {
                    noteSkip(verdict, victim, attacker, direct);
                }
                return;
            }
            final ServerLevel level = victim.serverLevel();
            if (level == null) {
                return;   // 理论上不会发生；无论如何都不拿 null 去排队
            }
            // 排到 tick 末再删：现在是伤害流程中间，就地删实体容易踩到各种遍历
            QUEUED.add(new Queued(level, attacker, victim, "发生攻击（LivingAttackEvent）"));
            QUEUED_IDS.add(attacker.getUUID());   // 🆕 去重：同一只一秒里连打几下也只排一次
            noteOwner(victim);   // 本 tick 的播报发给挨打的那位
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge 反击判据抛异常（原样放行）：" + t);
        }
    }

    /**
     * 把一个 {@code LivingAttackEvent} 摊成纯判据的输入（<b>这里只取值，不做决定</b>）。
     *
     * <p>「敌对实体」的换算（纯核只收一个 boolean）：{@code instanceof Enemy}
     * （= {@code Monster} / 末影龙 / 凋灵 / 按惯例实现 {@code Enemy} 的模组敌对生物）
     * 或 {@code getType().getCategory() == MobCategory.MONSTER} —— 后者把"注册成 MONSTER 类目"的模组生物也算上。
     */
    private static ShanhaiPurgeAdmission.Attack describeAttack(ServerPlayer victim, Entity attacker,
                                                               Entity direct, float amount) {
        final long now = System.currentTimeMillis();
        final ShanhaiPurgeAdmission.Attack attack = new ShanhaiPurgeAdmission.Attack();
        attack.victimGuarded = ShanhaiGuardEvents.isGuarded(victim);
        attack.hasAttacker = attacker != null;
        attack.directIsAttacker = direct == null || direct == attacker;
        attack.attackerIsPlayer = attacker instanceof Player;
        attack.attackerIsLiving = attacker instanceof LivingEntity;
        attack.amount = amount;
        attack.sameLevel = attacker != null && victim.serverLevel() == attacker.level();
        attack.attackerIsMob = attacker instanceof Mob;
        attack.mobTargetsVictim = attacker instanceof Mob mob && mob.getTarget() == victim;
        attack.attackerIsHostile = isHostile(attacker);
        attack.alreadyQueued = attacker != null && QUEUED_IDS.contains(attacker.getUUID());
        attack.typeSuppressed = attacker != null
                && ShanhaiGuardSuppression.isSuppressed(attacker.getType(), now);
        return attack;
    }

    /** 敌对实体的判据（口径见 {@link #describeAttack}）：{@code Enemy} 或注册成 {@code MONSTER} 类目。 */
    private static boolean isHostile(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof Enemy) {
            return true;
        }
        try {
            return entity.getType().getCategory() == MobCategory.MONSTER;
        } catch (Throwable t) {
            // 类目读不到（极早期的实体）⇒ 当作"不敌对"：只会更保守地跳过，不会多抹
            return false;
        }
    }

    /** 记一次跳过（进限流器；{@code flushSkipLog()} 每 5 秒最多落一行）。 */
    private static void noteSkip(ShanhaiPurgeAdmission.Reason reason, ServerPlayer victim,
                                 Entity attacker, Entity direct) {
        final StringBuilder detail = new StringBuilder("victim=").append(describe(victim));
        if (attacker != null) {
            detail.append(" attacker=").append(describe(attacker));
        }
        if (direct != null && direct != attacker) {
            detail.append(" direct=").append(describe(direct));
        }
        SKIP.note(reason, detail.toString(), System.currentTimeMillis());
    }

    /** 跳过日志到点就落一行（{@code pass=…} 那种限流：没到窗口一个字都不打）。 */
    private static void flushSkipLog() {
        final String line = SKIP.takeLine(System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.info(line);
        }
    }

    /**
     * 🆕 扣一次抹除额度（同一类型每 {@value ShanhaiPurgeAdmission#QUOTA_WINDOW_MS} 毫秒最多
     * {@value ShanhaiPurgeAdmission#MAX_PER_TYPE_PER_WINDOW} 只）。
     *
     * <p>包内可见：{@code ShanhaiGuardSuppression#scanAndRemove}（持续压制）与 {@link #drainQueue}（挨打反击）
     * 走的是<b>同一份</b>配额 —— 这样"两条路各自的预算"不会叠成两倍。
     *
     * @return {@code false} = 这一秒这个类型已经抹满（这一只不抹，计数进 {@code capped=…}）
     */
    static boolean takeQuota(EntityType<?> type, long nowMillis) {
        return QUOTA.tryAcquire(type, nowMillis);
    }

    /** 退还一次额度（阶梯这一轮其实没抹掉时用：只有"真抹掉"才占额度）。 */
    static void refundQuota(EntityType<?> type, long nowMillis) {
        QUOTA.refund(type, nowMillis);
    }

    // ═══════════════════════════════ tick 末：排队 + 播报 + 重申 ═══════════════════════════════

    /**
     * 服务端 tick 末：
     * <pre>
     *   ① 处理攻击端排队的那几次抹除（吃 {@link ShanhaiPurgeAdmission.Quota} 的每条额度）
     *   ② 给已隔离的那几只重申 L3（每 tick）
     *   ③ 跳过日志到点落一行（{@code skip reason=…}，限流）
     *   ④ 播报：🔴 每 5 秒最多一行（{@code purge_announce}，行内按类型合并），左下角同窗口一起发
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
            flushSkipLog();
            flushKillReport();   // 🆕 安全清场的 `purge_kill` 行：同一个 5 秒窗口
            flushAnnouncements(server);
        }
    }

    /** 🆕 安全清场通道的读数到点就落一行（{@code purge_kill swept=… killed=… stuck=… skipped=…}）。 */
    private static void flushKillReport() {
        final String line = KILL_WINDOW.takeLine(System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.warn(line);
        }
    }

    /** 处理攻击端排队的那些。 */
    private static void drainQueue() {
        if (QUEUED.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final List<Queued> batch = new ArrayList<>(QUEUED);
        QUEUED.clear();
        for (final Queued q : batch) {
            final String name = describe(q.entity);
            // 🆕 去重表在这里摘掉：这一只已经离开排队（下一次攻击还能再排，不会永久拉黑）
            QUEUED_IDS.remove(q.entity.getUUID());
            final EntityType<?> type = q.entity.getType();
            // 🆕 每次抹除的条数上限：同一类型每 1 秒最多 4 只（超出由配额表记 capped）
            if (!takeQuota(type, now)) {
                continue;
            }
            final String channel = ladder(q.level, q.entity, q.owner);
            if (channel == null) {
                refundQuota(type, now);   // 这一轮没抹掉 ⇒ 额度退回来（占额度的只算"真抹掉"的）
            }
            // 🆕 持续压制·来源②：挨打反击抹掉的那个攻击者的类型 ⇒ 上压制表
            //    （不论这一轮有没有解决都记：没解决的那一只恰恰最需要每 5 tick 再抹一次）
            ShanhaiGuardSuppression.noteRemoved(type, q.owner, "counter");
            final String line = LOG.admit(KIND_COUNTER, now);
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
     *   ③ {@code suppress type=…} 这一行日志仍按"每秒最多一次"合并（数字是这一秒的总和）；
     *      左下角那句「已压制 X ×N」改成跟 {@code purge_announce} 的 <b>5 秒</b>播报窗口一起发
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
            resetPurgeTuning();   // 🆕 新的一局：配额 / 去重表 / 播报窗口 / 跳过限流 全部清零
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
            // 🆕 也进"5 秒一行"的 `purge_announce`（原来它自己是一秒一句 ⇒ 一秒一行刷屏）
            ANNOUNCE.note(stat.name, stat.removed, now);
        }
    }

    /** 🆕 换世界（新的一局）时把这一套调参状态清零：配额 / 去重 / 播报窗口 / 跳过限流 / 清场读数。 */
    private static void resetPurgeTuning() {
        QUOTA.clear();
        QUEUED_IDS.clear();
        ANNOUNCE.reset();
        SKIP.reset();
        ANNOUNCE_LINES.clear();
        KILL_WINDOW.reset();
    }

    // ═══════════════════════════════ 触发 B：主动抹除 ═══════════════════════════════

    /**
     * 🔴 <b>主动触发：安全清场（HurtOnly）</b> —— {@code /shanhai purge}、全息板第 1 格「清除实体」、
     * {@code /shanhai_add purge} <b>都走这里</b>（本体那条经 {@code com.shanhai.api.ShanhaiAdd#purgeAll} 进来）。
     *
     * <h2>为什么换掉旧的逐级阶梯</h2>
     * 旧路径是"逐维度枚举 ＋ {@code setRemoved/discard}"。对<b>自带静态登记表</b>的实体
     * （真身不是 {@code Entity}、替身的 {@code setRemoved} 是<b>空实现</b>的那一类）不但杀不掉，
     * 还会把它内部表搞成不一致 ⇒ 它自己 tick 时 {@code NoSuchElementException} <b>崩服</b>（2026-11-08 实际崩过一次）。
     *
     * <h2>新通道只走原版伤害路径</h2>
     * <pre>
     *   ① 不是 LivingEntity / 拿不到血            ⇒ 跳过（skip reason=not_living）
     *   ② 当前血 &lt;=0 或 NaN                     ⇒ 先把同步血量写成 0.1（骗过 isDeadOrDying 前置）；
     *                                               正常血量的<b>不要动</b>
     *   ③ 原版 hurt(玩家/通用伤害源, damageFor(maxHealth))
     *      （伤害上界依据见 {@link ShanhaiPurgeAdmission#SAFE_DAMAGE_MAX}；不是 Float.MAX_VALUE）
     *   ④ 🔴 永不 setRemoved / discard / 直接改 Level 容器 —— 移除交给原版 die()
     *   ⑤ 复查 isRemoved()||isDeadOrDying()        ⇒ killed；否则 stuck=类型，<b>留在原地不处理</b>
     * </pre>
     * 日志：{@code purge_kill window_ms=5000 swept=… killed=… stuck=… skipped=… reason=…}
     * ＋逐类型 {@code 类型×N}，走同一个 5 秒窗口（{@link ShanhaiPurgeAdmission.KillWindow}）。
     *
     * <p>⚠️ 这条通道<b>不</b>把类型记进持续压制表：压制扫描走的仍是旧的 L1→L6 阶梯（会 setRemoved/discard），
     * 让它去碰同一批实体就等于刚避开的崩服路径又接回来。旧行为原样保留在 {@link #purgeLegacy}（显式参数才走）。
     *
     * <p>⚠️ 这条是<b>显式清场</b>，所以<b>不吃</b> {@link ShanhaiPurgeAdmission#MAX_PER_TYPE_PER_WINDOW}
     * 那个"每类型每秒 4 只"的上限（那是给两条自动路径的），日志里用 {@code quota=exempt(manual)} 标出。
     *
     * @param owner 播报收件人（可为 {@code null}：控制台执行时只进日志）
     * @param dryRun true = 只数不抹（{@code /shanhai purge count}）
     */
    public static Result purgeAll(MinecraftServer server, ServerPlayer owner, boolean dryRun) {
        final Result result = new Result();
        result.mode = "hurt";
        result.dryRun = dryRun;
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
        logPurge("purge_start via=hurt scope=all_levels levels=" + levels.size()
                + " total_entities=" + targets.size() + " dry_run=" + dryRun
                + " quota=exempt(manual)");
        announceLine("抹除开始：范围内", Component.literal(String.valueOf(targets.size())), " 只", null, owner);
        if (dryRun) {
            // 只数不抹：按"要不要跳过"分类，一只都不动
            int candidates = 0;
            int skipped = 0;
            int players = 0;
            for (final Entity e : targets) {
                if (e == null || e.isRemoved()) {
                    continue;
                }
                if (isPlayerLike(e)) {
                    players++;
                } else if (canUseHurt(e)) {
                    candidates++;
                } else {
                    skipped++;
                }
            }
            result.scanned = candidates;
            result.notLivingSkipped = skipped;
            result.skippedPlayers = players;
            logPurge("purge_dry_run " + result.line());
            return result;
        }
        final ShanhaiPurgeAdmission.KillTally tally = new ShanhaiPurgeAdmission.KillTally();
        for (final Entity entity : targets) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if (isPlayerLike(entity)) {
                tally.notePlayers(1);   // 玩家永远不是候选（含自己）
                continue;
            }
            final ServerLevel level = (ServerLevel) entity.level();
            tally.note(typeIdOf(entity), killOne(level, entity, owner));
        }
        // ③ 读数（单一来源 = tally；swept 由 killed+stuck+skipped 派生，绝不允许对不上）
        result.scanned = tally.swept();
        result.killed = tally.killed();
        result.stuck = tally.stuck();
        result.notLivingSkipped = tally.skipped();
        result.skippedPlayers = tally.skippedPlayers();
        result.removed = tally.killed();
        KILL_WINDOW.add(tally);   // 走 5 秒窗口，和播报同一套
        logPurge("purge_done " + result.line());
        announceLine("已抹除", Component.literal(String.valueOf(result.killed)),
                " 只" + (result.stuck > 0 ? "，删不掉 " + result.stuck + " 只" : "")
                        + "，跳过玩家 " + result.skippedPlayers + " 个", null, owner);
        return result;
    }

    /**
     * 🔴 <b>安全清场通道的单只</b>（HurtOnly）：<b>永不</b> setRemoved / discard / 改 Level 容器。
     *
     * <p>只做四件事：读血 →（必要时）写 0.1 → 原版 hurt → 复查。复查不过就记 {@code stuck}，
     * <b>留在原地不处理</b>（那种"替身 hurt 恒 false"的实体，任何强删都可能把对方内部表搞崩）。
     */
    private static ShanhaiPurgeAdmission.KillOutcome killOne(ServerLevel level, Entity entity,
                                                             ServerPlayer owner) {
        if (!(entity instanceof LivingEntity living)) {
            return ShanhaiPurgeAdmission.KillOutcome.SKIPPED_NOT_LIVING;
        }
        final float health = readHealth(living);
        if (Float.isNaN(health)) {
            logPurge("purge_kill_skip reason=not_living type=" + typeIdOf(entity)
                    + " id=" + entity.getId() + "（拿不到血）");
            return ShanhaiPurgeAdmission.KillOutcome.SKIPPED_NOT_LIVING;
        }
        if (ShanhaiPurgeAdmission.shouldPrimeHealth(health)) {
            // 只在"已经 <=0 / NaN"时写：让 isDeadOrDying() 前置放行（它不是伤害，只是把场景改成"还有一口气"）
            try {
                living.setHealth(ShanhaiPurgeAdmission.PRIME_HEALTH);
            } catch (Throwable t) {
                logPurge("purge_kill_prime_threw type=" + typeIdOf(entity) + " id=" + entity.getId()
                        + " err=" + t);
            }
        }
        final float damage = ShanhaiPurgeAdmission.damageFor(readMaxHealth(living));
        // 🔴 清场语义：不掉东西（旧的 discard 也不掉）。用两块原版 API 把"死亡副产品"按住：
        //    captureDrops(空表) ⇒ 掉落物进一个没人要的列表（地上不会下装备雨）；
        //    skipDropExperience() ⇒ 不刷经验球（它只是置一个 flag，见 1.20.1 字节码）。
        Collection<ItemEntity> capturedBefore = null;
        try {
            capturedBefore = living.captureDrops(new ArrayList<>());
        } catch (Throwable t) {
            logPurge("purge_kill_capture_threw type=" + typeIdOf(entity) + " err=" + t);
        }
        try {
            living.skipDropExperience();
        } catch (Throwable t) {
            logPurge("purge_kill_xp_threw type=" + typeIdOf(entity) + " err=" + t);
        }
        boolean hurt = false;
        try {
            hurt = living.hurt(killSource(level, owner), damage);
        } catch (Throwable t) {
            logPurge("purge_kill_hurt_threw type=" + typeIdOf(entity) + " id=" + entity.getId()
                    + " damage=" + damage + " err=" + t);
        }
        final boolean gone = ShanhaiPurgeAdmission.isGone(safeRemoved(entity), safeDeadOrDying(living));
        if (!gone) {
            // 没杀掉 ⇒ 把掉落表还原（别把"这一只以后自然死亡也不掉东西"的副作用留在它身上）
            try {
                living.captureDrops(capturedBefore);
            } catch (Throwable ignored) {
                // 还原失败也不影响主流程：它只是"这只以后死亡会少掉落"
            }
            logPurge("purge_kill_stuck type=" + typeIdOf(entity) + " id=" + entity.getId()
                    + " hurt=" + hurt + " damage=" + damage + "（留在原地，不处理）");
            return ShanhaiPurgeAdmission.KillOutcome.STUCK;
        }
        return ShanhaiPurgeAdmission.KillOutcome.KILLED;
    }

    /**
     * 伤害源：有人执行 ⇒ 原版<b>玩家攻击</b>（与秘法涡流同款，<b>不带</b>任何 bypass 标签）；
     * 控制台 ⇒ 通用伤害。
     */
    private static DamageSource killSource(ServerLevel level, ServerPlayer owner) {
        if (owner != null && !owner.hasDisconnected()) {
            return level.damageSources().playerAttack(owner);
        }
        return level.damageSources().generic();
    }

    /** 这一只能不能走 hurt 通道（是生物 ＋ 读得到血）。 */
    static boolean canUseHurt(Entity entity) {
        return entity instanceof LivingEntity living && !Float.isNaN(readHealth(living));
    }

    private static float readHealth(LivingEntity living) {
        try {
            return living.getHealth();
        } catch (Throwable t) {
            return Float.NaN;   // 读不到血 ⇒ 当作"不是可杀生物"（skip reason=not_living）
        }
    }

    private static float readMaxHealth(LivingEntity living) {
        try {
            return living.getMaxHealth();
        } catch (Throwable t) {
            return Float.NaN;   // 纯核见到 NaN 会用安全上界
        }
    }

    private static boolean safeRemoved(Entity entity) {
        try {
            return entity.isRemoved();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeDeadOrDying(LivingEntity living) {
        try {
            return living.isDeadOrDying();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一只实体的人话类型 id（注册名；读数/日志用，不依赖客户端语言）。 */
    private static String typeIdOf(Entity entity) {
        return entity == null ? "?" : ShanhaiGuardSuppression.typeId(entity.getType());
    }

    /**
     * 🆕 <b>旧通道（保留，但默认不走）</b>：逐维度枚举 ＋ L1→L6 阶梯（{@code setRemoved/discard/反射摘账/隔离}）。
     *
     * <p>🔴 <b>可能造成第三方崩</b>：对"自带静态登记表、替身 setRemoved 是空实现"的实体
     * （真身不是实体、替身 {@code setRemoved} 空实现的那一类），旧阶梯会把它的内部表搞成不一致
     * ⇒ 它自己 tick 时 {@code NoSuchElementException} 崩服。
     * 只有在显式参数下才允许走（{@code /shanhai_add purge legacy}）。
     */
    public static Result purgeLegacy(MinecraftServer server, ServerPlayer owner, boolean dryRun) {
        final Result result = new Result();
        result.mode = "legacy";
        result.dryRun = dryRun;
        if (server == null) {
            return result;
        }
        final List<ServerLevel> levels = new ArrayList<>();
        final List<Entity> targets = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            levels.add(level);
            targets.addAll(snapshot(level));
        }
        ShanhaiMod.LOGGER.warn(PREFIX + "purge_legacy_removal start levels=" + levels.size()
                + " total_entities=" + targets.size() + " dry_run=" + dryRun
                + "（旧阶梯会 setRemoved/discard ⇒ 可能造成第三方崩；默认不走这条）");
        logPurge("purge_legacy_removal start scope=all_levels levels=" + levels.size()
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
            logPurge("purge_legacy_removal dry_run " + result.line());
            return result;
        }
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
            result.killed = result.removed;
            result.channels.merge(channel, 1, Integer::sum);
            // 持续压制·来源①：旧通道真抹掉的每个实体的类型 ⇒ 上压制表（旧行为，原样保留）
            ShanhaiGuardSuppression.noteRemoved(entity.getType(), owner, "purge");
            logPurge(ShanhaiGuardRemoval.purgeLogLine(channel, name, entity.getId(), true));
        }
        logPurge("purge_legacy_removal done " + result.line());
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

    // ═══════════════════════════════ 播报（左下角 ＋ 每 5 秒一行） ═══════════════════════════════

    private static void announceRemoved(Entity entity, String channel, ServerPlayer owner) {
        final String extra = ShanhaiGuardRemoval.announceRemoved("", channel);
        // announceRemoved("", ch) 的形状是「已抹除 （强制）」⇒ 从里面把后缀抠出来
        final int cut = extra.indexOf('（');
        final String suffix = cut >= 0 ? extra.substring(cut) : "";
        final String verb = ShanhaiGuardRemoval.CH_L6.equals(channel) ? "已隔离" : "已抹除";
        PENDING.add(new Line(verb, entity.getDisplayName(), suffix, describe(entity), displayNameOf(entity)));
    }

    /** 主动抹除里那两行（开始/结束）也走同一个缓冲，保证"同窗口只发一行"。 */
    private static void announceLine(String verb, Component middle, String suffix, String plain,
                                     ServerPlayer owner) {
        PENDING.add(new Line(verb, middle, suffix, plain, verb));
    }

    /** 实体的人话类型名（`类型×N` 合并用；读不到就退回注册名+id，绝不编造）。 */
    private static String displayNameOf(Entity entity) {
        try {
            final String name = entity.getDisplayName().getString();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
            // 显示名读不到 ⇒ 走下面的兜底
        }
        return describe(entity);
    }

    /**
     * 🔴 <b>播报出口：每 5 秒最多一行</b>（原来这里是"每秒一行"，一局 186 次播报把日志刷满）。
     *
     * <p>口径：
     * <pre>
     *   ① 本 tick 攒的每一句 ⇒ 只<b>并进窗口</b>（{@link ShanhaiPurgeAdmission.Announcer}），不发
     *   ② 窗口没到 ⇒ 一个字都不发（日志与左下角都攒着）
     *   ③ 到点 ⇒ 日志只落<b>一行</b> {@code purge_announce window_ms=5000 total=… ｜ 类型：A×34 B×5}；
     *      被条数上限挡过的再落一行 {@code purge_capped … capped=<n>}；左下角同一窗口一次发完
     * </pre>
     *
     * <p>合并口径（左下角）：同一（动词＋后缀）的一组只发一条；{@code 1 只} 发
     * 「动词 名字 后缀」，{@code N 只} 发「动词 第一个名字 等 N 只 后缀」。
     * 持续压制的「已压制 X ×N」仍然是自己的那一份 {@link #SUPPRESS_PENDING}（形状不变），
     * 只是从"每秒一句"跟着这个窗口一起发。
     */
    private static void flushAnnouncements(MinecraftServer server) {
        final long now = System.currentTimeMillis();
        // ① 本 tick 攒的台词 ⇒ 进 5 秒窗口（这一步不发任何东西）
        final List<Line> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        for (final Line line : batch) {
            ANNOUNCE_LINES.add(line);
            ANNOUNCE.note(line.typeName, 1, now);
        }
        // ② 窗口没到 ⇒ 全都攒着（capped 也一起攒，保证它也"每 5 秒最多一行"）
        if (!ANNOUNCE.due(now)) {
            return;
        }
        final ShanhaiPurgeAdmission.Batch announced = ANNOUNCE.drain(now);
        final Map<EntityType<?>, Integer> capped = QUOTA.drainCapped(now);
        if (announced != null) {
            ShanhaiMod.LOGGER.warn(announced.logLine());
        }
        if (!capped.isEmpty()) {
            ShanhaiMod.LOGGER.warn(ShanhaiPurgeAdmission.cappedLogLine(
                    ShanhaiPurgeAdmission.ANNOUNCE_WINDOW_MS, ShanhaiPurgeAdmission.QUOTA_WINDOW_MS,
                    typeIdCounts(capped)));
        }
        if (announced == null && capped.isEmpty()) {
            return;   // 这一窗口真的什么都没发生 ⇒ 连左下角都不用发
        }
        // ③ 左下角（同一个窗口，一次发完）
        final ServerPlayer to = pickRecipient(server);
        if (to != null && !ANNOUNCE_LINES.isEmpty()) {
            sendGrouped(to, groupLines(ANNOUNCE_LINES));
        }
        ANNOUNCE_LINES.clear();
        final Map<String, Integer> suppress = new LinkedHashMap<>(SUPPRESS_PENDING);
        SUPPRESS_PENDING.clear();
        if (suppress.isEmpty()) {
            return;
        }
        // 压制播报的收件人：最近一次触发压制的那位（sticky，与 LAST_OWNER 不共用）
        final ServerPlayer owner = ShanhaiGuardSuppression.owner(server);
        for (final Map.Entry<String, Integer> e : suppress.entrySet()) {
            final String text = ShanhaiGuardSuppressWindow.announce(e.getKey(), e.getValue());
            if (owner == null) {
                continue;   // 没有收件人（控制台 / 已登出）⇒ 只进上面那一行日志
            }
            try {
                owner.displayClientMessage(Component.literal(text), true);   // 左下角
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "suppress 播报发送失败：" + t);
            }
        }
    }

    /** 按（动词＋后缀）分组（LinkedHashMap 保序，读数稳定）。 */
    private static Map<String, List<Line>> groupLines(List<Line> lines) {
        final Map<String, List<Line>> groups = new LinkedHashMap<>();
        for (final Line line : lines) {
            groups.computeIfAbsent(line.verb + "|" + line.extra, k -> new ArrayList<>()).add(line);
        }
        return groups;
    }

    /** {@code Map<EntityType, 次数>} ⇒ {@code Map<注册名, 次数>}（日志行的 `类型×N` 用注册名，稳定可 grep）。 */
    private static Map<String, Integer> typeIdCounts(Map<EntityType<?>, Integer> byType) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final Map.Entry<EntityType<?>, Integer> e : byType.entrySet()) {
            out.merge(ShanhaiGuardSuppression.typeId(e.getKey()), e.getValue(), Integer::sum);
        }
        return out;
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

    /**
     * 抹除相关的日志（走自己的闸门；同一秒最多一行，带末次原因）。
     *
     * <p>⚠️ 播报（{@code purge_announce}）<b>不</b>走这里了：它有自己的 5 秒窗口
     * （{@link ShanhaiPurgeAdmission.Announcer}），见 {@link #flushAnnouncements}。
     */
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
