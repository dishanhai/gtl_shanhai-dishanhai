package com.shanhai.add.guard.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.guard.ShanhaiMotionBrake;
import com.shanhai.add.guard.ShanhaiMotionBrakeRules;
import com.shanhai.add.guard.stack.ShanhaiViolationProbe;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
 * 山海 · <b>「玩家状态由我们独占权威」的动量/位置/物品栏那一半</b>（用户口径的<u>主线 B</u>）。
 *
 * <h2>🔴 2026-11-08 之后判据换过一次（用户当场点名的那个错）</h2>
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
    private static final AtomicLong WINDOWS_OPENED = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong SELF_INPUT_ADOPTED = new AtomicLong();
    private static final AtomicLong SELF_GUARD_ADOPTED = new AtomicLong();

    /** 有多少"移动包声称的位移"人腿解释不通（＝疑似瞬移，锚点不动、交给轮询判据）。 */
    private static final AtomicLong IMPLAUSIBLE_INPUT = new AtomicLong();

    private ShanhaiStateAuthority() {}

    // ═══════════════════════════════ 装配 / 自证 ═══════════════════════════════

    /** 一行开机自证（真值仍要看 {@code 拦截 状态被改写} / {@code motion_brake} 那两个行）。 */
    public static String armedLine() {
        return "state_authority armed " + ShanhaiStateSwitches.statusLine()
                + " window_ticks=" + WINDOW_TICKS
                + " judge_motion=self_attribution（self_input/self_guard 一律采纳；无阈值）"
                + " judge_pos=self_attribution+anchor_budget(" + ShanhaiStateRules.SPRINT_BUDGET_PER_TICK + "/tick)"
                + " judge_inv=lost>=" + (int) Math.round(ShanhaiStateRules.INVENTORY_LOST_FRACTION * 100) + "%"
                + " brake_at_write_head=on（" + ShanhaiMotionBrakeRules.Tuning.production() + "）"
                + " " + ShanhaiSelfInput.statusLine()
                + " logger=" + (ShanhaiStateLog.emitted() >= 0 ? "bound" : "?");
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

    /** 受保护 = 携带「创始现实修改模块」（与本体 {@code guardOne} 同一判据）。 */
    private static boolean isProtected(ServerPlayer sp) {
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
