package com.shanhai.add.guard.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.guard.ShanhaiMotionBrake;
import com.shanhai.add.guard.ShanhaiMotionBrakeRules;
import com.shanhai.add.guard.stack.ShanhaiViolationProbe;
import com.shanhai.add.mixin.ShanhaiHealthReadClampMixin;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海 · <b>「玩家状态由我们独占权威」—— 第二层（主脊）</b>（用户口径的<u>主线 B</u>）。
 *
 * <h2>🔴 2026-11-20：用户点破的架构缺口 —— 「我们当时的第二层是不是还没做好」</h2>
 * <pre>
 *   答案：<b>没做全</b>。
 *   旧版本类只管 <b>动量 / 位置 / 物品栏</b> 三项；
 *   {@code ShanhaiStateRules.exempt(...)} 还把 {@code deadOrDying} 当成<b>豁免条件</b>
 *   ⇒ <b>血 / die / 被移除</b> 这三类<b>恰好落在所有判据之外</b>：
 *     最该管的时候（血被写成 -Infinity、正在死）不管，
 *     血只靠本体 {@code ShanhaiGuardEvents.guardOne}（{@code ServerTickEvent.END}，<b>1 次/tick</b>）补
 *     ⇒ <b>追不上对手 20 次/秒的写</b>。实机日志：
 * </pre>
 * <pre>
 *   [SHANHAI-TRIAGE] damage_seen canceled=true guarded=true dmg=Infinity src=mob
 *   [CHAT] [创始现实修改模块] RESET deathTime (ServerTickEvent) x1 + CANCELLED LivingAttackEvent x1
 *   [CHAT] [创始现实修改模块] REFUSED die (LivingEntity#die / ServerPlayer#die) x1
 *   玩家仍然看到死亡界面 + 死亡白烟
 * </pre>
 * ⇒ 2026-11-20 起，第二层补齐成 <b>六项</b>（判据都在纯 JDK 的 {@link ShanhaiStateRules} 里）：
 * <pre>
 *   ① 血       应当 = getMaxHealth()（不可用则用基线）；实际 &lt;=0 ／ NaN ／ ±∞ ⇒ 写回
 *   ② 死亡态   isDeadOrDying ／ deathTime&gt;0 ／ 同步血量字段真值&lt;=0 ⇒ 两条腿补血 ＋ 两个计时器归零
 *   ③ 白烟     deathTime <b>每 tick 无条件归零</b> ⇒ 永远到不了 20 ⇒ tickDeath 压根不广播 60
 *   ④ 被移除   见 isRemoved ⇒ 复用本体那<b>唯一一份</b> revive 路径（{@code ShanhaiGuardEvents#rescueRemovedNow}）
 *   ⑤ 动量     原有（归属判据，无阈值）
 *   ⑥ 位置/物品栏 原有（窗口内）
 * </pre>
 * 🔴 <b>① ② ④ 三项永不被 {@link ShanhaiStateRules#exempt} 豁免</b>（那是这次缺口的根因）。
 *
 * <h2>为什么 ③ 不需要新 mixin（javap 实证，不是推测）</h2>
 * <pre>
 *   net.minecraft.world.entity.LivingEntity#tickDeath（srg m_6153_）
 *       偏移 0..10   ++deathTime
 *       偏移 14..16  bipush 20 / if_icmplt 53        ← deathTime &gt;= 20 才往下
 *       偏移 36..41  level() / bipush 60             ← 60 = POOF 白烟
 *       偏移 43      Level#broadcastEntityEvent(this, (byte)60)
 *       偏移 46..50  remove(RemovalReason.KILLED)
 *   LivingEntity#tick（srg m_6075_）偏移 651 才调 tickDeath，且只在 isDeadOrDying() 时
 *   ==== Forge 补丁后的 Player#tick（sources jar 实测，47.3.0）====
 *       213 public void tick() {
 *       214    ForgeEventFactory.onPlayerPreTick(this);   ← PlayerTickEvent START 在这里
 *       241    super.tick();                              ← tickDeath 在这一行之后
 *       285    ForgeEventFactory.onPlayerPostTick(this);
 *   ⇒ 在 START（{@link #reconcile}）里把 deathTime 归零，是<b>掐在 tickDeath 之前</b>，
 *     deathTime 那一拍最多变成 1 ⇒ 20 永远到不了 ⇒ 白烟 0 次、remove(KILLED) 0 次。
 * </pre>
 *
 * <h2>判据</h2>
 * <pre>
 *   「你这个反击退写得不行啊，我被打的时候自己都动不了了，然后那个猫居然还能推动我」
 *   「反击退的话，你为什么要把 tick 作为判据，我说了<b>把是否为玩家自己的操作作为判据</b>啊」
 * </pre>
 * 旧版把 <b>tick 边界</b>当判据：上一拍 END 与这一拍 START 之间 {@code deltaMovement} 变了 ⇒ 判"外部写入"。
 * 那条判据<b>分不清"别人推我"与"我自己在走"</b>，于是把玩家自己的操作也写回去了。真实日志（test2）：
 * <pre>
 *   拦截 状态被改写 ｜ field=motion before=(0.000,-0.078,0.000) after=(0.000,0.420,0.000) source=unknown restored=true via=tick_boundary
 *                    ↑ 0.42 就是玩家自己按空格起跳（javap 已证：handleMovePlayer 偏移 748 调 jumpFromGround）
 *   拦截 状态被改写 ｜ field=pos  before=(60.789,80.745,-48.373) after=(59.015,80.745,-47.161) restored=true via=window elapsed_ticks=2
 *                    ↑ 1.9 格 ≈ 正常冲刺的一包，被当成"被传送"拉回 ⇒ 「我被打的时候自己都动不了了」
 * </pre>
 *
 * <h2>现在的判据：<b>归属</b>（是不是玩家自己的操作）</h2>
 * <pre>
 *   标记来源（{@link ShanhaiSelfInput}）：
 *     self_input —— 原版处理【玩家自己的移动包】那一段
 *                   {@code ServerGamePacketListenerImpl#handleMovePlayer}（HEAD 置位 / RETURN 清位）
 *     self_guard —— 我们自己的写入（复活 / 复位 / 清击退 / 还原后重锚）
 *   判据（{@link ShanhaiStateRules#momentumVerdict(boolean, boolean, ShanhaiSelfInput.Origin, double, double, double, double, double, double)}）：
 *     来源是自己的 ⇒ 采纳（并把"他自己写完之后的值"记成新的认可值）
 *     来源不是自己的、值又变了 ⇒ 外部写入 ⇒ 还原
 *   ⇒ <b>tick 边界只是采样时机，不再决定归属</b>（这正是用户点名的那条区别）
 * </pre>
 *
 * <h2>位置：为什么"锚点"要跟着玩家的移动包走</h2>
 * 旧版锚点 = 窗口打开那一刻，20 tick 里所有位移都拿去和它比 ⇒ 正常冲刺被拉回。
 * 新版：<b>玩家每发一个移动包，就把锚点挪到"他自己写完之后的位置"</b>（{@link #onSelfInputTail}）
 * ⇒ 锚点与现在之间只差"那一包之后的一点点合法物理"，所以走/冲刺/跳跃/下落都不会被拉回；
 * 只有<b>标记之外</b>的位移（别的实体把他挪走）才会在窗口内被纠正。
 *
 * <h2>🔴 抓栈的位置（用户纠正过一次，不许回退）</h2>
 * 轮询点上的栈<b>只有我们自己的帧</b>（{@code ServerTickEvent → 我们的类}），看不到敌人 ——
 * 这就是那天 46 条 {@code source=unknown}、{@code strike_caller none} 的真因。
 * ⇒ <b>轮询只负责"发现值变了 → 还原"</b>，日志里写 {@code source=none_poll} /
 * {@code note=restored_wait_injection_stack}（如实：这里抓不到）；
 * 真正的栈由<b>原版写入点的注入</b>抓（{@link ShanhaiViolationProbe}）：
 * {@code setHealth} / {@code setRemoved} / {@code die} / {@code setDeltaMovement} / {@code setPosRaw} / 伤害事件取消点。
 *
 * <h2>🔴 它为什么不认识任何 mod（用户原话）</h2>
 * <pre>
 *   「我们不要 mixin 他的代码啊，那他代码类换个名称，或者换个 mod，不就都失效了吗」
 *   「不要写那种静态的 mixin，我说修改它的代码，是那种动态的，要有泛用性的」
 *   「要么你就干脆保护玩家的代码不被修改啊」
 * </pre>
 * ⇒ 判据仍然只看<b>玩家的值</b>与<b>归属</b>：换 mod、换类名、换写法，值一样会变、标记一样成立。
 * 唯一新增的 mixin 是<b>原版</b> {@code net.minecraft.*} 上的两个方法（点名见报告），不是第三方代码。
 *
 * <h2>失败姿势</h2>
 * 每个入口 {@code try/catch(Throwable)}：本类坏掉的结果只能是"这一拍没纠正"，
 * <b>绝不许</b>把 tick 带崩、绝不许改出一个非法值（写回的都是我们自己读到的合法值）。
 *
 * <h2>🔴 2026-11-08 之后新增的上一层：<b>写入口「写入即丢」</b>（{@link ShanhaiMotionBrake}）</h2>
 * 现场日志证明"下一拍写回"挡不住推动（{@code restored=true} 但他仍被推）。所以动量这一项多了一层：
 * 在 {@code Entity#setDeltaMovement(Vec3)} 的 <b>HEAD</b> 判，判成"丢弃"就 {@code ci.cancel()}，
 * 这次写入干脆不发生。两层的关系写死如下：
 * <pre>
 *   ① 写入即丢（{@link ShanhaiMotionBrake}）：能丢就丢，值根本不变大 ⇒ 位移/击退包都不会按大的走
 *   ② 下一拍写回（本类 {@link #restoreMomentum}）：<b>没有被丢掉的</b>那些（以及硬闸停手的 60 秒里）照旧兜底
 *   ⇒ 两层都不删：① 是主路，② 是保险（本轮的判据若误判，60 秒后也一定回到 ② 的旧行为）
 * </pre>
 * 位置 / 物品栏两项<b>没有</b>对应改动（它们的写入点不在这两个方法上），仍是本类原来那一套。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiStateAuthority {

    /** 日志前缀（与守卫同一套）。 */
    public static final String PREFIX = ShanhaiStateLog.PREFIX;

    /** 「我们刚拦过东西」的窗口长度（tick）。20 tick = 1 秒。 */
    public static final int WINDOW_TICKS = 20;

    /** 每个玩家一份的状态上限（防泄漏；玩家数远小于它）。 */
    public static final int MAX_TRACKED = 64;

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private static final AtomicLong MOTION_FIXED = new AtomicLong();
    private static final AtomicLong POS_FIXED = new AtomicLong();
    private static final AtomicLong INV_FIXED = new AtomicLong();
    /** 🆕 2026-11-20：血被写回几次（第二层新增第 ① 项）。 */
    private static final AtomicLong HEALTH_FIXED = new AtomicLong();
    /** 🆕 2026-11-20：死亡态被掰正几次（第 ② 项）。 */
    private static final AtomicLong DEATH_FIXED = new AtomicLong();
    /** 🆕 2026-11-20：{@code deathTime} 被归零几次（第 ③ 项 = 掐白烟）。 */
    private static final AtomicLong DEATH_TIMER_ZEROED = new AtomicLong();
    /** 🆕 2026-11-20：被移除之后救回来几次（第 ④ 项，复用本体那条唯一路径）。 */
    private static final AtomicLong REMOVED_FIXED = new AtomicLong();
    /** 🆕 2026-11-20：{@code LivingEntity#tickDeath} 被整刀取消几次（白烟/remove 都没发生的那几次）。 */
    private static final AtomicLong TICK_DEATH_BLOCKED = new AtomicLong();
    private static final AtomicLong WINDOWS_OPENED = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong SELF_INPUT_ADOPTED = new AtomicLong();
    private static final AtomicLong SELF_GUARD_ADOPTED = new AtomicLong();

    /** 有多少"移动包声称的位移"人腿解释不通（＝疑似瞬移，锚点不动、交给轮询判据）。 */
    private static final AtomicLong IMPLAUSIBLE_INPUT = new AtomicLong();

    private ShanhaiStateAuthority() {}

    // ═══════════════════════════════ 装配 / 自证 ═══════════════════════════════

    /** 一行开机自证（真值仍要看 {@code 拦截 状态被改写} / {@code state_second_layer} 那几个行）。 */
    public static String armedLine() {
        return "state_authority armed " + ShanhaiStateSwitches.statusLine()
                + " window_ticks=" + WINDOW_TICKS
                + " layer2=health+dead+deathtime+removed+motion+pos+inv"
                + " judge_health=dead_band_only(<=0/NaN/Inf)"
                + " judge_dead=deathFlowResetDecision"
                + " judge_deathtime=always_zero(fix_60_poof_at_tickDeath@20)"
                + " judge_removed=reuse_guard_events_rescue"
                + " judge_motion=self_attribution（self_input/self_guard 一律采纳；无阈值）"
                + " judge_pos=self_attribution+anchor_budget(" + ShanhaiStateRules.SPRINT_BUDGET_PER_TICK + "/tick)"
                + " judge_inv=lost>=" + (int) Math.round(ShanhaiStateRules.INVENTORY_LOST_FRACTION * 100) + "%"
                + " brake_at_write_head=on（" + ShanhaiMotionBrakeRules.Tuning.production() + "）"
                + " hold_grace_ticks=" + ShanhaiStateRules.HOLD_GRACE_TICKS
                + "/" + ShanhaiGuardEvents.HOLD_GRACE_TICKS
                + (ShanhaiStateRules.HOLD_GRACE_TICKS == ShanhaiGuardEvents.HOLD_GRACE_TICKS
                        ? "(match)" : "(🔴 MISMATCH 两处常量漂了)")
                + " " + ShanhaiSelfInput.statusLine()
                + " judge_tickdeath=HEAD_cancel(.m_6153_@LivingEntity)+heal_before_cancel=on"
                + "（受保护玩家：先补血＋计时器归零，再 cancel ⇒ 白烟/remove 都不发生）"
                + " logger=" + loggerProbe();
    }

    /**
     * 🔴 2026-11-20：<b>真值</b>自证"日志出口通不通"（旧版这一句是假的）。
     *
     * <h2>为什么要重写（实机证据）</h2>
     * 旧版写的是 {@code (ShanhaiStateLog.emitted() >= 0 ? "bound" : "?")} ——
     * {@code emitted()} 是"已落盘行数"计数器，<b>永远 &gt;= 0</b> ⇒ 这一句恒为 {@code bound}。
     * 于是 22:10:26.919 那行开机自证看起来"日志已接线"，而同一场 8341 行日志里
     * {@code 拦截 状态被改写}（含 {@code field=motion/pos/inv/health/dead/deathtime/removed}）
     * <b>一行都没有</b> ⇒ "自证"恰好把要发现的问题藏住了。
     *
     * <p>现在改成<b>真的发一行探针、再看计数器涨没涨</b>（同一个出口、同一套 1/s 限流）：
     * 探针行掉下来 ⇒ 出口活着；掉不下来 ⇒ 出口没接，第二层所有 {@code field=…} 行都会静默消失。
     */
    private static String loggerProbe() {
        try {
            final long before = ShanhaiStateLog.emitted();
            ShanhaiStateLog.emit("selfcheck", "-", "emit_probe", "emit_probe", "none_poll",
                    "selfcheck 探针：这一行掉下来 ⇒ 第二层的 field=… 行才会掉下来");
            return ShanhaiStateLog.emitted() > before
                    ? "bound(probe_emitted=true)"
                    : "🔴 UNBOUND(probe_dropped=true ⇒ 第二层所有 field=… 行都不会出现)";
        } catch (Throwable t) {
            return "probe_failed(" + t + ")";
        }
    }

    /** 一行读数（命令 / 报告用）。 */
    public static String statusLine() {
        final int tracked;
        final int inWindow;
        synchronized (TRACKS) {
            tracked = TRACKS.size();
            int w = 0;
            for (Track t : TRACKS.values()) {
                if (t.windowLeft > 0) {
                    w++;
                }
            }
            inWindow = w;
        }
        return "state_authority " + ShanhaiStateSwitches.statusLine()
                + " tracked=" + tracked
                + " in_window=" + inWindow
                + " windows_opened=" + WINDOWS_OPENED.get()
                + " motion_fixed=" + MOTION_FIXED.get()
                + " pos_fixed=" + POS_FIXED.get()
                + " inv_fixed=" + INV_FIXED.get()
                + " health_fixed=" + HEALTH_FIXED.get()
                + " death_fixed=" + DEATH_FIXED.get()
                + " deathtime_zeroed=" + DEATH_TIMER_ZEROED.get()
                + " removed_fixed=" + REMOVED_FIXED.get()
                + " tick_death_blocked=" + TICK_DEATH_BLOCKED.get()
                + " self_input_adopted=" + SELF_INPUT_ADOPTED.get()
                + " self_guard_adopted=" + SELF_GUARD_ADOPTED.get()
                + " implausible_input=" + IMPLAUSIBLE_INPUT.get()
                + " log_emitted=" + ShanhaiStateLog.emitted()
                + " log_folded=" + ShanhaiStateLog.folded()
                + " errors=" + ERRORS.get()
                + " | " + ShanhaiMotionBrake.statusLine()
                + " | " + ShanhaiSelfInput.statusLine();
    }

    /** 清空所有跟踪状态（停服 / 命令重来）。 */
    public static void reset(String why) {
        synchronized (TRACKS) {
            TRACKS.clear();
        }
        ShanhaiStateLog.reset();
        ShanhaiSelfInput.forceClear();
        ShanhaiMotionBrake.reset(why);          // 刹车那一层也一起清（它有自己的逐玩家硬闸状态）
        ShanhaiMod.LOGGER.info("{} state_authority_reset why={} tracked=0", PREFIX, why);
    }

    // ═══════════════════════════════ 给「我们自己的写入」打的标记 ═══════════════════════════════

    /**
     * 进入"这是我们自己写的"作用域（必须成对调用；用 {@code try/finally}）。
     *
     * <p>⚠️ 2026-11-08 之后它转给 {@link ShanhaiSelfInput#enterGuard()} ——
     * 与"玩家自己的操作"用<b>同一套</b>标记机制，只是来源记为 {@code self_guard}。
     * 保留这个旧名字是为了不动调用点（含本体那边可能的调用）。
     */
    public static void enterOurScope() {
        ShanhaiSelfInput.enterGuard();
    }

    /** 退出作用域（与 {@link #enterOurScope()} 成对）。 */
    public static void exitOurScope() {
        ShanhaiSelfInput.exitGuard();
    }

    /** 我们刚写过这个实体的动量 ⇒ 下一拍不做判据（以现状为准）。 */
    public static void noteOurMomentum(Entity entity) {
        if (!(entity instanceof ServerPlayer)) {
            return;
        }
        try {
            synchronized (TRACKS) {
                final Track t = TRACKS.get(entity.getUUID());
                if (t != null) {
                    t.ourMomentum = true;
                }
            }
        } catch (Throwable ignored) {
            // 标不上只意味着"下一拍可能多判一次"，不影响正确性
        }
    }

    /** 我们刚拦过东西 ⇒ 打开窗口（位置/物品栏只在这个窗口里动手）。 */
    public static void noteBlocked(Entity entity, String reason) {
        if (!(entity instanceof ServerPlayer sp)) {
            return;
        }
        try {
            final Track t = trackOf(sp);
            if (t != null) {
                openWindow(sp, t, reason);
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ ① 玩家自己的操作：HEAD / RETURN 两个钩子 ═══════════════════════════════

    /**
     * 原版开始处理【玩家自己的移动包】了（{@code handleMovePlayer} 的 HEAD）。
     *
     * <p>做两件事：
     * <ol>
     *   <li>把这个线程的 {@code shanhaiSelfInput} 标记置为 {@code self_input}
     *       （从这一刻到 {@link #onSelfInputTail(Object)} 之间发生的写入都是玩家自己干的）；</li>
     *   <li>把<b>进门那一刻</b>的动量/位置记下来 —— 这一条是"只采纳这一包自己造成的变化"的关键：
     *       TAIL 上只有"值跟进门时不一样"才证明<b>是这一包改的</b>。
     *       若不记，TAIL 就会把"上一拍别人推的 0.4"也当成玩家自己的操作采纳掉
     *       （猫推 → 下一拍包先到 → 一采纳，那次击退就永远还不了）。</li>
     * </ol>
     *
     * @param listener {@code ServerGamePacketListenerImpl}（<b>以 {@code Object} 接</b>：
     *                 mixin 类体里不许出现 MC 成员引用，见 {@code ShanhaiSetHealthGuardMixin} 类注释）
     */
    public static void onSelfInputHead(Object listener) {
        ShanhaiSelfInput.enterInput();
        try {
            final ServerPlayer sp = playerOf(listener);
            if (sp != null) {
                final Track t = trackOf(sp);
                if (t != null) {
                    t.inputSeenInPacket = true;
                    final Vec3 mv = sp.getDeltaMovement();
                    t.inputHeadMx = mv.x;
                    t.inputHeadMy = mv.y;
                    t.inputHeadMz = mv.z;
                    t.inputHeadX = sp.getX();
                    t.inputHeadY = sp.getY();
                    t.inputHeadZ = sp.getZ();
                }
            }
        } catch (Throwable ignored) {
            // 只影响读数：标记已经置上了，判据照走（读不到"进门值"⇒ 这一包不采纳，宁可多判外部）
        }
    }

    /**
     * 原版处理完玩家自己的移动包了（{@code handleMovePlayer} 的 RETURN / TAIL）。
     *
     * <p>口径（两件事，然后<b>一定</b>清标记）：
     * <ol>
     *   <li><b>动量</b>：只有"值跟进门时不一样"才采纳 —— 那才是<b>这一包自己造成的</b>
     *       （起跳 0.42 是它调 {@code jumpFromGround()} 产生的、落地摩擦、碰撞归零都在这一类）。
     *       进门时值就没变 ⇒ 这一包没碰动量，认可值<b>保持不动</b>，于是"上一拍别人推的那一把"
     *       会在下一拍 START 被正常判成外部写入。</li>
     *   <li><b>位置</b>：这一包声称的位移要是<b>人腿解释得通</b>（预算判据）才把锚点挪过去；
     *       解释不通（例如客户端自称瞬移 50 格）⇒ 锚点<b>不动</b>，交给下一拍 START 还原。</li>
     * </ol>
     *
     * @param listener 同 {@link #onSelfInputHead(Object)}
     */
    public static void onSelfInputTail(Object listener) {
        try {
            final ServerPlayer sp = playerOf(listener);
            if (sp != null && sp.level() != null && !sp.level().isClientSide()) {
                final Track t = trackOf(sp);
                if (t != null) {
                    if (t.inputSeenInPacket) {
                        // ── 动量：只采纳"这一包自己改的" ──
                        final Vec3 now = sp.getDeltaMovement();
                        if (ShanhaiStateRules.selfInputChangedMomentum(t.inputHeadMx, t.inputHeadMy,
                                t.inputHeadMz, now.x, now.y, now.z)) {
                            adoptMomentum(t, now);
                            t.approvedOrigin = ShanhaiSelfInput.Origin.SELF_INPUT;
                            SELF_INPUT_ADOPTED.incrementAndGet();
                        }
                        // ── 位置：只有"这一包声称的位移"人腿解释得通才重锚 ──
                        if (plausiblePacketMove(sp, t)) {
                            reanchor(sp, t);
                        } else {
                            IMPLAUSIBLE_INPUT.incrementAndGet();
                        }
                    }
                    // ⚠️ 刻意**不**置 t.ourMomentum：认可值已经在这里对齐了，
                    //    下一拍 START 只要值没再变就是 KEEP；若这中间真有人推了一把，
                    //    那一次**必须**被判成外部（置了旗标反而会把那次推送放过去）。
                    t.inputSeenInPacket = false;
                }
            }
        } catch (Throwable ex) {
            ERRORS.incrementAndGet();
            ShanhaiMod.LOGGER.warn("{} state_authority_failed where=self_input_tail err={}", PREFIX, ex.toString());
        } finally {
            // 🔴 无论上面发生什么，标记必须清掉（否则这一线程上的保护会静默失效）
            ShanhaiSelfInput.exitInput();
        }
    }

    /**
     * 这一包声称的位移人腿解释得通吗（＝预算判据，<b>忽略来源</b>才问得出"可疑不可疑"）。
     *
     * <p>窗口没开时一律算"解释得通"（那时根本不做位置判据，锚点越新越好）。
     */
    private static boolean plausiblePacketMove(ServerPlayer sp, Track t) {
        if (t.windowLeft <= 0) {
            return true;
        }
        final int elapsed = Math.max(0, sp.tickCount - t.posAnchorTick);
        final double speed = ShanhaiStateRules.horizontalSpeed(t.approvedX, t.approvedZ);
        return ShanhaiStateRules.positionVerdict(ShanhaiStateSwitches.pos(), exemptOf(sp, t),
                ShanhaiSelfInput.Origin.NONE, elapsed,
                t.posAnchorX, t.posAnchorY, t.posAnchorZ, sp.getX(), sp.getY(), sp.getZ(), speed)
                != ShanhaiStateRules.Verdict.RESTORE;
    }

    // ═══════════════════════════════ ② 原版写入点的旁听（只抓栈，不改行为） ═══════════════════════════════

    /**
     * 有人在写玩家的 {@code deltaMovement}（{@code Entity#setDeltaMovement(Vec3)} 的 RETURN，
     * 那一刻字段<b>已经写好</b>）。
     *
     * <p>🔴 2026-11-08 之后：<b>写入口多了一层"写入即丢"</b>（{@code ShanhaiMotionBrake}，同一个方法的 HEAD）。
     * 被那一层丢掉的写入<b>走不到这里</b>（原方法被取消）—— 它的证据由
     * {@link ShanhaiViolationProbe#onMotionBrakeDrop} 在 HEAD 上补，{@code where=motion_brake}。
     * 本方法保留原职责：<b>没被丢掉</b>的那些写入，值仍然被改了、且轮询接下来会还原 ⇒ 留一条栈。
     *
     * <p><b>只做证据</b>：不改值、不取消、不写回。判定与轮询用<b>同一套判据</b>，
     * 只有"轮询接下来一定会还原"的那一次才去抓栈（1/s 限流在抓栈那一层）。
     *
     * @param entity 被写的实体（{@code Object}：mixin 类体不许引 MC 成员）
     */
    public static void noteMotionWrite(Object entity) {
        try {
            if (!(entity instanceof ServerPlayer sp)) {
                return;
            }
            final Track t = trackOf(sp);
            if (t == null || !t.ready || !t.protectedNow) {
                return;
            }
            final ShanhaiSelfInput.Origin origin = originOf(t);
            if (origin.isSelf()) {
                return;                                          // 玩家自己 / 我们自己 ⇒ 采纳，不抓
            }
            if (exemptOf(sp, t)) {
                return;
            }
            final Vec3 now = sp.getDeltaMovement();
            final ShanhaiStateRules.Verdict v = ShanhaiStateRules.momentumVerdict(
                    true, false, origin, t.approvedX, t.approvedY, t.approvedZ, now.x, now.y, now.z);
            if (v != ShanhaiStateRules.Verdict.RESTORE) {
                return;                                          // 值没变 / 归零 ⇒ 轮询也不会动它
            }
            ShanhaiViolationProbe.onMotionWrite(sp, now.x, now.y, now.z);
        } catch (Throwable ignored) {
            // 旁听失败只意味着"这一次没有栈"，绝不许影响写入本身
        }
    }

    /**
     * 有人在写玩家的位置（{@code Entity#setPosRaw(DDD)} 的 HEAD）。
     *
     * <p>与 {@link #noteMotionWrite} 同一条口径：<b>只抓栈</b>；
     * 判据用"锚点 + 预算"（锚点跟着玩家自己的移动包走）。
     */
    public static void notePosWrite(Object entity, double newX, double newY, double newZ) {
        try {
            if (!(entity instanceof ServerPlayer sp)) {
                return;
            }
            final Track t = trackOf(sp);
            if (t == null || !t.ready || !t.protectedNow || t.windowLeft <= 0) {
                return;
            }
            final ShanhaiSelfInput.Origin origin = originOf(t);
            if (origin.isSelf()) {
                return;
            }
            if (exemptOf(sp, t)) {
                return;
            }
            final int elapsed = Math.max(0, sp.tickCount - t.posAnchorTick);
            final double speed = ShanhaiStateRules.horizontalSpeed(t.approvedX, t.approvedZ);
            final ShanhaiStateRules.Verdict v = ShanhaiStateRules.positionVerdict(true, false, origin, elapsed,
                    t.posAnchorX, t.posAnchorY, t.posAnchorZ, newX, newY, newZ, speed);
            if (v != ShanhaiStateRules.Verdict.RESTORE) {
                return;
            }
            ShanhaiViolationProbe.onPosWrite(sp, newX, newY, newZ);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ 事件：每玩家 tick ═══════════════════════════════

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        try {
            if (!(event.player instanceof ServerPlayer sp)) {
                return;                      // 客户端玩家不归我们管（那边没有权威）
            }
            if (sp.level() == null || sp.level().isClientSide()) {
                return;
            }
            if (event.phase == TickEvent.Phase.START) {
                reconcile(sp);
            } else if (event.phase == TickEvent.Phase.END) {
                record(sp);
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            ShanhaiMod.LOGGER.warn("{} state_authority_failed where=player_tick err={}", PREFIX, t.toString());
        }
    }

    /** 玩家被打 ⇒ 打开窗口（"我们刚拦过东西"的另一种来源）。 */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer sp && !sp.level().isClientSide()) {
                final Track t = trackOf(sp);
                if (t != null && t.ready) {
                    openWindow(sp, t, "attack");
                }
            }
        } catch (Throwable ignored) {
            // 开窗口失败只意味着位置/物品栏那两项这一轮不生效
        }
    }

    /**
     * 伤害被<b>取消</b>的那一拍：抓一次栈（用户口径：「所有代码路径」，伤害事件的取消点也算）。
     *
     * <p>用 {@link EventPriority#LOWEST}：取消动作是别人（本体/别的 mod）做的，
     * 我们要在<b>所有人都跑完之后</b>才看得到 {@code isCanceled()}。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingAttackCancelled(LivingAttackEvent event) {
        try {
            if (event.isCanceled() && event.getEntity() instanceof ServerPlayer sp
                    && sp.level() != null && !sp.level().isClientSide()) {
                ShanhaiViolationProbe.onAttackCancelled(sp, "LivingAttackEvent");
            }
        } catch (Throwable ignored) {
            // 抓不到栈不影响任何判定
        }
    }

    /** 同上（{@code LivingHurtEvent} 也是常见的取消点）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurtCancelled(LivingHurtEvent event) {
        try {
            if (event.isCanceled() && event.getEntity() instanceof ServerPlayer sp
                    && sp.level() != null && !sp.level().isClientSide()) {
                ShanhaiViolationProbe.onAttackCancelled(sp, "LivingHurtEvent");
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * 🆕 2026-11-20：<b>伤害事件本身</b>（不管有没有被取消）⇒ 触发源第 ① 类 {@code hurt}。
     *
     * <p>为什么必须挂在 {@code LivingHurtEvent} 而不是只在"被取消"时才抓：这条事件是在
     * {@code LivingEntity#hurt} <b>里面</b>发的，所以此刻栈上还留着「<b>谁调了 hurt</b>」——
     * 那正是"对手在打我们玩家"的那一帧。而"伤害被取消"只是其中一小部分情形
     * （大多数攻击并没有被取消，只是伤害被削）⇒ 只在取消点抓等于<b>大部分场景都漏掉</b>。
     *
     * <p>用 {@link EventPriority#LOWEST}：等所有人都处理完再抓，栈里不会有"别人的处理器"干扰。
     * 成本：{@code StackWalker} 只在<b>受保护玩家挨打</b>时走一趟，且同一 {@code hurt|玩家} 1/s 限流。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurtProbe(LivingHurtEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer sp
                    && sp.level() != null && !sp.level().isClientSide()) {
                ShanhaiViolationProbe.onHurt(sp, "LivingHurtEvent", event.getAmount());
            }
        } catch (Throwable ignored) {
            // 抓不到栈不影响任何判定
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        try {
            synchronized (TRACKS) {
                TRACKS.clear();
            }
            ShanhaiSelfInput.forceClear();
            ShanhaiMotionBrake.reset("server_stopped");
        } catch (Throwable ignored) {
            // 停服清理失败不影响任何东西（进程内的状态随下次进世界重建）
        }
    }

    // ═══════════════════════════════ ③ START：比对 ＋ 纠正（采样点不变，判据换成归属） ═══════════════════════════════

    private static void reconcile(ServerPlayer sp) {
        final Track t = trackOf(sp);
        if (t == null || !t.ready) {
            return;                              // 第一拍：只建基线，不判
        }
        final boolean protectedNow = isProtected(sp);
        if (protectedNow != t.protectedNow) {
            t.protectedNow = protectedNow;
            adoptMomentum(t, sp.getDeltaMovement());   // 保护状态变化 ⇒ 重新对齐，不纠正
            t.windowLeft = 0;
            return;
        }
        if (!protectedNow) {
            return;                              // 不携带模块 ⇒ 一个字节都不碰
        }

        // ── 🔴 2026-11-20 新增 ① ② ③：血 / 死亡态 / 白烟计时器 ──
        //    🔴 这三项【永不】被 exemptOf(...) 豁免 —— 旧版把 deadOrDying 当豁免条件，
        //       于是"血被写死 / 正在死"这件事恰好落在所有判据之外（这就是用户点破的缺口）。
        checkLife(sp, t);
        // ── 🔴 2026-11-20 新增 ④：被移除 ⇒ 复用本体那唯一一份 revive 路径 ──
        checkRemoved(sp, t);

        final boolean exempt = exemptOf(sp, t);
        final ShanhaiSelfInput.Origin origin = originOf(t);

        // ── 动量（归属判据） ──
        final Vec3 now = sp.getDeltaMovement();
        final ShanhaiStateRules.Verdict mv = ShanhaiStateRules.momentumVerdict(
                ShanhaiStateSwitches.momentum(), exempt, origin,
                t.approvedX, t.approvedY, t.approvedZ, now.x, now.y, now.z);
        if (mv == ShanhaiStateRules.Verdict.ADOPT) {
            adoptMomentum(t, now);
            t.approvedOrigin = origin;
            if (origin == ShanhaiSelfInput.Origin.SELF_GUARD) {
                SELF_GUARD_ADOPTED.incrementAndGet();
            }
        } else if (mv == ShanhaiStateRules.Verdict.RESTORE) {
            restoreMomentum(sp, t, now);
        }

        // ── 位置（只在窗口内；锚点跟着玩家自己的移动包走） ──
        if (t.windowLeft > 0) {
            checkPosition(sp, t, exempt, origin);
        }

        // ── 物品栏（只在窗口内） ──
        if (t.windowLeft > 0) {
            checkInventory(sp, t);
        }
    }

    private static void restoreMomentum(ServerPlayer sp, Track t, Vec3 found) {
        final Vec3 approved = new Vec3(t.approvedX, t.approvedY, t.approvedZ);
        enterOurScope();
        try {
            sp.setDeltaMovement(approved);
        } finally {
            exitOurScope();
        }
        // 客户端那一份也要按【认可值】改回来：它可能已经收到过对方发的位移包
        try {
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
        } catch (Throwable ignored) {
            // 发包失败只影响"客户端要不要抖一下"，服务端权威已经纠正
        }
        MOTION_FIXED.incrementAndGet();
        // before = 我们认可的值（也会被写回）；after = 被改成什么（留证据）
        // source=none_poll：**轮询点抓不到写者**（栈上只有我们自己的帧）⇒ 如实写，不编名字；
        // 真正的栈由「原版写入点的注入」抓（ShanhaiViolationProbe → violation_stack where=motion/pos）。
        ShanhaiStateLog.emit("motion", name(sp),
                ShanhaiStateLog.vec(t.approvedX, t.approvedY, t.approvedZ),
                ShanhaiStateLog.vec(found.x, found.y, found.z),
                "none_poll",
                "approved_from=" + originToken(t.approvedOrigin)
                        + " restored=true via=attribution note=restored_wait_injection_stack");
        openWindow(sp, t, "motion");
    }

    private static void checkPosition(ServerPlayer sp, Track t, boolean exempt, ShanhaiSelfInput.Origin origin) {
        if (!ShanhaiStateSwitches.pos()) {
            return;
        }
        final int elapsed = Math.max(0, sp.tickCount - t.posAnchorTick);
        final double speed = ShanhaiStateRules.horizontalSpeed(t.approvedX, t.approvedZ);
        final double nowX = sp.getX();
        final double nowY = sp.getY();
        final double nowZ = sp.getZ();
        if (ShanhaiStateRules.positionVerdict(true, exempt, origin, elapsed,
                t.posAnchorX, t.posAnchorY, t.posAnchorZ, nowX, nowY, nowZ, speed)
                != ShanhaiStateRules.Verdict.RESTORE) {
            return;
        }
        enterOurScope();
        try {
            sp.teleportTo(t.posAnchorX, t.posAnchorY, t.posAnchorZ);   // 与本体「传送拉回」同一个调用
            sp.setDeltaMovement(Vec3.ZERO);
        } finally {
            exitOurScope();
        }
        adoptMomentum(t, Vec3.ZERO);
        POS_FIXED.incrementAndGet();
        ShanhaiStateLog.emit("pos", name(sp),
                ShanhaiStateLog.vec(t.posAnchorX, t.posAnchorY, t.posAnchorZ),
                ShanhaiStateLog.vec(nowX, nowY, nowZ),
                "none_poll",
                "write_origin=" + originToken(origin)
                        + " restored=true via=anchor elapsed_ticks=" + elapsed
                        + " note=restored_wait_injection_stack");
        reanchor(sp, t);      // 重新锚定，免得下一拍又按旧锚点判
        openWindow(sp, t, "pos");
    }

    private static void checkInventory(ServerPlayer sp, Track t) {
        if (!ShanhaiStateSwitches.inv()) {
            return;
        }
        final ShanhaiStateInventory.Snapshot snap = t.invSnapshot;
        if (snap == null) {
            return;
        }
        final boolean containerOpen = ShanhaiStateInventory.containerOpen(sp);
        final boolean cursorNonEmpty = ShanhaiStateInventory.cursorNonEmpty(sp);
        final int before = snap.nonEmpty();
        final int lost = ShanhaiStateInventory.lostCount(snap, sp);
        final ShanhaiStateRules.Verdict v = ShanhaiStateRules.inventoryVerdict(true, containerOpen,
                true, before, before - lost, lost, cursorNonEmpty);
        if (v != ShanhaiStateRules.Verdict.RESTORE) {
            return;
        }
        final int restored = ShanhaiStateInventory.restoreMissing(snap, sp);
        if (restored <= 0) {
            return;
        }
        INV_FIXED.incrementAndGet();
        ShanhaiStateLog.emit("inv", name(sp), "non_empty=" + before, "non_empty=" + (before - lost),
                "none_poll",
                "restored_slots=" + restored + " via=window note=inv_has_no_write_hook");
        t.invSnapshot = ShanhaiStateInventory.snapshot(sp);   // 补完立刻重拍，免得下一拍重复补
    }

    // ═══════════════ 🆕 2026-11-20：第二层的 ① 血 / ② 死亡态 / ③ 白烟计时器 ═══════════════

    /**
     * 🔴 <b>第二层第 ① ② ③ 项：血 / 死亡态 / 白烟计时器</b>（2026-11-20 补的缺口）。
     *
     * <h2>为什么顺序是 ③ → ① → ②</h2>
     * <ol>
     *   <li><b>③ 先归零 {@code deathTime}</b>：这是<b>无条件</b>的（不设阈值），
     *       因为对手可以做到"血是满的、但 {@code deathTime} 被写到 19"⇒ 下一拍
     *       {@code tickDeath} 一加就够 20 ⇒ 下面"白烟的证据链"那一行说的事情就发生了。
     *       归零的代价为零：原版只在 {@code isDeadOrDying()} 时才读它。</li>
     *   <li><b>① 再判血</b>：{@code <=0 / NaN / ±∞} 才写回（正常受伤不归这一项管）。</li>
     *   <li><b>② 最后判死亡态</b>：血已经掰正之后还判死 ⇒ 走<b>唯一一份</b>复位动作
     *       （{@link ShanhaiGuardValueLayer#resetDeathFlow}：两条腿补血 ＋ 两个计时器归零）。</li>
     * </ol>
     *
     * <h2>🔴 白烟的证据链（javap，不是推测）</h2>
     * <pre>
     *   LivingEntity#tickDeath（srg m_6153_）：++deathTime → 偏移14 bipush 20 → if_icmplt 53
     *      → 偏移41 bipush 60 → 偏移43 Level#broadcastEntityEvent(this, (byte)60)   ← 白烟
     *   LivingEntity#tick（srg m_6075_）偏移 651 才调它，且只在 isDeadOrDying()
     *   Forge 的 Player#tick：214 onPlayerPreTick(START) → 241 super.tick() → 285 onPlayerPostTick
     *   ⇒ 本方法由 START 里的 {@link #reconcile} 调 ⇒ <b>永远掐在 tickDeath 之前</b>。
     * </pre>
     *
     * @param sp 受保护玩家（调用方已经判过）
     * @param t  这一拍的跟踪状态
     */
    private static void checkLife(ServerPlayer sp, Track t) {
        // ── ③ 白烟计时器：无条件归零（永不到 20 ⇒ tickDeath 压根不广播 (byte)60）──
        final int deathTimeSeen = sp.deathTime;
        if (ShanhaiStateRules.deathTimerNeedsZero(deathTimeSeen, sp.hurtTime)) {
            sp.deathTime = 0;
            DEATH_TIMER_ZEROED.incrementAndGet();
            ShanhaiStateLog.emit("deathtime", name(sp), "death_time=0", "death_time=" + deathTimeSeen,
                    "none_poll",
                    "restored=true via=second_layer judge=tickDeath_threshold_20"
                            + " next_broadcast=never(poof_60) hurt_time=" + sp.hurtTime
                            + " note=poll_cannot_see_writer");
        }

        // ── ① 血：应当值 vs 实际值 ──
        //    实际值优先用【字段真值】（对手会给 getHealth() 加偏移，与本体同一口径）。
        final float fieldHealth = readFieldHealth(sp);
        final float found = Float.isNaN(fieldHealth) ? safeHealth(sp) : fieldHealth;
        final float expected = healthExpected(sp, t);
        if (ShanhaiStateRules.healthVerdict(true, expected, found) == ShanhaiStateRules.Verdict.RESTORE) {
            restoreHealth(sp, t, expected, found);
        }

        // ── ② 死亡态：血/计时器/字段真值 任一说"已经在死" ⇒ 唯一一份复位动作 ──
        final float fieldNow = readFieldHealth(sp);
        if (ShanhaiStateRules.deathStateVerdict(true, sp.isDeadOrDying(), sp.deathTime,
                sp.hurtTime, fieldNow) == ShanhaiStateRules.Verdict.RESTORE) {
            final float max = sp.getMaxHealth();
            if (!ShanhaiStateRules.healthUsable(max)) {
                // 🔴 getMaxHealth() 不可用 ⇒ 【一步都不写】：硬写会把血量字段写成 NaN，比 0 更糟
                //    （与客户端那一路同一条纪律）。留给下一拍 / 本体的 look_up 通道。
                ShanhaiStateLog.emit("dead", name(sp), "state=alive", "state=dying", "none_poll",
                        "restored=false via=failed_probe getMaxHealth=" + max
                                + " dead=" + sp.isDeadOrDying() + " death_time=" + sp.deathTime
                                + " field_health=" + fieldNow + " note=getMaxHealth_unusable");
                return;
            }
            final ShanhaiGuardValueLayer.HealOutcome outcome = ShanhaiGuardValueLayer.resetDeathFlow(sp);
            DEATH_FIXED.incrementAndGet();
            ShanhaiStateLog.emit("dead", name(sp), "state=alive", "state=dying",
                    ShanhaiViolationProbe.writerForCorrection("die", sp),
                    "restored=" + outcome.landed() + " via=second_layer heals=" + outcome.channel.label()
                            + " death_time_was=" + deathTimeSeen + " field_health=" + fieldNow
                            + " | " + outcome.detail);
        }
    }

    /**
     * 把血写回应当值（<b>能写多少写多少，写不了就如实说写不了</b>）。
     *
     * <pre>
     *   getMaxHealth() 可用      ⇒ 走唯一一份复位动作（两条腿补血 ＋ deathTime/hurtTime 归零）
     *   getMaxHealth() 不可用    ⇒ 用【基线】直写同步血量字段（forceHealthField，绕开 setHealth）
     *   基线也不可用             ⇒ 一个字节都不写，日志写 via=failed_probe
     * </pre>
     *
     * @param sp       玩家
     * @param t        跟踪状态（基线在 {@code t.approvedHealth} 里）
     * @param expected 应当值
     * @param found    这次读到的实际值（会变红/NaN/±∞）
     */
    private static void restoreHealth(ServerPlayer sp, Track t, float expected, float found) {
        final float max = sp.getMaxHealth();
        final boolean maxOk = ShanhaiStateRules.healthUsable(max);
        final String writer = ShanhaiViolationProbe.writerForCorrection("health", sp);
        if (maxOk) {
            final ShanhaiGuardValueLayer.HealOutcome outcome = ShanhaiGuardValueLayer.resetDeathFlow(sp);
            HEALTH_FIXED.incrementAndGet();
            ShanhaiStateLog.emit("health", name(sp),
                    ShanhaiStateLog.num(expected), ShanhaiStateLog.num(found), writer,
                    "restored=" + outcome.landed() + " via=second_layer write_path="
                            + outcome.channel.label() + " writer_source=write_point"
                            + " note=poll_cannot_see_writer | " + outcome.detail);
            return;
        }
        if (ShanhaiStateRules.healthUsable(t.approvedHealth)) {
            final boolean ok = ShanhaiGuardValueLayer.forceHealthField(sp, t.approvedHealth);
            if (ok) {
                sp.deathTime = 0;
                sp.hurtTime = 0;
            }
            HEALTH_FIXED.incrementAndGet();
            ShanhaiStateLog.emit("health", name(sp),
                    ShanhaiStateLog.num(t.approvedHealth), ShanhaiStateLog.num(found), writer,
                    "restored=" + ok + " via=second_layer write_path=baseline_field"
                            + " getMaxHealth=" + max + " writer_source=write_point"
                            + " note=getMaxHealth_unusable_used_baseline");
            return;
        }
        ShanhaiStateLog.emit("health", name(sp), "?", ShanhaiStateLog.num(found), writer,
                "restored=false via=failed_probe getMaxHealth=" + max
                        + " baseline=" + t.approvedHealth + " note=no_usable_target");
    }

    /** 应当的血量：优先 {@code getMaxHealth()}，不可用则用基线（再不可用 ⇒ 原样返回，由判据判 KEEP）。 */
    private static float healthExpected(ServerPlayer sp, Track t) {
        final float max = sp.getMaxHealth();
        if (ShanhaiStateRules.healthUsable(max)) {
            return max;
        }
        return t != null ? t.approvedHealth : max;
    }

    /** 读血（读不出来 ⇒ {@code NaN}，**不假装是 0**）。 */
    private static float safeHealth(ServerPlayer sp) {
        try {
            return sp.getHealth();
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    /** 同步血量字段真值（读不到 ⇒ {@code NaN}；与本体 {@code ShanhaiGuardValueLayer} 同一份实现）。 */
    private static float readFieldHealth(ServerPlayer sp) {
        try {
            return ShanhaiGuardValueLayer.readHealthField(sp);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    // ═══════════════ 🆕 2026-11-20：第二层的 ④ 被移除（复用本体唯一那条 revive 路径） ═══════════════

    /**
     * 🔴 <b>第二层第 ④ 项：被移除</b>。
     *
     * <h2>为什么这里只写一行包装、判据与动作全在别人手里</h2>
     * 用户口径：<b>「把它挪进/复用第二层，别写两套」</b>。
     * 那条"见 {@code isRemoved()} ⇒ 放回位置 → {@code revive()} → {@code addFreshEntity()}（判返回值）"
     * 的路径本来就在本体 {@code ShanhaiGuardEvents.guardOne} 里，且带三条硬约束
     * （过滤正常离开／顺序死／判返回值）。⇒ 2026-11-20 把它抽成
     * {@link ShanhaiGuardEvents#rescueRemovedNow}，<b>两边调同一个方法</b>，
     * 本类只负责"在第二层的采样点上发现它、并补一条 {@code field=removed} 的读数"。
     *
     * <p>⚠️ 采样点说明（诚实边界）：被移除的实体<b>不会再收到 {@code PlayerTickEvent}</b>，
     * 所以这一条真正的采样点是 {@link #onServerTickStart}（{@code ServerTickEvent.START}）——
     * 那里每次都跑，且对每个受保护玩家都看一眼 {@code isRemoved()}。
     *
     * @param sp 受保护玩家
     * @param t  跟踪状态（本方法不用它，留作签名一致）
     */
    private static void checkRemoved(ServerPlayer sp, Track t) {
        if (!sp.isRemoved()) {
            return;
        }
        final boolean rescued = ShanhaiGuardEvents.rescueRemovedNow(sp);
        if (!rescued) {
            // 没救回来 / 按规矩不动手（正常离开）⇒ 本体那边已经有一行 KIND_REMOVED，这里不重复刷
            return;
        }
        REMOVED_FIXED.incrementAndGet();
        ShanhaiStateLog.emit("removed", name(sp), "present", "removed",
                ShanhaiViolationProbe.writerForCorrection("remove", sp),
                "restored=true via=guard_events_revive(shared_impl) writer_source=write_point");
    }

    // ═══════════════ 🆕 2026-11-20：决定性拦截 —— tickDeath 整个不跑（mixin 的入口） ═══════════════

    /**
     * 🔴 <b>{@code LivingEntity#tickDeath} 的 HEAD（{@code m_6153_}，由 {@code ShanhaiTickDeathGuardMixin} 调）</b>。
     *
     * <h2>为什么这一刀比"归零 deathTime"更硬</h2>
     * <pre>
     *   tickDeath:  ++deathTime;  if (deathTime &gt;= 20 && !client && !isRemoved) { broadcast(60); remove(KILLED); }
     *   baseTick :  if (isDeadOrDying() && shouldTickDeath(this)) tickDeath();     ← 唯一调用点
     * </pre>
     * 第二层原来的 ③（{@link #checkLife} 里归零 {@code deathTime}）跑在
     * {@code PlayerTickEvent.START}（{@code Player#tick:214}），那是<b>本方法之前</b>；
     * 但如果 {@code ++deathTime} 这句被对手 redirect 成"直接写 20"（KEYCLASS 扫描里存在注入 id
     * {@code redirectDeathTimeSet}），清零与阈值就在<b>一次调用内</b>前后脚发生 ⇒ 归零永远追不上。
     * 在 HEAD 上 cancel ⇒ 连 {@code ++deathTime} 都不执行 ⇒ 白烟（byte 60）与 {@code remove(KILLED)} 都不发生。
     *
     * <h2>🆕 2026-11-20 22:56：光挡住不够 —— cancel 之前必须先把血补回来</h2>
     * <pre>
     *   现场：field=tickDeath ×19（cancel 成功 ⇒ 白烟与 remove 都没发生）
     *         但 client_death_screen_opening hp=-Infinity ×5
     *         第二层"每 tick 对账补血"一条日志都没有（field=health 命中 0）⇒ 那条腿不会来救
     *   ⇒ 挡住"死的过程"与"把血掰回来"是两件事。值层仍是 -Infinity ⇒ isDeadOrDying() 恒真
     *      ⇒ 原版每 tick 都还想走死亡流程、客户端一直开着死亡界面。
     *   ⇒ 顺序改成：判受保护 → 【补血 ＋ 归零】→ cancel。
     *     补血<b>复用全工程唯一一份复位动作</b> {@link ShanhaiGuardValueLayer#resetDeathFlow}
     *     （＝ ② 每 tick 看门狗 / ④ die 拒绝调的那一个：两条腿补血 ＋ 两个计时器归零），
     *     本类<b>不另写一套写血口径</b>。
     *   {@code getMaxHealth()} 不可用（{@code <=0} ／ {@code NaN} ／ {@code ±∞}）⇒ <b>一个字节都不写</b>
     *     （硬写会把血量字段写成 NaN，比 0 更糟；与 {@link #checkLife} 同一条纪律）。
     * </pre>
     *
     * <h2>🔴 只对受保护玩家生效（负向路径逐条写死）</h2>
     * <ol>
     *   <li>不是 {@code ServerPlayer} ⇒ <b>在调用任何判定之前</b>就 return（怪物/掉落物/别的实体：
     *       一个字段都不碰，也不多跑一次物品栏扫描）；</li>
     *   <li>客户端 / level 为空 ⇒ return（客户端本来就不会走 remove 分支）；</li>
     *   <li>{@link ShanhaiGuardEvents#isGuarded} 为 false ⇒ return（<b>唯一</b>受保护入口：持有 ／ 登记过 ／ 宽限窗口）；</li>
     *   <li>本方法抛任何 Throwable ⇒ 返回 false ⇒ <b>不 cancel</b>（退化成原版，绝不给别人加行为）。</li>
     * </ol>
     *
     * @param entity mixin 传进来的 {@code this}（{@code Object}：mixin 类体不许引 MC 成员）
     * @return true = 这一次已经取消（调用方 {@code ci.cancel()}）；false = 原版照走
     */
    public static boolean onTickDeathHead(Object entity) {
        try {
            if (!(entity instanceof ServerPlayer sp)) {
                return false;                       // 🔴 非玩家：一个字都不改
            }
            if (sp.level() == null || sp.level().isClientSide()) {
                return false;
            }
            if (!ShanhaiGuardEvents.isGuarded(sp)) {
                return false;                       // 🔴 唯一受保护入口（不另写一套判据）
            }
            final int deathSeen = sp.deathTime;
            final int hurtSeen = sp.hurtTime;
            // ── 🆕 2026-11-20 22:56 缺口：先【补血】，再归零，最后 cancel ──
            //   只挡 tickDeath 挡不住"血已经被写成 -Infinity"：值层没补回来 ⇒ isDeadOrDying() 恒真
            //   ⇒ 原版每 tick 都还想走死亡流程、客户端一直开着死亡界面
            //     （现场：field=tickDeath ×19，但 client_death_screen_opening hp=-Infinity ×5）。
            //   🔴 补血走全工程【唯一一份】复位动作（＝ ② 每 tick 看门狗 / ④ die 拒绝调的那一个）：
            //      两条腿补血（setHealth 自证 → 直写同步血量字段，绕开任何人对 setHealth 的 cancel）
            //      ＋ deathTime/hurtTime 归零。本类不另写一套写血口径。
            //   🔴 getMaxHealth() 不可用（<=0 / NaN / ±∞）⇒ 一个字节都不写：硬写会把血量字段写成 NaN，
            //      比 0 更糟（与 checkLife 同一条纪律）。
            //   读数口径与 checkLife 相同：优先【同步血量字段真值】，读不到才退回 getHealth()。
            final float fieldBefore = readFieldHealth(sp);
            final float hpBefore = Float.isNaN(fieldBefore) ? safeHealth(sp) : fieldBefore;
            final ShanhaiGuardValueLayer.HealOutcome heal =
                    ShanhaiStateRules.healthUsable(sp.getMaxHealth())
                            ? ShanhaiGuardValueLayer.resetDeathFlow(sp)
                            : null;
            final float fieldAfter = readFieldHealth(sp);
            final float hpAfter = Float.isNaN(fieldAfter) ? safeHealth(sp) : fieldAfter;
            // 「归零」与第二层 ③ 同一个口径（判据住在纯 JDK 核里，这里只落写）
            sp.deathTime = 0;
            sp.hurtTime = 0;
            TICK_DEATH_BLOCKED.incrementAndGet();
            ShanhaiStateLog.emit("tickDeath", name(sp), "death_time=0", "death_time=" + deathSeen,
                    "none_poll",
                    "拦截 tickDeath（受保护玩家，白烟与 remove 都不会发生）"
                            + " at=LivingEntity#m_6153_@HEAD hurt_time=" + hurtSeen
                            + " judge=" + (ShanhaiStateRules.deathTimerNeedsZero(deathSeen, hurtSeen)
                                    ? "second_layer_deathtime" : "dying_but_timer_zero")
                            + " healed=" + (heal != null && heal.landed())
                            + " hp_before=" + ShanhaiStateLog.num(hpBefore)
                            + " hp_after=" + ShanhaiStateLog.num(hpAfter)
                            + " heal_channel=" + (heal == null ? "max_unusable" : heal.channel.label())
                            // 🆕 读取侧钳制的两个读数（只加字段，不改本行任何既有字段的算法）：
                            //   read_clamped=<本拍是否钳过读取> / hp_field=<同步血量字段真值，读不到=unknown>
                            //   目的：一眼看出"字段是 -∞、但读取被钳成 20"；若 read_clamped=false 而
                            //   hp_field=-Infinity ⇒ 读侧没钳住（读侧那条注入没挂上/抛异常），不必再猜。
                            + ShanhaiHealthReadClampMixin.Core.fieldsForTickDeathLog((Object) sp));
            return true;
        } catch (Throwable ignored) {
            return false;                           // 我们坏了 ⇒ 原版行为逐字不变
        }
    }

    // ═══════════════ 🆕 2026-11-20：全服最早的那个写点（ServerTickEvent.START） ═══════════════

    /**
     * 🔴 <b>全服最早的一个可写点</b>：{@code ServerTickEvent.START}（在任何 {@code ServerLevel#tick}、
     * 任何实体 tick 之前）。
     *
     * <h2>它兜的是什么（{@link #reconcile} 兜不到的两类）</h2>
     * <ol>
     *   <li><b>被移除的玩家</b>：他<b>不再 tick</b> ⇒ {@code PlayerTickEvent} 一次都不会来
     *       ⇒ 只有这里还看得到他（{@link #checkRemoved}）；</li>
     *   <li><b>{@code deathTime} 的"跨 tick 蓄水"</b>：对手可以在<b>自己的</b>实体 tick 里把我们的
     *       {@code deathTime} 写成 19，然后我们的玩家下一拍 {@code tickDeath} 一加就够 20。
     *       这里把他归零，等于<b>在他 tick 之前</b>再清一次（{@link #reconcile} 里那一次是主力，这是双保险）。</li>
     * </ol>
     *
     * <p>成本：只遍历<b>玩家表</b>（几十个），而且对每个玩家先问一句
     * {@link #protectedNow}（缓存版，不翻物品栏）⇒ 非受保护玩家<b>一个字段都不碰</b>。
     */
    @SubscribeEvent
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.START) {
            return;
        }
        try {
            final MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }
            final List<ServerPlayer> roster = server.getPlayerList().getPlayers();
            final int n = roster.size();
            for (int i = 0; i < n; i++) {
                final ServerPlayer sp = roster.get(i);
                if (sp == null) {
                    continue;
                }
                try {
                    if (!protectedNow(sp)) {
                        continue;                       // 不受保护 ⇒ 一个字段都不碰（与全工程同一道闸门）
                    }
                    // ④ 被移除（这一类玩家已经不 tick 了，只有这里还看得到）
                    if (sp.isRemoved()) {
                        checkRemoved(sp, null);
                        continue;
                    }
                    // ③ 白烟计时器（在别人 tick 之前的双保险）
                    if (sp.deathTime != 0) {
                        final int was = sp.deathTime;
                        sp.deathTime = 0;
                        DEATH_TIMER_ZEROED.incrementAndGet();
                        ShanhaiStateLog.emit("deathtime", name(sp), "death_time=0", "death_time=" + was,
                                "none_poll", "restored=true via=server_tick_start judge=tickDeath_threshold_20");
                    }
                } catch (Throwable ignored) {
                    // 单个玩家出错不许影响别人（本层坏掉的结果只能是"这一拍没纠正"）
                }
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            ShanhaiMod.LOGGER.warn("{} state_authority_failed where=server_tick_start err={}", PREFIX, t.toString());
        }
    }

    // ═══════════════════════════════ ④ END：把这一拍的结果记成认可值 ═══════════════════════════════

    private static void record(ServerPlayer sp) {
        final Track t = trackOf(sp);
        if (t == null) {
            return;
        }
        final Vec3 now = sp.getDeltaMovement();
        adoptMomentum(t, now);
        t.approvedOrigin = ShanhaiSelfInput.origin();
        t.posX = sp.getX();
        t.posY = sp.getY();
        t.posZ = sp.getZ();
        t.ticksSeen++;
        t.ourMomentum = false;
        t.protectedNow = isProtected(sp);
        // 🆕 2026-11-20：把这一拍 END 的"合法血量"记成基线（只记可用的值，绝不把 NaN/0 记成基线）
        final float fieldNow = readFieldHealth(sp);
        final float bloodNow = Float.isNaN(fieldNow) ? safeHealth(sp) : fieldNow;
        if (ShanhaiStateRules.healthUsable(bloodNow)) {
            t.approvedHealth = bloodNow;
        }
        t.ready = true;
        if (t.windowLeft > 0) {
            t.windowLeft--;
            if (t.windowLeft == 0) {
                t.invSnapshot = null;       // 窗口结束就不再留快照（省内存，也不误判）
            } else {
                t.invSnapshot = ShanhaiStateInventory.snapshot(sp);
            }
        }
        if (sp.isRemoved()) {
            synchronized (TRACKS) {
                TRACKS.remove(sp.getUUID());
            }
        }
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /** 每个玩家一份的快照（身份用<b>实例</b>：换实例＝重生/换维度 ⇒ 整份重建，不纠正）。 */
    private static final class Track {

        final ServerPlayer owner;

        Track(ServerPlayer owner) {
            this.owner = owner;
        }

        boolean ready;
        boolean protectedNow;
        int ticksSeen;

        /** 我们认可的动量（玩家自己那次写入之后的值 / 上一拍 END 的值）。 */
        double approvedX;
        double approvedY;
        double approvedZ;

        /** 认可值是怎么来的（只进日志，不参与判定）。 */
        ShanhaiSelfInput.Origin approvedOrigin = ShanhaiSelfInput.Origin.NONE;

        /** 上一拍 END 的位置（只作读数／证据用）。 */
        double posX;
        double posY;
        double posZ;

        /** 位置锚点（窗口打开时 / 玩家自己的移动包写完之后）。 */
        double posAnchorX;
        double posAnchorY;
        double posAnchorZ;
        int posAnchorTick;

        /** 窗口剩余 tick。 */
        int windowLeft;

        /** 我们自己刚写过动量（{@link #noteOurMomentum(Entity)}）。 */
        boolean ourMomentum;

        /** 这一包移动包已经记过一次（HEAD 置了、TAIL 要消费掉）。 */
        boolean inputSeenInPacket;

        /** 进门那一刻（HEAD）的动量：TAIL 上"值变了"才证明这一包自己改过动量。 */
        double inputHeadMx;
        double inputHeadMy;
        double inputHeadMz;

        /** 进门那一刻（HEAD）的位置：用来判"这一包声称的位移是否人腿解释得通"。 */
        double inputHeadX;
        double inputHeadY;
        double inputHeadZ;

        /** 窗口内的物品栏权威快照。 */
        ShanhaiStateInventory.Snapshot invSnapshot;

        /**
         * 🆕 2026-11-20：<b>我们认可的血量基线</b>（上一拍 END 读到的合法值）。
         *
         * <p>用途：{@code getMaxHealth()} 被写坏（NaN / ±∞ / 非正）时，第二层还有东西可写
         * ⇒ 不至于"因为上限坏了就整项放手"。初值 0（未建立）由
         * {@link ShanhaiStateRules#healthUsable} 判成"不可用"，不会被误写成 0。
         */
        float approvedHealth;
    }

    private static Track trackOf(ServerPlayer sp) {
        final UUID id = sp.getUUID();
        synchronized (TRACKS) {
            Track t = TRACKS.get(id);
            if (t == null || t.owner != sp) {
                // 新玩家 / 新实例（重生、换维度都会换 ServerPlayer 实例）⇒ 整份重建
                t = new Track(sp);
                TRACKS.put(id, t);
                prune();
            }
            return t;
        }
    }

    /** 超过上限就丢掉"已经不在了"的旧条目（只增不覆盖，绝不误删活人）。 */
    private static void prune() {
        if (TRACKS.size() <= MAX_TRACKED) {
            return;
        }
        final Iterator<Map.Entry<UUID, Track>> it = TRACKS.entrySet().iterator();
        while (it.hasNext() && TRACKS.size() > MAX_TRACKED) {
            final Track t = it.next().getValue();
            if (t == null || t.owner.isRemoved() || !t.owner.isAlive()) {
                it.remove();
            }
        }
    }

    private static void adoptMomentum(Track t, Vec3 v) {
        t.approvedX = v == null ? 0.0D : v.x;
        t.approvedY = v == null ? 0.0D : v.y;
        t.approvedZ = v == null ? 0.0D : v.z;
    }

    /** 把位置锚点挪到"现在"（玩家自己的移动包写完之后 / 窗口打开 / 我们刚还原完）。 */
    private static void reanchor(ServerPlayer sp, Track t) {
        t.posAnchorX = sp.getX();
        t.posAnchorY = sp.getY();
        t.posAnchorZ = sp.getZ();
        t.posAnchorTick = sp.tickCount;
    }

    private static void openWindow(ServerPlayer sp, Track t, String reason) {
        t.windowLeft = WINDOW_TICKS;
        reanchor(sp, t);
        if (t.invSnapshot == null) {
            t.invSnapshot = ShanhaiStateInventory.snapshot(sp);
        }
        WINDOWS_OPENED.incrementAndGet();
        // ⚠️ 窗口本身不打日志（每次挨打都打会刷屏）；真动手时才有 `拦截 状态被改写 …` 那一行
    }

    /**
     * 这次写入的来源（{@link ShanhaiSelfInput} ＋"我们自己的写入"那条旁路）。
     *
     * <p>{@code noteOurMomentum} 置的那个旗标也算 {@code self_guard}（历史接口保留）。
     */
    private static ShanhaiSelfInput.Origin originOf(Track t) {
        final ShanhaiSelfInput.Origin o = ShanhaiSelfInput.origin();
        if (o.isSelf()) {
            return o;
        }
        return t != null && t.ourMomentum ? ShanhaiSelfInput.Origin.SELF_GUARD : ShanhaiSelfInput.Origin.NONE;
    }

    /** 来源的机器名（日志用）。 */
    public static String originToken(ShanhaiSelfInput.Origin origin) {
        return origin == null ? "none" : origin.token();
    }

    /**
     * 受保护 = 携带「创始现实修改模块」（**与本体 {@code guardOne} 同一判据**）。
     *
     * <h2>🔴 2026-11-20：判据改成"本体那一个"（这是"模块被清空 ⇒ 全套防护失效"的一半修法）</h2>
     * 旧版只问 {@code ShanhaiHoloMenuNetwork.moduleInInventory(sp)}（<b>只看当前背包</b>）——
     * 而本体那套（伤害 / 死亡 / die 拒绝）问的是
     * {@link ShanhaiGuardEvents#isGuarded}（<b>当前持有 ＋ 登记过 ＋ 宽限窗口</b>）。
     * 两套判据不一致 ⇒ 模块被清空的那一帧：
     * <pre>
     *   本体那条线：isGuarded 仍 true（登记过） ⇒ 挡伤害照旧
     *   本类这条线：moduleInInventory=false     ⇒ 第二层【整层停工】
     * </pre>
     * 用户实测的破防形态就是这个。⇒ 现在<b>优先问本体那一个</b>，
     * 只有它判不了（抛异常）才退回"只看当前背包"的旧口径（保守：判不了 ⇒ 不碰）。
     */
    private static boolean isProtected(ServerPlayer sp) {
        try {
            if (ShanhaiGuardEvents.isGuarded(sp)) {
                return true;      // 当前持有 ／ 登记过 ／ 模块刚丢但还在宽限窗口内
            }
        } catch (Throwable ignored) {
            // 本体那一套判不了 ⇒ 退回下面那条旧口径（绝不当成"受保护"乱写）
        }
        try {
            return ShanhaiHoloMenuNetwork.moduleInInventory(sp);
        } catch (Throwable t) {
            return false;   // 判不了 ⇒ 不碰（保守）
        }
    }

    /** 供 {@code ShanhaiViolationProbe} 用（同一个受保护判据，只此一份）。 */
    public static boolean protectedPlayer(ServerPlayer sp) {
        return sp != null && isProtected(sp);
    }

    /**
     * <b>缓存版</b>的"这个玩家现在受保护吗"（给<b>写入口</b>用：那里每一 tick 会被调好几次，
     * 不可能每次都翻一遍物品栏）。
     *
     * <p>缓存来自 {@link #reconcile}（START）与 {@link #record}（END）—— 与本类自己的判据<b>同一份</b>，
     * 所以"刹车看得见的玩家"与"写回管得着的玩家"永远是同一批。
     *
     * <p>⚠️ 只有 {@code ready}（至少跑完一拍）之后才给 true：新玩家第一拍先建基线，不判、也不刹。
     *
     * @param sp 玩家
     * @return true = 现在就按"受保护"对待（携带模块 ／ 上一拍刚确认过）
     */
    public static boolean protectedNow(ServerPlayer sp) {
        if (sp == null) {
            return false;
        }
        try {
            final Track t = trackOf(sp);
            return t != null && t.ready && t.protectedNow;
        } catch (Throwable ignored) {
            return false;      // 判不了 ⇒ 不碰（保守）
        }
    }

    private static boolean exemptOf(ServerPlayer sp, Track t) {
        try {
            return ShanhaiStateRules.exempt(
                    sp.isSpectator(),
                    sp.isPassenger(),
                    sp.isFallFlying(),
                    sp.isChangingDimension(),
                    sp.isDeadOrDying(),
                    t.ticksSeen);
        } catch (Throwable e) {
            return true;    // 判不了 ⇒ 当成豁免（宁可不动）
        }
    }

    /** 从 {@code ServerGamePacketListenerImpl} 取玩家（<b>反射面之外唯一的一次成员访问</b>，只在普通类里做）。 */
    private static ServerPlayer playerOf(Object listener) {
        if (!(listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl impl)) {
            return null;
        }
        return impl.player;
    }

    private static String name(ServerPlayer sp) {
        try {
            return sp.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 只读：现在跟踪着哪些玩家（命令/报告用）。 */
    public static Set<String> trackedNames() {
        final Set<String> out = new HashSet<>();
        synchronized (TRACKS) {
            for (Track t : TRACKS.values()) {
                if (t != null && t.owner != null) {
                    out.add(name(t.owner));
                }
            }
        }
        return out;
    }
}
