package com.shanhai.add.guard.stack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.guard.state.ShanhaiStateAuthority;
import com.shanhai.add.guard.state.ShanhaiStateRules;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 山海 · <b>「原版被写的那一刻」的旁听者</b>（认识 MC，但不改任何行为）。
 *
 * <h2>🔴 它为什么必须住在注入点，而不是轮询点（用户当场纠正过）</h2>
 * <pre>
 *   「你去抓所有代码」
 *   「昨天我说的那个主动检测并废掉敌方的代码，你到底能不能做」
 * </pre>
 * 轮询（tick 末发现"值被改了"）那一刻，改值的人<b>早就返回了</b>，栈上只剩
 * {@code ServerTickEvent → 我们的类} ⇒ 那天 46 条 {@code source=unknown}、{@code strike_caller none}。
 * 这里挂在<b>原版写入方法的最前面</b>（HEAD），栈上还留着调用者 ——
 * 这才是第三方 mod 的帧唯一能出现的位置。
 *
 * <h2>它做什么 / 不做什么</h2>
 * <table border="1">
 *   <tr><th>做</th><th>不做</th></tr>
 *   <tr><td>① 便宜地判"这一次值异常吗、目标是不是受保护玩家"
 *       ② 命中才抓一次栈（{@link ShanhaiViolationStack}，同一 {@code where|target} 1/s）
 *       ③ 打 {@code [SHANHAI-GUARD] violation_stack …} 一行
 *       ④ 把帧喂给 {@code HotfixTriageTap} 的 strike 通道</td>
 *       <td>❌ 不改值 ❌ 不取消 ❌ 不写回 ❌ 不在快路径上打日志
 *           （拦不拦、改不改仍由本体值层与 {@link ShanhaiStateAuthority} 决定）</td></tr>
 * </table>
 *
 * <h2>为什么阈值只用来"决定要不要抓栈"</h2>
 * {@code HEALTH_DROP_ALARM} 是<b>采样判据</b>，不是拦截判据：
 * 它只决定"这次写血要不要留一条栈"，拦不拦还是本体那套。所以它不影响任何玩家可见行为。
 * 3 颗心的依据：本体 {@code guardOne} 每 tick 把血补满 ⇒ 受保护玩家身上
 * <b>任何一次把血削掉 ≥3 颗心的写入</b>都值得留证据（正常挨打是 1~2 颗心且立刻被补回）。
 */
public final class ShanhaiViolationProbe {

    /** 与守卫同一套前缀（纯字面量，避免本类依赖 {@code ShanhaiStateLog} 的加载顺序）。 */
    public static final String PREFIX = "[SHANHAI-GUARD] ";

    /**
     * 「一次写血削掉这么多就算可疑」的采样阈值（3 颗心 = 6.0）。
     *
     * <p>⚠️ 只决定要不要抓栈，<b>不是</b>拦截判据（见类注释）。
     */
    public static final float HEALTH_DROP_ALARM = 6.0F;

    private static final Map<String, AtomicLong> BY_WHERE = new LinkedHashMap<>();

    /** {@code guard_motion_skip} 的限流时间戳（1 行 / 5 秒）。 */
    private static volatile long GUARD_SKIP_LAST_MS;

    private ShanhaiViolationProbe() {}

    // ═══════════════════════════════ 原版写入点（由 mixin 在 HEAD 调） ═══════════════════════════════

