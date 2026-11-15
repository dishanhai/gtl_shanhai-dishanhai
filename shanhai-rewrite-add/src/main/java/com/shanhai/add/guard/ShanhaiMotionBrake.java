package com.shanhai.add.guard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.guard.stack.ShanhaiViolationProbe;
import com.shanhai.add.guard.state.ShanhaiSelfInput;
import com.shanhai.add.guard.state.ShanhaiStateAuthority;
import com.shanhai.add.guard.state.ShanhaiStateSwitches;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 山海 · <b>「写入那一瞬就丢」的动量刹车（认识 MC，只负责取值 / 写 / 打日志）</b>。
 *
 * <h2>🔴 它修的是哪一条（2026-11-08 13:41 test2 现场日志给死的结论）</h2>
 * <pre>
 *   [SHANHAI-GUARD] 拦截 状态被改写 field=motion target=dgyjh02804
 *     before=(0.000, -0.585, 0.000) after=(0.000, 2.000, 0.000)
 *     source=none_poll approved_from=none restored=true via=attribution note=restored_wait_injection_stack
 *   ⇒ 用户报「猫还是能推我」：我们看见了、也写回旧速度了（restored=true），但<b>还原发生在下一拍</b>，
 *     这一拍的位置/给客户端的击退包已经按 2.0 走了。
 * </pre>
 * ⇒ 本类挂在 <b>{@code Entity#setDeltaMovement(Vec3)} 的 HEAD</b>：
 * 在<b>写下去之前</b>判，判完这一次写入<b>干脆不发生</b>（{@code ci.cancel()}），
 * 只把 y 分量替它写下去（只清水平）。
 *
 * <h2>四件事的分工（判据一行不写在这里）</h2>
 * <table border="1">
 *   <tr><th>谁</th><th>干什么</th></tr>
 *   <tr><td>{@link ShanhaiMotionBrakeRules}</td>
 *       <td><b>纯 JDK 判据核</b>：判据顺序 ／ 硬闸状态机 ／ 日志字面量（离线 {@code javac} 可单独编译并真跑）</td></tr>
 *   <tr><td>本类</td>
 *       <td>取原版读数 → 喂判据 → 该丢就 {@code setDeltaMovement(0, keepY, 0)} ＋ 打日志 ＋ 抓一次栈</td></tr>
 *   <tr><td>{@code ShanhaiValueWriteProbeMixin}</td><td>HEAD 上问本类"这次要不要取消原方法"（返回 true ⇒ {@code ci.cancel()}）</td></tr>
 *   <tr><td>{@code ShanhaiStateAuthority}</td>
 *       <td>仍然保留<b>下一拍写回</b>那一层（硬闸停手的那 5 秒里就靠它；它没有被动掉）</td></tr>
 * </table>
 *
 * <h2>🔴 三个"必须不"（防上次冻结玩家复现）</h2>
 * <ol>
 *   <li><b>不刹非受保护玩家</b>：{@code ShanhaiStateAuthority.protectedNow(sp)}（缓存版判据，
 *       不每次翻背包）不是 true ⇒ 立刻放行，一个字节都不碰；</li>
 *   <li><b>不刹豁免态</b>：旁观 ／ 载具 ／ 鞘翅 ／ 刚传送 {@value ShanhaiMotionBrakeRules#TELEPORT_SKIP_TICKS} tick
 *       ／ 死亡中 ⇒ 放行；</li>
 *   <li><b>不无限刹</b>：连续 {@value ShanhaiMotionBrakeRules#SUSTAIN_TICKS} tick（6 秒）都在丢<b>模糊带</b> ⇒
 *       {@link ShanhaiMotionBrakeRules.Gate} 自动停手 {@value ShanhaiMotionBrakeRules#GATE_OFF_TICKS} tick（5 秒），
 *       期间打 {@code pass reason=gate_off}，<b>交回上一层的写回逻辑</b>；5 秒后自动复位并打一行 {@code brake_rearmed}。
 *       <b>巨大档除外</b>：{@code reason=foreign_huge}（horiz&gt;2.0 或 |Δy|&gt;1.5）不计入熔断、
 *       也不受熔断影响 —— 熔断只放行模糊带，明确的攻击照丢。</li>
 * </ol>
 *
 * <h2>为什么丢弃时是"写 (0, keepY, 0)"而不是"什么都不写"</h2>
 * <pre>
 *   ci.cancel() ⇒ 原方法（包括它后面的原版逻辑）不跑；
 *   我们替它写 (0, keepY, 0) ⇒ 水平清掉，<b>y 保留</b> ⇒ 重力 / 跳跃 / 下落一个都不受影响（不可能卡死/悬空）。
 *   keepY 的例外：|Δy| &gt; 1.0 且上抛（被弹飞）⇒ 0；NaN / ±Inf ⇒ 0（绝不把怪值写进字段）。
 *   ⚠️ 这次替写本身也会进 HEAD，但那时标记是 {@code self_guard}（{@link ShanhaiStateAuthority#enterOurScope()}）
 *      ⇒ 判据走 a（self）⇒ 放行 ⇒ <b>不会递归</b>。
 * </pre>
 *
 * <h2>日志（用户点名的那一行，1 秒最多一行）</h2>
 * <pre>
 *   [SHANHAI-GUARD] motion_brake why=drop|pass \
 *        reason=self|vanilla_small|foreign_big|foreign_huge|gate_off dv=(x,y,z) horiz=… vert=… folded=N
 *   [SHANHAI-GUARD] brake_disabled reason=sustained drop_ticks=120 gate_off_ticks=100 now_tick=… until_tick=… folded=N
 *   [SHANHAI-GUARD] brake_rearmed dropped_window=120 now_tick=…        （一次一行，不折叠）
 * </pre>
 * {@code why=pass} <b>只在 {@code reason=gate_off} 时打</b>（用户口径：别刷屏；"玩家自己在走"每拍都有）。
 * {@code folded=N} 与 {@code ShanhaiStateLog} 同一套语义：被 1/s 挡下的次数<b>折进下一条真正落盘的行</b>。
 * 真丢弃时<b>另外</b>抓一次栈（{@code violation_stack where=motion_brake}，同样 1/s），
 * 这样"是它在推还是我在走"能一眼分开：有 {@code why=drop} ＋ 栈 ⇒ 是它在推；没有 ⇒ 是你在走。
 *
 * <h2>🔴 诚实边界</h2>
 * <ul>
 *   <li><b>只看得见 {@code setDeltaMovement}</b>：直接写字段（{@code putfield} ／ 反射 ／ 别人的 {@code @Shadow}）
 *       绕得开这个方法 ⇒ 本类<b>根本不会被叫到</b>（与 {@code violation_stack} 同一条边界，报告里写明）。</li>
 *   <li><b>判的是 Δv</b>（这次写入改了多少）。分几拍累加（每拍 Δ 小）拦不到 ⇒ 那几拍仍由写回逻辑兜。</li>
 *   <li><b>不补发速度包</b>：丢弃后不额外发 {@code ClientboundSetEntityMotionPacket}
 *       （补包仍由 {@code ShanhaiStateAuthority} 的写回路径做）。理由：值根本没变成大的，
 *       原版 {@code ServerEntity#sendChanges} 发出去的也就是"没被推"的那个值。</li>
 *    <li><b>没有在游戏里实测</b>（本轮只做离线自证）：真机上会不会误刹正常走路
 *       ／ 那只猫到底走不走 {@code setDeltaMovement}，都要等日志。</li>
 * </ul>
 */
public final class ShanhaiMotionBrake {

    /** 与守卫同一套前缀。 */
    public static final String PREFIX = ShanhaiMotionBrakeRules.PREFIX;

    /** 一个玩家最多多久一行 {@code motion_brake}（毫秒）。 */
    public static final long MIN_INTERVAL_MILLIS = 1000L;

    /** 跟踪的玩家上限（防泄漏；玩家数远小于它）。 */
    public static final int MAX_TRACKED = 64;

    /** 限流表的形态上限（防泄漏）。 */
    public static final int MAX_SHAPES = 64;

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    /** 形态（{@code 词头|玩家名}）⇒ 上次落盘的时刻。 */
    private static final Map<String, Long> LAST = new LinkedHashMap<>();

    private static final AtomicLong EMITTED = new AtomicLong();
    private static final AtomicLong FOLDED = new AtomicLong();
    private static final AtomicLong DROPS = new AtomicLong();
    private static final AtomicLong PASSES = new AtomicLong();
    private static final AtomicLong GATE_PASSES = new AtomicLong();
    private static final AtomicLong GATE_TRIPS = new AtomicLong();
    private static final AtomicLong REARMS = new AtomicLong();
    private static final AtomicLong HUGE_DROPS = new AtomicLong();
    private static final AtomicLong BLUR_DROPS = new AtomicLong();
    private static final AtomicLong SELF_PASSES = new AtomicLong();
    private static final AtomicLong EXEMPT_PASSES = new AtomicLong();
    private static final AtomicLong SMALL_PASSES = new AtomicLong();
    private static final AtomicLong SWITCH_OFF = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong WRITES_SEEN = new AtomicLong();

    private ShanhaiMotionBrake() {}

    // ═══════════════════════════════ 写入口（由 mixin 的 HEAD 调） ═══════════════════════════════

    /**
     * {@code Entity#setDeltaMovement(Vec3)} 的 HEAD：<b>先判、再决定这一次写入要不要发生</b>。
     *
     * <p>⚠️ 本方法在<b>每一条实体</b>的动量写入上都会被叫到 ⇒ 第一行必须是
     * {@code instanceof ServerPlayer}（便宜），之后才是"受保护玩家"（缓存判据）。
     *
     * @param entity   被写的实体（{@code Object}：mixin 类体不许引 MC 成员）
     * @param incoming 这一次要写进去的 {@link Vec3}（原方法的入参；以 {@code Object} 接）
     * @return true = <b>丢弃这次写入</b>（调用方必须 {@code ci.cancel()}）；
     *         false = 放行（原方法照跑）
     */
    public static boolean onMotionWriteHead(Object entity, Object incoming) {
        try {
            if (!(entity instanceof ServerPlayer sp)) {
                return false;                       // 绝大多数实体在这里就走掉，一个字段都不读
            }
            if (sp.level() == null || sp.level().isClientSide()) {
                return false;                       // 客户端不归我们管（那边没有权威）
            }
            if (!(incoming instanceof Vec3 wanted)) {
                return false;                       // 拿不到入参 ⇒ 不猜，放行
            }
            if (!ShanhaiStateAuthority.protectedNow(sp)) {
                return false;                       // 不是受保护玩家 ⇒ 一个字节都不碰
            }
            if (sp.isDeadOrDying()) {
                return false;                       // 死亡中也在豁免里（与 ShanhaiStateRules.exempt 同一口径）
            }
            WRITES_SEEN.incrementAndGet();

            final long nowTick = gameTimeOf(sp);
            final Track t = trackOf(sp);
            final boolean justTeleported = t.observeTeleport(sp, nowTick);
            final boolean switchOn = t.switchOn(nowTick);

            final Vec3 current = sp.getDeltaMovement();
            final ShanhaiSelfInput.Origin origin = ShanhaiSelfInput.origin();
            final ShanhaiMotionBrakeRules.In in = ShanhaiMotionBrakeRules.In.of(
                    switchOn,
                    origin.isSelf(),
                    ShanhaiMotionBrakeRules.exemptKind(
                            sp.isSpectator(), sp.isFallFlying(), sp.isPassenger(), justTeleported),
                    current.x, current.y, current.z,
                    wanted.x, wanted.y, wanted.z);

            final ShanhaiMotionBrakeRules.Decision d = ShanhaiMotionBrakeRules.decide(
                    in, ShanhaiMotionBrakeRules.Tuning.production(), t.gate, nowTick);

            if (d.trippedGate) {
                GATE_TRIPS.incrementAndGet();
                final String shape = "brake_disabled|" + nameOf(sp);
                final long folded = reserveFold(shape);
                if (folded >= 0L) {
                    emitLine(ShanhaiMotionBrakeRules.disabledLine(t.gate, nowTick, folded));
                }
            }

            if (d.rearmedGate) {
                REARMS.incrementAndGet();
                // 复位是"一次一行"的事件（每 100+ tick 才最多一次）⇒ 不走 1/s 折叠，免得被折叠吃掉
                emitLine(ShanhaiMotionBrakeRules.rearmLine(t.gate, nowTick));
            }

            if (d.drop()) {
                DROPS.incrementAndGet();
                if (d.huge()) {
                    HUGE_DROPS.incrementAndGet();       // ③ 巨大档：不计熔断、不受 gate_off 影响
                } else {
                    BLUR_DROPS.incrementAndGet();       // ④ 模糊档：可能把熔断踢开
                }
                applyDrop(sp, d.keepY);
                final String shape = "motion_brake|" + nameOf(sp);
                final long folded = reserveFold(shape);
                if (folded >= 0L) {
                    emitLine(ShanhaiMotionBrakeRules.logLine(d, folded));
                }
                // 真丢弃才抓栈（1/s）：答案就在"是它在推还是我在走"这一行里
                ShanhaiViolationProbe.onMotionBrakeDrop(sp, d.dvx, d.dvy, d.dvz, d.horiz, d.vert);
                return true;
            }

            // ── 放行：只有 gate_off 要打一行（别刷屏） ──
            PASSES.incrementAndGet();
            countPass(d.reason);
            if (d.shouldLog()) {
                GATE_PASSES.incrementAndGet();
                final String shape = "motion_brake|" + nameOf(sp);
                final long folded = reserveFold(shape);
                if (folded >= 0L) {
                    emitLine(ShanhaiMotionBrakeRules.logLine(d, folded));
                }
            }
            return false;
        } catch (Throwable ex) {
            // 🔴 失败姿势：本类坏掉只能是"这一次没刹"，绝不许把 tick 带崩、也绝不许乱丢写入
            ERRORS.incrementAndGet();
            warn("motion_brake_failed where=head err=" + ex);
            return false;
        }
    }

    /** 丢弃：取消原方法之后，替它把 {@code (0, keepY, 0)} 写下去（y 保留 ⇒ 不会卡死/悬空）。 */
    private static void applyDrop(ServerPlayer sp, double keepY) {
        final Vec3 current = sp.getDeltaMovement();
        // 已经就是 (0, keepY, 0) 的话就不用替写了（常见于"连续同一拍丢两次"）
        if (current.x == 0.0D && current.z == 0.0D && current.y == keepY) {
            return;
        }
        ShanhaiStateAuthority.enterOurScope();
        try {
            sp.setDeltaMovement(new Vec3(0.0D, keepY, 0.0D));
        } finally {
            ShanhaiStateAuthority.exitOurScope();
        }
    }

    private static void countPass(ShanhaiMotionBrakeRules.Reason reason) {
        switch (reason) {
            case SELF:
                SELF_PASSES.incrementAndGet();
                break;
            case EXEMPT:
                EXEMPT_PASSES.incrementAndGet();
                break;
            case VANILLA_SMALL:
                SMALL_PASSES.incrementAndGet();
                break;
            case SWITCH_OFF:
                SWITCH_OFF.incrementAndGet();
                break;
            default:
                // GATE_OFF 的计数由 ShanhaiMotionBrakeRules.Gate 自己记（decide 里 noteGateSkip）
                break;
        }
    }

    // ═══════════════════════════════ 逐玩家状态（只住服务端线程） ═══════════════════════════════

    /** 一个受保护玩家的刹车状态（键 = 玩家 UUID；身份比对用实例，换实例 = 重生/换维度 ⇒ 整份重建）。 */
    private static final class Track {

        final ServerPlayer owner;

        /** 硬闸状态机（连续丢弃 ⇒ 停手 60 秒）。 */
        final ShanhaiMotionBrakeRules.Gate gate = new ShanhaiMotionBrakeRules.Gate();

        /** 上次观察"刚传送"是哪个 tick（同一 tick 只消耗一次 2 tick 豁免）。 */
        long lastObserveTick = Long.MIN_VALUE;
        /** 上次看到的实体 id（变了 = 复活）。 */
        int lastEntityId = Integer.MIN_VALUE;
        /** 上次看到的维度。 */
        String lastDimension;
        /** 还剩几个 tick 的"刚传送"豁免。 */
        int teleportSkip;

        /** 开关缓存（每 tick 只读一次配置）。 */
        long switchTick = Long.MIN_VALUE;
        boolean switchEnabled = true;

        Track(ServerPlayer owner) {
            this.owner = owner;
        }

        /** 开关（每 tick 只读一次配置；读失败退回上一次的值）。 */
        boolean switchOn(long nowTick) {
            if (switchTick != nowTick) {
                switchTick = nowTick;
                try {
                    switchEnabled = ShanhaiStateSwitches.momentum();
                } catch (Throwable ignored) {
                    // 读不到就沿用上一次（默认开），绝不让配置层的抖动变成"突然刹车"
                }
            }
            return switchEnabled;
        }

        /**
         * 认出「刚传送」（换维度 ／ 复活=实体 id 变了 ／ {@code isChangingDimension()}），
         * 并<b>按 tick 消耗</b> {@value ShanhaiMotionBrakeRules#TELEPORT_SKIP_TICKS} tick 的豁免。
         *
         * <p>⚠️ 必须按 tick 幂等：一个玩家一 tick 可能写 3~6 次动量，若按"调用次数"消耗，
         * 2 tick 的豁免会被一秒内的 6 次写成"不到一拍就没了"。
         */
        boolean observeTeleport(ServerPlayer sp, long nowTick) {
            if (nowTick == lastObserveTick) {
                return teleportSkip > 0;
            }
            lastObserveTick = nowTick;
            final int id = sp.getId();
            final String dim = dimensionOf(sp);
            final boolean respawned = lastEntityId != Integer.MIN_VALUE && id != lastEntityId;
            final boolean changedDimension = lastDimension != null && dim != null && !lastDimension.equals(dim);
            lastEntityId = id;
            lastDimension = dim;
            if (sp.isChangingDimension() || respawned || changedDimension) {
                teleportSkip = ShanhaiMotionBrakeRules.TELEPORT_SKIP_TICKS;
            }
            if (teleportSkip > 0) {
                teleportSkip--;
                return true;
            }
            return false;
        }
    }

    private static Track trackOf(ServerPlayer sp) {
        final UUID id = sp.getUUID();
        synchronized (TRACKS) {
            Track t = TRACKS.get(id);
            if (t == null || t.owner != sp) {
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

    // ═══════════════════════════════ 日志（1/s／形态，折叠计数） ═══════════════════════════════

    /**
     * 向限流器"占一行"：允许落盘 ⇒ 返回要填进 {@code folded=} 的数（≥0）；
     * 被 1/s 挡下 ⇒ 折叠计数 +1 并返回 {@code -1}（调用方什么都不打）。
     *
     * <p>与 {@code ShanhaiStateLog#emit} 同一套语义（1 行/秒/形态；被挡次数折进下一条真正落盘的行），
     * 独立一份实现 —— 那边打的是 {@code 拦截 状态被改写} 的固定形态，这一族要的是 {@code motion_brake} 的字面量。
     *
     * @param shape {@code 词头|玩家名}
     */
    private static long reserveFold(String shape) {
        synchronized (LAST) {
            final long now = System.currentTimeMillis();
            final Long last = LAST.get(shape);
            if (last != null && now - last < MIN_INTERVAL_MILLIS) {
                FOLDED.incrementAndGet();
                return -1L;
            }
            LAST.put(shape, Long.valueOf(now));
            if (LAST.size() > MAX_SHAPES) {
                final String oldest = LAST.keySet().iterator().next();
                LAST.remove(oldest);
            }
            return FOLDED.getAndSet(0L);
        }
    }

    /** 真正落盘（{@code line} 已经由纯函数组好，含前缀）。 */
    private static void emitLine(String line) {
        EMITTED.incrementAndGet();
        try {
            ShanhaiMod.LOGGER.info("{}", line);
        } catch (Throwable ignored) {
            // 日志出口坏了不许把 tick 带崩
        }
    }

    private static void warn(String text) {
        try {
            ShanhaiMod.LOGGER.warn("{} {}", PREFIX, text);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ 读数 / 清理 ═══════════════════════════════

    /** 一行读数（命令 / 报告 / 开机自证用）。 */
    public static String statusLine() {
        final int tracked;
        final int gated;
        synchronized (TRACKS) {
            tracked = TRACKS.size();
            int g = 0;
            for (Track t : TRACKS.values()) {
                if (t != null && t.gate.off(t.lastObserveTick)) {
                    g++;
                }
            }
            gated = g;
        }
        return "motion_brake writes_seen=" + WRITES_SEEN.get()
                + " drops=" + DROPS.get()
                + " passes=" + PASSES.get()
                + " gate_passes=" + GATE_PASSES.get()
                + " gate_trips=" + GATE_TRIPS.get()
                + " rearmed=" + REARMS.get()
                + " huge_drops=" + HUGE_DROPS.get()
                + " blur_drops=" + BLUR_DROPS.get()
                + " self_pass=" + SELF_PASSES.get()
                + " exempt_pass=" + EXEMPT_PASSES.get()
                + " small_pass=" + SMALL_PASSES.get()
                + " switch_off=" + SWITCH_OFF.get()
                + " emitted=" + EMITTED.get()
                + " folded=" + FOLDED.get()
                + " tracked=" + tracked
                + " gated=" + gated
                + " errors=" + ERRORS.get()
                + " judge={" + ShanhaiMotionBrakeRules.Tuning.production() + "}";
    }

    /** 清空逐玩家状态（停服 / 换局 / 命令重来）。<b>不动</b>累计读数。 */
    public static void reset(String why) {
        synchronized (TRACKS) {
            TRACKS.clear();
        }
        synchronized (LAST) {
            LAST.clear();
        }
        try {
            ShanhaiMod.LOGGER.info("{} motion_brake_reset why={} tracked=0", PREFIX, why);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /** 清空累计读数（离线 / 自检用；游戏里没人调）。 */
    public static void resetCounters() {
        EMITTED.set(0L);
        FOLDED.set(0L);
        DROPS.set(0L);
        PASSES.set(0L);
        GATE_PASSES.set(0L);
        GATE_TRIPS.set(0L);
        REARMS.set(0L);
        HUGE_DROPS.set(0L);
        BLUR_DROPS.set(0L);
        SELF_PASSES.set(0L);
        EXEMPT_PASSES.set(0L);
        SMALL_PASSES.set(0L);
        SWITCH_OFF.set(0L);
        ERRORS.set(0L);
        WRITES_SEEN.set(0L);
    }

    /** 丢过多少次（读数）。 */
    public static long drops() {
        return DROPS.get();
    }

    /** 硬闸踢开过多少次（读数）。 */
    public static long gateTrips() {
        return GATE_TRIPS.get();
    }

    // ═══════════════════════════════ 原版读数（唯一碰 MC 的地方） ═══════════════════════════════

    private static long gameTimeOf(ServerPlayer sp) {
        try {
            return sp.level() == null ? 0L : sp.level().getGameTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String dimensionOf(ServerPlayer sp) {
        try {
            return sp.level() == null ? null : sp.level().dimension().location().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nameOf(ServerPlayer sp) {
        try {
            return sp.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
