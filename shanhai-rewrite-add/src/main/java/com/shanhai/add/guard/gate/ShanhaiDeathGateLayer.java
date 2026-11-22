package com.shanhai.add.guard.gate;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.guard.state.ShanhaiStateLog;
import com.shanhai.add.hotfix.HotfixHolder;
import com.shanhai.add.hotfix.HotfixReadClassRestore;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 山海重构 · <b>「死态闸门」执行层</b>：被判定为死的受保护玩家，仍然能动、能捡
 * （2026-11-21，第三处「原版死态闸门」）。
 *
 * <h2>🔴 三处闸门里本类负责两处（第三处＝交互，已在客户端修，见既有那两条 redirect）</h2>
 * <pre>
 *   ① 移动（本类 {@link #immobile(Object)}）
 *      宿主：{@code LivingEntity#m_8107_}（aiStep）偏移 372 那条 {@code invokevirtual m_6107_}（isImmobile）
 *            javap -p -c &lt;forge-1.20.1-47.4.16-srg.jar&gt; net.minecraft.world.entity.LivingEntity#m_8107_:
 *              371: aload_0
 *              372: invokevirtual #3741  // Method m_6107_:()Z
 *              375: ifeq          396
 *              378..390: putfield jumping=false / xxa=0 / zza=0   ← 三个输入被归零（这就是"冻住"）
 *      {@code LivingEntity#m_6107_} = {@code isDeadOrDying()}；
 *      {@code Player#m_6107_}      = {@code super.isImmobile() || isSleeping()}（javap 逐条核过）：
 *              1: invokespecial LivingEntity.m_6107_:()Z / 4: ifne 14
 *              8: invokevirtual m_5803_:()Z (isSleeping) / 11: ifeq 18 / 14: iconst_1 / 19: ireturn
 *   ⇒ 判死的玩家被 {@code isImmobile()=true} 冻住输入；本类只解"死"那一半，
 *     <b>睡觉那一半一个字都不动</b>（{@code isSleeping()} 仍然 ⇒ 仍然不能动）。
 *
 *   ② 拾取（本类 {@link #pickupHealth(Object)}）
 *      宿主：{@code Player#m_8107_}（aiStep）偏移 200 那一条 {@code getHealth()}：
 *              200: aload_0 / 201: invokevirtual #902  // Method m_21223_:()F
 *              204: fconst_0 / 205: fcmpl / 206: ifle 382      ← 判的是 getHealth() &gt; 0.0F
 *              209..213: isSpectator() &amp;&amp; … ⇒ 也跳 382
 *              216..382: getEntities → playerTouch / 经验球 touch
 *      ⇒ 闸门判的<b>不是</b> {@code isDeadOrDying()}，而是<b>这一次 getHealth() 的读数</b>
 *        ⇒ 只把"这一次读数"抬到 &gt;0 就够，<b>不写字段、不改 {@code isDeadOrDying()}</b>。
 * </pre>
 *
 * <h2>🔴 为什么逻辑在普通类、mixin 只做转发</h2>
 * 本工程铁律（见 {@code ShanhaiHealthWriteClampMixin} / {@code ShanhaiGuardValueLayer} 类注释）：
 * mixin 类体内不许引用 Minecraft <b>成员</b>（{@code @Shadow} 字段/方法在 refmap 里没有条目）。
 * 两条 mixin 只做三件事：形参对得上、{@code (Object) self}、一次转发。判据与日志全在本类。
 *
 * <h2>🔴 负向路径（逐条写死；每一条都要求"逐位不变"）</h2>
 * <pre>
 *   ① 移动闸门
 *     {@code isImmobile()} 原值 false                      ⇒ false（一个字节都不动；正常生物走这条，只多一次已缓存的反射调用）
 *     不是 {@code Player}（怪物 / 载具 / 其它生物）           ⇒ 原值（true 或 false，原样；含 Ravager / 马 / 狐狸各自的覆写）
 *     是 Player 但<b>未受保护</b>（{@link ShanhaiGuardEvents#isGuarded}） ⇒ 原值
 *     受保护但<b>正在睡觉</b>                               ⇒ 原值（睡觉仍然不能动，睡眠那半不许碰）
 *     我们自己的判定抛任何 Throwable                        ⇒ 原值（见 {@code vanillaImmobile}）
 *   ② 拾取闸门
 *     读数 &gt;= 1.0（含 +∞）                                ⇒ 原读数（{@code served == read} ⇒ 直接返回，连 isGuarded 都不调）
 *     未受保护玩家                                         ⇒ 原读数（逐位放行）
 *     非 Player（不可能，防御用）                           ⇒ 0.0F 之外一律不动
 *     任何 Throwable                                       ⇒ 原读数（见 {@code vanillaHealth}）
 * </pre>
 *
 * <h2>🔴 与"读侧钳制"的关系（为什么两端表现不同，这是如实记录）</h2>
 * {@code ShanhaiHealthReadClampMixin} 只在<b>服务端</b>把 &lt;=0 的读数钳成 {@code getMaxHealth()}
 * （它的判据是 {@code instanceof ServerPlayer}）。所以：
 * <pre>
 *   服务端：字段被写成死的 ⇒ {@code getHealth()} 已读成 20 ⇒ 拾取闸门本来就过；
 *   客户端：没有那道钳制 ⇒ 读数就是同步字段的原值 ⇒ 本闸门在客户端才是真正起作用的那一处。
 *   ⇒ ② 仍然两端都挂（服务端的读数也可能被别的东西改；两端挂 = 不依赖"哪一侧先坏"）。
 * </pre>
 *
 * <h2>🔴 诊断（一次性，每会话每侧一行）</h2>
 * 现场三次取证都指向同一件事：{@code field=20.0} 与 {@code isDeadOrDying()=true}
 * 在原版<b>不可能同时成立</b>（{@code isDeadOrDying()} 体内就是 {@code getHealth() <= 0}）。
 * 这一行是第一次<b>同时</b>拿到「读数 / 字段真值 / 判死」三个值：
 * <pre>
 *   death_gate_probe where=Player#aiStep read=&lt;getHealth()&gt; field=&lt;DATA_HEALTH_ID&gt;
 *                    dead=&lt;isDeadOrDying()&gt; note=&lt;判读&gt; side=&lt;client|server&gt;
 *   note=judge_tampered     read&gt;0 但 dead=true ⇒ {@code isDeadOrDying()} 的判据被人改（HEAD 注入形态）
 *   note=read_side_faked    read&lt;=0 但字段&gt;0   ⇒ {@code getHealth()} 的返回值被人改（RETURN 注入形态）
 *   note=field_really_dead  read&lt;=0 且字段&lt;=0 ⇒ 字段真值就是死的（写侧钳制没兜住）
 *   note=consistent         两者一致（read&gt;0 且 dead=false 不算触发条件，不会占掉一次性名额）
 * </pre>
 * ⚠️ 触发条件是「受保护玩家 且 （{@code read<=0} 或 {@code dead}）」——<b>正常态不触发</b>，
 * 所以"日志里没有这一行"= 整段会话没出现过异常读数，<b>不等于</b>本类没挂上；
 * "挂上没有"用 {@code death_gate gate=immobile …} / {@code death_gate gate=pickup …} 两行判。
 */
public final class ShanhaiDeathGateLayer {

    private ShanhaiDeathGateLayer() {}

    /** 日志前缀（与守卫同一套，历史 grep 口径不失效）。 */
    private static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /**
     * 拾取闸门要供给的最低读数（用户口径「抬到 &gt;0」⇒ 取 1.0，与写侧钳制的 {@code CLAMP_MIN_HEALTH} 同值）。
     */
    public static final float PICKUP_MIN_HEALTH = ShanhaiGuardValueLayer.CLAMP_MIN_HEALTH;

    /** 两行 gate 日志的节流窗口：<b>100 tick = 5 秒最多一行</b>（需求原话）。 */
    private static final long LOG_EVERY_TICKS = 100L;

    /** 移动闸门的上一次落盘 tick（{@code Long.MIN_VALUE} = 还没落过）。 */
    private static final AtomicLong LAST_IMMOBILE_LOG_TICK = new AtomicLong(Long.MIN_VALUE);

    /** 拾取闸门的上一次落盘 tick。 */
    private static final AtomicLong LAST_PICKUP_LOG_TICK = new AtomicLong(Long.MIN_VALUE);

    /** 诊断行：客户端那一侧打过没有（每会话每侧只打一次）。 */
    private static final AtomicBoolean PROBED_CLIENT = new AtomicBoolean(false);

    /** 诊断行：服务端那一侧打过没有。 */
    private static final AtomicBoolean PROBED_SERVER = new AtomicBoolean(false);

    /** 移动闸门累计"解除冻结"了几次（只进日志，不参与判据）。 */
    private static final AtomicLong RELEASED_IMMOBILE = new AtomicLong();

    /** 拾取闸门累计"抬读数"了几次。 */
    private static final AtomicLong LIFTED_PICKUP = new AtomicLong();

    // ═══════════════════════════════ ① 移动闸门 ═══════════════════════════════

    /**
     * 🔴 <b>{@code LivingEntity#m_8107_} 里那一次 {@code isImmobile()} 要用的返回值</b>。
     *
     * <p>算法（严格按需求）：
     * <pre>
     *   原值 false                                  ⇒ false
     *   原值 true 且 是 Player 且 受保护 且 不在睡觉  ⇒ false   ← 只解"死"这一半
     *   其余（含原值 true 但没受保护 / 正在睡觉 / 非 Player） ⇒ true（＝原值）
     * </pre>
     *
     * <p>⚠️ "原值"是真的去调一次 {@code isImmobile()}（反射虚调用 ⇒ 各子类覆写照常跑），
     * 不是在这里重写一份判据 —— 这样任何第三方对 {@code m_6107_} 的改写都<b>照原样生效</b>，
     * 我们只在"它说 true"的基础上做减法（为什么必须反射、为什么不能重算：见 {@link #originalImmobile(LivingEntity)}）。
     *
     * @param self {@code (Object) this}（{@code @Redirect} 的接收者，恒为 {@code LivingEntity}）
     * @return 这一次 isImmobile 要用的值
     */
    public static boolean immobile(Object self) {
        final LivingEntity living = asLiving(self);
        if (living == null) {
            return false;                                   // 不可能：接收者恒为 LivingEntity
        }
        // ① 原值（反射取；反射不可用 ⇒ 公开 API 近似，两种都绝不抛）
        final boolean original = originalImmobile(living);
        if (!original) {
            return false;                                   // 逐位放行：正常活人 / 正常怪物走这条
        }
        if (!(living instanceof Player)) {
            return true;                                    // 非玩家 ⇒ 原值（此时原值必为 true）
        }
        final Player player = (Player) living;
        // ② 未受保护 ⇒ 原值（判据与伤害/死亡/界面/写钳制同一份）
        if (!isGuarded(player)) {
            return true;
        }
        // ③ 睡觉那一半不许动：睡觉仍然不能动
        if (isSleeping(player)) {
            noteImmobile(player, false);
            return true;
        }
        // ④ 受保护 ＋ 读成死的 ＋ 没在睡觉 ⇒ 解除冻结
        noteImmobile(player, true);
        return false;
    }

    /**
     * 移动闸门的<b>兜底</b>：只取原值，一个判断都不做（mixin 的 catch 分支用）。
     *
     * <p>只在"我们自己的判定抛异常"时用到 ⇒ 必须 100% 退回原版语义。
     *
     * @param self {@code (Object) this}
     * @return 原版 {@code isImmobile()}；连它都读不到 ⇒ {@code true}（宁可"照旧冻住"也不凭空放行）
     */
    public static boolean vanillaImmobile(Object self) {
        try {
            final LivingEntity living = asLiving(self);
            return living == null || originalImmobile(living);
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ═══════════════ 取原值：为什么必须是反射（不是重写一份判据） ═══════════════

    /**
     * 🔴 <b>原版 {@code isImmobile()} 原值</b>（{@code m_6107_}）。
     *
     * <h2>为什么不能在本类里"照着原版重写一遍"</h2>
     * <pre>
     *   {@code isImmobile()} 是 {@code protected} ⇒ 普通类（不在 net.minecraft 包里、也不是它的子类）直接调编译不过。
     *   而"用公开 API 重算"是<b>错的</b>：它不只被 Player 覆写 —— 反编译逐类查过：
     *     LivingEntity   : return isDeadOrDying();
     *     Player         : return super.isImmobile() || isSleeping();
     *     Ravager        : return super.isImmobile() || roarTicks&gt;0 || attackTicks&gt;0 || stunTicks&gt;0;
     *     AbstractHorse  : return super.isImmobile() &amp;&amp; isVehicle() &amp;&amp; isSaddled() || isEating() || …;
     *     Llama / Fox    : 各有自己的覆写
     *   ⇒ 若在这里重算成 {@code isDeadOrDying() || isSleeping()}，劫掠兽吼叫 / 马进食 / 狐狸坐下
     *     会被判成"能动" —— <b>非玩家实体的行为就被我们改了</b>（违反"其余逐位放行"）。
     *
     * <h2>为什么反射能拿到"原值"</h2>
     * <pre>
     *   {@code Method.invoke} 按<b>动态分派</b>调用（JLS：instance method ⇒ dynamic method lookup）
     *   ⇒ 从 {@code LivingEntity.class} 取到的那个 Method，在 Ravager 实例上跑的就是 Ravager 的覆写；
     *     第三方若在 {@code m_6107_} 上挂了 HEAD 注入，反射调用<b>同样经过</b>它 ⇒ 拿到的就是"这一次真的会返回什么"。
     *   名字两个都试：开发名 {@code isImmobile}（dev-run）/ 生产 SRG 名 {@code m_6107_}（运行时）
     *   —— 与本工程取 {@code DATA_HEALTH_ID} / {@code f_20961_} 是同一套写法。
     * </pre>
     *
     * <h2>成本</h2>
     * <pre>
     *   每个 LivingEntity 每 tick 一次缓存后的 {@code Method.invoke}（约几十 ns）；
     *   200 只生物 ≈ 10 µs/tick，与"每 tick 读一次血量"同量级。
     * </pre>
     *
     * @param living 目标实体
     * @return 原值；反射不可用/抛异常 ⇒ {@link #approximateImmobile(LivingEntity)}（并只打一次 warn）
     */
    private static boolean originalImmobile(LivingEntity living) {
        try {
            final Method method = vanillaImmobileMethod();
            if (method != null) {
                return ((Boolean) method.invoke(living)).booleanValue();
            }
        } catch (Throwable ignored) {
            // 落到下面的近似（反射被挡 / 调用抛异常都不许影响 aiStep）
        }
        return approximateImmobile(living);
    }

    /**
     * 反射不可用时的<b>退化近似</b>：只按基类 + Player 的公开 API 算。
     *
     * <p>⚠️ 如实记它的代价：{@code Ravager} / {@code AbstractHorse} / {@code Llama} / {@code Fox}
     * 那几种覆写会退化成基类实现（它们本来会因吼叫 / 进食 / 坐下而 immobile）⇒ 那几种实体会变成能动。
     * 只有在"反射拿不到 {@code isImmobile}"这一种情况下才会走到这里，且会打一行 warn（{@link #warnApproximation()}）。
     */
    private static boolean approximateImmobile(LivingEntity living) {
        try {
            if (living.isDeadOrDying()) {
                return true;                                // 基类那一半：LivingEntity#isImmobile() = isDeadOrDying()
            }
            return living instanceof Player && ((Player) living).isSleeping();  // Player 那一半
        } catch (Throwable ignored) {
            return true;                                    // 连读数都拿不到 ⇒ 照旧冻住（不凭空放行）
        }
    }

    /** {@code isImmobile} 的反射句柄（取到一次就缓存；{@code null} = 取不到）。 */
    private static volatile Method VANILLA_IMMOBILE;

    /** 反射试过一次没有（失败也只打一行，不每 tick 重试）。 */
    private static volatile boolean VANILLA_IMMOBILE_TRIED;

    /** 取到的是哪个名字（{@code isImmobile} / {@code m_6107_}；只进日志）。 */
    private static volatile String vanillaImmobileVia = "(未取)";

    /** 取 {@code isImmobile} 的反射句柄（双名字；只试一次）。 */
    private static Method vanillaImmobileMethod() {
        final Method cached = VANILLA_IMMOBILE;
        if (cached != null) {
            return cached;
        }
        if (VANILLA_IMMOBILE_TRIED) {
            return VANILLA_IMMOBILE;                        // 取不到 ⇒ null（不再重试）
        }
        synchronized (ShanhaiDeathGateLayer.class) {
            if (VANILLA_IMMOBILE_TRIED) {
                return VANILLA_IMMOBILE;
            }
            VANILLA_IMMOBILE_TRIED = true;
            final String[] names = {"isImmobile", "m_6107_"};
            for (int i = 0; i < names.length; i++) {
                try {
                    final Method candidate = LivingEntity.class.getDeclaredMethod(names[i]);
                    candidate.setAccessible(true);
                    VANILLA_IMMOBILE = candidate;
                    vanillaImmobileVia = names[i];
                    return candidate;
                } catch (Throwable ignored) {
                    // 试下一个名字（开发名 / SRG 名）
                }
            }
            warnApproximation();
            return null;
        }
    }

    /** 反射不可用只报一次（"我们不是静默降级的"）。 */
    private static void warnApproximation() {
        try {
            ShanhaiMod.LOGGER.warn(PREFIX
                    + "death_gate immobile_vanilla_unavailable"
                    + " tried=isImmobile,m_6107_"
                    + " note=fallback_to_public_api（Ravager/AbstractHorse/Llama/Fox 的覆写会退化）");
        } catch (Throwable ignored) {
            // 日志坏了也不许影响移动
        }
    }

    // ═══════════════════════════════ ② 拾取闸门 ═══════════════════════════════

    /**
     * 🔴 <b>{@code Player#m_8107_} 里"拾取闸门"那一次 {@code getHealth()} 要用的返回值</b>。
     *
     * <p>语义：受保护玩家 ⇒ {@code max(读数, 1.0F)}；其余 ⇒ 原读数（逐位放行）。
     *
     * <p>⚠️ <b>两处刻意写明的地方</b>：
     * <ol>
     *   <li>{@code read &gt; 0} 且 {@code read &gt;= 1.0} ⇒ {@code served == read} ⇒ <b>直接返回</b>，
     *       连 {@code isGuarded} 都不调（正常玩家的正常血量走这条，成本 = 一次 float 比较）；</li>
     *   <li>{@code NaN}：{@code Math.max(NaN, 1.0F)} 还是 {@code NaN}（NaN 读不成 &gt;0 ⇒ 闸门仍然关着）。
     *       本类的目标是"把这一次读数抬到 &gt;0"，所以 NaN 也供 {@code 1.0} —— 这是
     *       {@code max} 语义在 NaN 上的<b>唯一一处偏离</b>，如实写在报告里。</li>
     * </ol>
     *
     * @param self {@code (Object) this}（{@code @Redirect} 的接收者，恒为 {@code Player}）
     * @return 这一次 getHealth 要用的值
     */
    public static float pickupHealth(Object self) {
        final Player player = asPlayer(self);
        if (player == null) {
            return 0.0F;                                    // 不可能：接收者恒为 Player
        }
        // ① 原读数 = 被替换掉的那一次调用（异常照原版抛出去）
        final float read = player.getHealth();
        // ② 诊断（一次性；正常态不占名额）
        probeOnce(player, read);
        // ③ 目标读数：read >= 1.0 时恒等于 read；否则抬到 1.0
        //    （NaN 的比较恒 false ⇒ 也走"抬"这一支，见类注释里那一处刻意偏离）
        final float served = read >= PICKUP_MIN_HEALTH ? read : PICKUP_MIN_HEALTH;
        if (served == read) {
            return read;                                    // 逐位放行：连受保护判定都不调
        }
        // ④ 只有"真要抬"的形态才付受保护判定的成本
        if (!isGuarded(player)) {
            return read;                                    // 未受保护 ⇒ 原读数
        }
        notePickup(player, read, served);
        // ⑤ 🆕 2026-11-21（P2 ①）：字段里有血、读数却是死的 ⇒ 直接做一次关键类还原
        //   （现场 61 条 read_side_mismatch 全都没有后续动作 ⇒ 用户原话第 ④ 步一次都没触发）。
        noteReadSideFaked(player, read);
        return served;
    }

    /**
     * 🆕 2026-11-21（P2 ①）：<b>{@code read_side_faked} ⇒ 对声明 {@code getHealth()} 的类做一次还原</b>。
     *
     * <h2>判据（就是现场那两条日志里已经在报的那件事）</h2>
     * <pre>
     *   player.getHealth() &lt;= 0 ／ NaN（读成死的）
     *   而 getEntityData().get(DATA_HEALTH_ID) &gt; 0（字段还是活的）
     *   ⇒ 读那一步被人改了 ⇒ 这类不需要"看见对手"就能动手：
     *     对 net.minecraft.world.entity.LivingEntity 调
     *     HotfixKeyClassRestore.restoreForTriage(inst, LivingEntity.class, null)
     *     （= 扫该类全部真实方法，把挂在上面的外部 handler 方法体清空；手术实现不在这里）
     * </pre>
     *
     * <h2>🔴 为什么这里只做"一次 float 比较 ＋ 一次投递"</h2>
     * 本方法是<b>每 tick 每玩家</b>都在跑的（{@code Player#aiStep} 里那次 getHealth），
     * 而还原里有<b>一次整类 retransformClasses</b> ⇒
     * <ol>
     *   <li>正常血量的玩家走快路（{@code read > 0} 直接 return，一个字节都不动）；</li>
     *   <li>真命中时也只调 {@link HotfixReadClassRestore#onReadSideFaked} ——
     *       它自己限流（同一类 1200 tick 一次）并把手术丢到工作线程上，
     *       <b>绝不在服务端主线程上 retransform</b>。</li>
     * </ol>
     *
     * @param player 被读的那个玩家
     * @param read   这一次 {@code getHealth()} 的读数
     */
    private static void noteReadSideFaked(Player player, float read) {
        try {
            if (read > 0.0F) {
                return;                                     // 读数正常 ⇒ 快路（一次 float 比较）
            }
            final float field = ShanhaiGuardValueLayer.readHealthField(player);
            if (!(field > 0.0F)) {
                return;                                     // 字段也是死的 ⇒ 真死，不该还原（不许阻断正常死亡）
            }
            HotfixReadClassRestore.onReadSideFaked(
                    HotfixHolder.instrumentation(), LivingEntity.class, ticks(player), read, field);
        } catch (Throwable ignored) {
            // 触发/日志坏了不许影响拾取闸门本身
        }
    }

    /**
     * 拾取闸门的<b>兜底</b>：只取原读数（mixin 的 catch 分支用）。
     *
     * @param self {@code (Object) this}
     * @return 原版 {@code getHealth()}；读不到 ⇒ {@code 0.0F}（**不**凭空供 1.0：兜底不许造"活"，
     *         造"活"必须在受保护判据之下，那一条已经在本方法外面走过了）
     */
    public static float vanillaHealth(Object self) {
        try {
            final Player player = asPlayer(self);
            return player == null ? 0.0F : player.getHealth();
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    // ═══════════════════════════════ ③ 一次性诊断 ═══════════════════════════════

    /**
     * 每会话每侧只打一行的「读数 / 字段 / 判死」三联诊断。
     *
     * <p>触发条件：{@code read<=0} <b>或</b> {@code isDeadOrDying()==true}，且这个玩家受保护。
     * 正常态（read&gt;0 且没判死）<b>不触发、也不占掉一次性名额</b>。
     *
     * @param player 被读取的玩家
     * @param read   这一次 {@code getHealth()} 的读数
     */
    private static void probeOnce(Player player, float read) {
        try {
            final boolean clientSide = isClientSide(player);
            final AtomicBoolean oneShot = clientSide ? PROBED_CLIENT : PROBED_SERVER;
            if (oneShot.get()) {
                return;                                     // 已打过 ⇒ 只剩一次原子读的成本
            }
            final boolean dead = player.isDeadOrDying();
            if (!dead && read > 0.0F) {
                return;                                     // 正常态：不触发，也不占名额
            }
            if (!isGuarded(player)) {
                return;                                     // 不是受保护玩家：不是我们的现场
            }
            if (!oneShot.compareAndSet(false, true)) {
                return;                                     // 另一条线程先打了
            }
            final float field = ShanhaiGuardValueLayer.readHealthField(player);
            ShanhaiMod.LOGGER.warn(PREFIX
                    + "death_gate_probe where=Player#aiStep"
                    + " read=" + num(read)
                    + " field=" + num(field)
                    + " dead=" + dead
                    + " note=" + probeNote(read, field, dead)
                    + " side=" + (clientSide ? "client" : "server"));
        } catch (Throwable ignored) {
            // 诊断坏了不许影响拾取
        }
    }

    /** 三联读数的判读（纯算术，只看这三个值）。 */
    private static String probeNote(float read, float field, boolean dead) {
        if (read > 0.0F && dead) {
            return "judge_tampered";                        // 读成活的、却判成死的 ⇒ 判死那一步被人改
        }
        if (read <= 0.0F && field > 0.0F) {
            return "read_side_faked";                       // 字段是好的、读数却是死的 ⇒ 读血那一步被人改
        }
        if (read <= 0.0F) {
            return "field_really_dead";                     // 字段真值就是死的
        }
        return "consistent";
    }

    // ═══════════════════════════════ 日志 ═══════════════════════════════

    /**
     * 移动闸门落一行（<b>100 tick 最多一行</b>）。
     *
     * <p>行形态（可 grep）：{@code death_gate gate=immobile read=<isDeadOrDying()> field=<DATA_HEALTH_ID> released=<true|false> player=<名>}
     */
    private static void noteImmobile(Player player, boolean released) {
        try {
            // 计数在限流之前：这一行可能被压掉，但"闸门到底动了几次"不能跟着少算
            final long total = released ? RELEASED_IMMOBILE.incrementAndGet() : RELEASED_IMMOBILE.get();
            final long tick = ticks(player);
            if (!admit(tick, LAST_IMMOBILE_LOG_TICK)) {
                return;
            }
            final boolean read = player.isDeadOrDying();
            final float field = ShanhaiGuardValueLayer.readHealthField(player);
            ShanhaiMod.LOGGER.info(PREFIX
                    + "death_gate gate=immobile"
                    + " read=" + read
                    + " field=" + num(field)
                    + " released=" + released
                    + " player=" + nameOf(player)
                    + " released_total=" + total);
        } catch (Throwable ignored) {
            // 日志坏了不许影响移动
        }
    }

    /**
     * 拾取闸门落一行（<b>100 tick 最多一行</b>；只在"真的抬了读数"时打）。
     *
     * <p>行形态（可 grep）：{@code death_gate gate=pickup ordinal=1 read=<原读数> served=<返回值> player=<名>}
     */
    private static void notePickup(Player player, float read, float served) {
        try {
            // 计数在限流之前（同上：被压掉的行不算"没发生"）
            final long total = LIFTED_PICKUP.incrementAndGet();
            final long tick = ticks(player);
            if (!admit(tick, LAST_PICKUP_LOG_TICK)) {
                return;
            }
            ShanhaiMod.LOGGER.info(PREFIX
                    + "death_gate gate=pickup"
                    + " ordinal=1"
                    + " read=" + num(read)
                    + " served=" + num(served)
                    + " player=" + nameOf(player)
                    + " lifted_total=" + total);
        } catch (Throwable ignored) {
            // 日志坏了不许影响拾取
        }
    }

    /**
     * "最多每 {@value #LOG_EVERY_TICKS} tick 一行"的全局限流。
     *
     * <p>tick 往回跳（换世界 / 换维度，gameTime 重新计）时<b>放行一次</b>并重置锚点 ——
     * 否则新世界前 100 tick 里一次都不打，看起来像"没挂上"。
     *
     * @param tick 当前 tick
     * @param last 该闸门上一次落盘的 tick
     * @return true = 这一拍可以打
     */
    private static boolean admit(long tick, AtomicLong last) {
        final long prev = last.get();
        if (prev != Long.MIN_VALUE && tick >= prev && tick - prev < LOG_EVERY_TICKS) {
            return false;
        }
        return last.compareAndSet(prev, tick);              // 抢输了说明别人刚打过 ⇒ 这一拍压掉
    }

    // ═══════════════════════════════ 小工具（全部防抛） ═══════════════════════════════

    /** {@code Object} → {@code LivingEntity}（不是就 null，绝不抛）。 */
    private static LivingEntity asLiving(Object self) {
        return self instanceof LivingEntity ? (LivingEntity) self : null;
    }

    /** {@code Object} → {@code Player}（不是就 null，绝不抛）。 */
    private static Player asPlayer(Object self) {
        return self instanceof Player ? (Player) self : null;
    }

    /**
     * 受保护判定（和伤害 / 死亡 / 死亡界面 / 写侧钳制同一份）。
     *
     * <p>抛异常 ⇒ {@code false}（＝退回原版：不改任何行为）。
     */
    private static boolean isGuarded(Player player) {
        try {
            return player != null && ShanhaiGuardEvents.isGuarded(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 在睡觉吗（{@code Entity#m_5803_}）。
     *
     * <p>抛异常 ⇒ {@code true}（"宁可照旧冻住"：睡眠那一半不许因为我们的异常被放开）。
     */
    private static boolean isSleeping(Player player) {
        try {
            return player.isSleeping();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 哪一侧（客户端 / 服务端）；读不到 ⇒ 按服务端算（诊断行少一次 client 标注，不影响判据）。 */
    private static boolean isClientSide(Player player) {
        try {
            final Level level = player.level();
            return level != null && level.isClientSide();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个玩家所在 level 的 gameTime（限流锚点）；读不到 ⇒ 50 ms 折 1 tick 的退化口径。 */
    private static long ticks(Player player) {
        try {
            final Level level = player.level();
            if (level != null) {
                return level.getGameTime();
            }
        } catch (Throwable ignored) {
            // 退化到毫秒口径
        }
        return System.currentTimeMillis() / 50L;
    }

    /** 玩家名（只进日志；拿不到 ⇒ {@code ?}，日志自己不许再抛）。 */
    private static String nameOf(Player player) {
        try {
            return player.getName().getString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 数值格式（与工程既有日志同一套：三位小数；NaN / ±∞ 原样写字面量）。 */
    private static String num(float value) {
        return ShanhaiStateLog.num((double) value);
    }
}