    /**
     * {@code LivingEntity#setHealth(float)} 的 HEAD。
     *
     * <p>判据（缺一不可，按<b>从便宜到贵</b>的顺序）：
     * <ol>
     *   <li>被写的是玩家（{@code instanceof ServerPlayer}，最便宜）；</li>
     *   <li>这次值是"异常"：{@code newValue <= 0}（要命）或 {@code newValue < 当前值 - }{@link #HEALTH_DROP_ALARM}；</li>
     *   <li>目标是受保护玩家（要遍历物品栏，最贵 ⇒ 放最后）；</li>
     *   <li>同一 {@code health|玩家} 1 秒内只抓一次（在 {@link ShanhaiViolationStack} 里）。</li>
     * </ol>
     *
     * @param self      {@code (Object) this}（mixin 类体不许引 MC 成员）
     * @param requested 原入参（此刻还没写进 {@code health}）
     */
    public static void guardSetHealthHead(Object self, float requested) {
        try {
            if (!(self instanceof ServerPlayer sp)) {
                return;                       // 生物死亡也走 setHealth(0)，这一步先把它们全部挡掉
            }
            final float current = ((LivingEntity) self).getHealth();
            final boolean killing = requested <= 0.0F;
            final boolean bigDrop = requested < current - HEALTH_DROP_ALARM;
            if (!killing && !bigDrop) {
                return;
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("health", sp, "requested=" + ShanhaiStateLogNum.num(requested)
                    + " current=" + ShanhaiStateLogNum.num(current)
                    + " killing=" + killing + " big_drop=" + bigDrop);
        } catch (Throwable ignored) {
            // 旁听失败只意味着"这一次没有栈"，绝不许影响写血本身
        }
    }

    /**
     * {@code ServerPlayer#die(DamageSource)} / {@code LivingEntity#die(DamageSource)} 的 HEAD。
     *
     * <p>判据：目标是受保护玩家（判死这件事本身没有"值"可看）。
     *
     * @param self {@code (Object) this}
     * @param site 哪一个注入点（{@code ServerPlayer#die} / {@code LivingEntity#die}）
     */
    public static void dieHead(Object self, String site) {
        try {
            if (!(self instanceof ServerPlayer sp)) {
                return;
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("die", sp, "site=" + site);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * {@code Entity#setRemoved(RemovalReason)} 的 HEAD。
     *
     * <p>判据：目标是受保护玩家（换维度/区块卸载/退服这些"正常离开"由本体值层放行，
     * 这里只负责<b>留下证据</b>，不参与放行判定 —— 所以即使最后放行了，这一条栈也是有用的）。
     *
     * @param self {@code (Object) this}
     * @param site 注入点说明
     */
    public static void setRemovedHead(Object self, String site) {
        try {
            if (!(self instanceof ServerPlayer sp)) {
                return;
            }
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("remove", sp, "site=" + site);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ 判据侧的现场（由 Authority 判过之后调） ═══════════════════════════════

    /**
     * 有人在写玩家的动量，而且<b>轮询接下来一定会还原它</b>（判据已在
     * {@link ShanhaiStateAuthority#noteMotionWrite} 里走完）。
     *
     * @param sp   玩家
     * @param x/y/z 这次要写进去的值
     */
    public static void onMotionWrite(ServerPlayer sp, double x, double y, double z) {
        capture("motion", sp, "write=" + ShanhaiStateLogNum.vec(x, y, z));
    }

    /** 有人在写玩家的位置，判据同上（{@link ShanhaiStateAuthority#notePosWrite}）。 */
    public static void onPosWrite(ServerPlayer sp, double x, double y, double z) {
        capture("pos", sp, "write=" + ShanhaiStateLogNum.vec(x, y, z));
    }

    /**
     * <b>写入口把这一次动量写入丢掉了</b>（{@code motion_brake} 判成 {@code foreign_big}）。
     *
     * <p>为什么必须在这里抓：被丢掉的写入<b>不会</b>走到 {@code setDeltaMovement} 的 RETURN
     * ⇒ {@link #onMotionWrite} 那条路<b>一次都不会被叫到</b>（原方法已经被取消）。
     * 而这一帧的栈恰恰是回到"<b>是它在推</b>"的唯一证据（{@code first_external=…}）。
     *
     * <p>成本：{@code StackWalker} 只在<b>真丢弃</b>时走一趟，并且同一个 {@code where|target} 1/s 限流
     * （{@link ShanhaiViolationStack#capture}）⇒ 连着被推几秒也只留几条，不会拖 tick。
     *
     * @param sp    玩家
     * @param dvx/dvy/dvz 这一次写入的 Δv（新值 − 旧值 = 原本要被加上的那一把）
     * @param horiz 水平幅度 {@code hypot(dvx, dvz)}
     * @param vert  垂直幅度（带符号）
     */
    public static void onMotionBrakeDrop(ServerPlayer sp, double dvx, double dvy, double dvz,
                                         double horiz, double vert) {
        try {
            capture("motion_brake", sp, "dv=" + ShanhaiStateLogNum.vec(dvx, dvy, dvz)
                    + " horiz=" + ShanhaiStateLogNum.num(horiz)
                    + " vert=" + ShanhaiStateLogNum.num(vert)
                    + " note=dropped_at_write_head");
        } catch (Throwable ignored) {
            // 抓不到栈不影响"这一次写入已经被丢掉"这个事实
        }
    }

    /** 伤害事件被取消的那一拍（{@code LivingAttackEvent} / {@code LivingHurtEvent}）。 */
    public static void onAttackCancelled(ServerPlayer sp, String site) {
        try {
            if (!ShanhaiStateAuthority.protectedPlayer(sp)) {
                return;
            }
            capture("attack", sp, "site=" + site);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /**
     * 抓 ＋ 打 ＋ 喂（<b>唯一的落地口径</b>）。
     *
     * @param where  {@code health|die|remove|motion|motion_brake|pos|attack}
     *               （{@code motion_brake} 是写入口"写入即丢"那一路，见
     *               {@link #onMotionBrakeDrop}）
     * @param sp     玩家
     * @param detail 附加证据（{@code key=value}，接在行尾）
     */
    private static void capture(String where, ServerPlayer sp, String detail) {
        // 🔴 2026-11-14：回放自己写世界时的"动量证据"没有任何信息量（每秒一条、target 还是玩家），
        //    会把真正的"外部改写"证据淹掉 ⇒ 只在【回放写临界区 + motion/motion_brake】豁免，
        //    health / die / attack / remove 一律照抓（判据核 = ShanhaiReplayGuardSkip，可离线断言）。
        try {
            if (com.shanhai.add.time.replay.ShanhaiReplayGuardSkip.skip(where,
                    com.shanhai.add.time.replay.ShanhaiReplayGate.inReplayWrite())) {
                final long n = com.shanhai.add.time.replay.ShanhaiReplayGuardSkip.note(where);
                final long now = System.currentTimeMillis();
                if (now - GUARD_SKIP_LAST_MS >= 5_000L) {
                    GUARD_SKIP_LAST_MS = now;
                    ShanhaiMod.LOGGER.info("[SHANHAI-GUARD] {}",
                            com.shanhai.add.time.replay.ShanhaiReplayGuardSkip.line(where, n));
                }
                return;
            }
        } catch (Throwable ignored) {
            // 豁免判定坏了 ⇒ 照旧抓（宁可多一条噪声，也不能漏证据）
        }
        final String player = nameOf(sp);
        final ShanhaiViolationStack.Hit hit = ShanhaiViolationStack.capture(where, player);
        if (hit == null) {
            return;                                   // 1/s 限流挡下（计数在 ShanhaiViolationStack 里）
        }
        count(where);
        final StringBuilder line = new StringBuilder(ShanhaiViolationStack.line(hit));
        if (detail != null && !detail.isEmpty()) {
            line.append(' ').append(detail);
        }
        // 🆕 2026-11-08（用户口径）：凡打玩家朝向，**一律先规范化到 (-180,180]** 再进日志
        //   （不规范化就会打出 yaw=-993.45 那种越界值，读的人会以为是在看另一个玩家）。
        line.append(" yaw=").append(ShanhaiStateRules.yawText(sp.getYRot()))
                .append(" pitch=").append(ShanhaiStateRules.yawText(sp.getXRot()));
        try {
            ShanhaiMod.LOGGER.info("{}", line.toString());
        } catch (Throwable ignored) {
            // 日志出口坏了不许影响任何东西
        }
        ShanhaiViolationStack.feed(hit);              // → HotfixTriageTap 的 strike 通道
    }

    private static void count(String where) {
        synchronized (BY_WHERE) {
            AtomicLong n = BY_WHERE.get(where);
            if (n == null) {
                n = new AtomicLong();
                BY_WHERE.put(where, n);
            }
            n.incrementAndGet();
        }
    }

    private static String nameOf(ServerPlayer sp) {
        try {
            return sp.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 按 {@code where} 分开的读数（命令 / 报告用）。 */
    public static String statusLine() {
        final StringBuilder sb = new StringBuilder(160);
        sb.append("violation_probe");
        synchronized (BY_WHERE) {
            for (Map.Entry<String, AtomicLong> e : BY_WHERE.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue().get());
            }
        }
        sb.append(' ').append(ShanhaiViolationStack.statusLine());
        return sb.toString();
    }

    /** 某个 {@code where} 抓到过几个现场（读数）。 */
    public static long countOf(String where) {
        synchronized (BY_WHERE) {
            final AtomicLong n = BY_WHERE.get(where);
            return n == null ? 0L : n.get();
        }
    }

    /**
     * 数值/三元组的日志写法（<b>纯字面量实现</b>：本类的 mixin 调用方在早期就会触碰本类，
     * 所以这里不引 {@code ShanhaiStateLog}，避免多一条加载依赖；口径与它逐字一致：三位小数、点号）。
     */
    static final class ShanhaiStateLogNum {

        private ShanhaiStateLogNum() {}

        static String num(double d) {
            if (Double.isNaN(d)) {
                return "NaN";
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "Infinity" : "-Infinity";
            }
            return String.format(java.util.Locale.ROOT, "%.3f", d);
        }

        static String vec(double x, double y, double z) {
            return "(" + num(x) + ", " + num(y) + ", " + num(z) + ")";
        }
    }
}
