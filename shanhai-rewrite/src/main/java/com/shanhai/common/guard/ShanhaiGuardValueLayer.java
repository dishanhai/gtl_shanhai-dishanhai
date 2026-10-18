package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 🔴 <b>【创始现实修改模块】「值层兜底」的执行层</b>（2026-10-17 新增；只这一份，所有调用点共用）。
 *
 * <h2>为什么要有这一层（实机证据 ＋ 对手字节码，两条都不推翻）</h2>
 * <pre>
 *   ① 实机：敌人不走 LivingAttackEvent 时 —— 事件层三条拦截都在生效、补血自证也成功，
 *      玩家却进入「死但没死透」：死亡界面被压住，人僵住不能动。
 *   ② 对手字节码：它的杀人链【根本不过 setHealth】——
 *        EntityUtil.damageHealth：`真血 - 伤害`（**没有** max(0, ·)）
 *        → EntityUtil.forceSetHealth
 *        → forceSetEntityData(LivingEntity.f_20961_（即 DATA_HEALTH_ID）, 值)   ← 直接裸写同步血量字段
 *      它还：
 *        · 在 setHealth 上挂 `@Inject(HEAD, cancellable=true)`：counter>=5 且新值 &gt; getHealth() ⇒ cancel，
 *          而 counter 是【单向锁存】⇒ 一旦中招，我们的 setHealth 补血【永久无效】；
 *        · 给 getHealth() 的每条 return 插了 `+ delta`（delta 同步给客户端）⇒ 读回值 != 真实血量。
 *   ⇒ 三条结论：只拦 setHealth 不够 ／ 只信 getHealth() 不够 ／ 只走 setHealth 补血不够。
 * </pre>
 *
 * <h2>本类做四件事（判据全部在 {@link ShanhaiGuardCore}，本类不写第二份判据）</h2>
 * <ol>
 *   <li>① {@link #guardSetHealth(Object, float)} —— {@code setHealth(float)} 的<b>入参</b>兜底。</li>
 *   <li>🔴 <b>第二通道</b>：{@link #forceHealthField(LivingEntity, float)} 直写 {@code DATA_HEALTH_ID}
 *       （{@link ShanhaiGuardCore.SecondChannelHeal} 的实现 = {@link FieldHealChannel}），
 *       绕开任何人对 {@code setHealth} 的 cancel。</li>
 *   <li><b>两条腿补血</b>：{@link #healTwoLegs(LivingEntity)} = setHealth → 自证 → 直写字段 → 再自证，
 *       并报出<b>是哪一条腿成的</b>（{@code heal_channel=setHealth / direct / failed}）。</li>
 *   <li>②/③/④ 共用的复位动作：{@link #resetDeathFlow(LivingEntity)}（补血两条腿 ＋ {@code deathTime = 0}
 *       ＋ {@code hurtTime = 0}）与 ④ 的 {@link #refuseDie(Object, String)}。</li>
 *   <li>⑤ <b>对手桥</b>（全部反射 + try/catch，类找不到就跳过）：用对手<b>自己的</b> public static API
 *       {@code setHealthDelta(LivingEntity, float)} 把 {@code getHealth()} 的偏移清零，
 *       并清它 ForgeData 里的单向锁存计数器（{@code omnimobs_counter_data}，阈值 5）。
 *       ⚠ 这层依赖对手的类名/方法名/数据键 ⇒ <b>换版本就失效</b>；失效时只记一行
 *       {@code omnimobs_bridge unavailable: <原因>}，之后不再重试，自动退回值层。</li>
 * </ol>
 *
 * <h2>🔴 为什么本类必须是<b>普通类</b>，而不是把逻辑写进 mixin</h2>
 * 本工程既定的 mixin 写法（见 {@code ShanhaiSetBlockWatchMixin} 类注释 §2、实测 refmap）：
 * mixin 类体内<b>不许引用 Minecraft 成员</b>（{@code @Shadow} 字段/方法在生成的 refmap 里没有条目），
 * <b>所有 MC 调用一律下沉到普通类</b> —— 普通类会被 reobf 成 SRG 名（如 {@code m_21233_} / {@code f_20919_}），
 * 运行期才对得上。所以：<b>mixin = 转发壳；本类 = 真正的执行体</b>。
 *
 * <h2>🔴 第二通道怎么拿到 {@code DATA_HEALTH_ID}</h2>
 * <pre>
 *   javap -p -s &lt;forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1.jar&gt; net.minecraft.world.entity.LivingEntity
 *     private static final net.minecraft.network.syncher.EntityDataAccessor&lt;java.lang.Float&gt; DATA_HEALTH_ID;
 *       descriptor: Lnet/minecraft/network/syncher/EntityDataAccessor;
 *   TSRG： DATA_HEALTH_ID → f_20961_（与对手写的 LivingEntity.f_20961_ 是同一个字段）
 * </pre>
 * 它是 <b>private</b> ⇒ 普通类无法直接引用（加 AccessTransformer 要改 {@code build.gradle}，超出本轮的改动面）
 * ⇒ 用<b>反射</b>取一次并缓存：<b>开发名 {@code DATA_HEALTH_ID} 与生产 SRG 名 {@code f_20961_} 两个都试</b>，
 * 谁先取到就记谁（日志里会打印用的是哪个名字）。取不到 ⇒ 第二通道明确"不可用"，
 * <b>不假装成功</b>（回血只报 failed，绝不报 direct）。
 *
 * <h2>🔴 防递归 / 防重入（两层，都在这份代码里）</h2>
 * <pre>
 *   ① 值判据：setHealth 的入参只要 &gt; 0 就【原样放行】⇒ 我们自己的 setHealth(max) 永远不会被再改写
 *   ② 重入闸门：{@link #BUSY}（ThreadLocal）—— 复位动作（②/④）期间，任何再进来的 setHealth/die
 *      一律放行 / 只拒绝不动手 ⇒ 就算某个 mod 在 setHealth 里回调 die、或在 die 里回调 setHealth，
 *      也只会走一层，不会无限递归
 * </pre>
 *
 * <h2>复现（离线，不需要游戏）</h2>
 * 所有判据的离线自检在 {@link ShanhaiGuardCore#main}（纯 JDK）：
 * {@code javac -d <out> ShanhaiGuardCore.java} 后 {@code java -cp <out> com.shanhai.common.guard.ShanhaiGuardCore}。
 * ⚠️ <b>离线自检证明的是「判据」</b>（值层兜底该不该触发、自证该判成功还是失败）；
 * 「mixin 是否真的挂上、直写是否真的到达客户端」只能进游戏验（判据见交付报告）。
 */
public final class ShanhaiGuardValueLayer {

    private ShanhaiGuardValueLayer() {}

    /**
     * 🔴 <b>重入闸门</b>：只有本类自己的复位动作会把它置 true。
     *
     * <p>为什么用 ThreadLocal 而不是一个 boolean 字段：{@code setHealth}/{@code die} 可能在不同维度/不同线程
     * 的实体上并发发生（服务端主线程是单线程，但客户端渲染线程与集成服线程是两条）——
     * 一个 static boolean 会被另一条线程误判成"正在复位"从而漏拦。
     */
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ═══════════════════════════════ ① setHealth(float) 入参兜底 ═══════════════════════════════

    /**
     * 🔴 <b>「谁都不许把受保护玩家写死」</b>：{@code LivingEntity#setHealth(float)} 的入参改写。
     *
     * <pre>
     *   不是 ServerPlayer（生物 / 客户端 LocalPlayer）                   ⇒ 原样返回（一个字节都不改）
     *   传入值 &gt; 0（正常写血：受伤 / 回血 / 改上限）                     ⇒ 原样返回
     *   enabled=false / 不是受保护玩家                                  ⇒ 原样返回
     *   受保护玩家 ＋ 传入值 &lt;= 0                                      ⇒ 改成 player.getMaxHealth()
     * </pre>
     *
     * <p>🔴 <b>判据在核里</b>（{@link ShanhaiGuardCore#rewriteHealthDecision}），本方法只负责"取值 + 执行"。
     *
     * <p>⚠️ 第一行那个 {@code value <= 0} 是<b>性能前置过滤</b>（否则每一 tick 一车正常的 setHealth 都要去扫背包），
     * 与核里那条判据是同一条（值 &gt; 0 ⇒ 不动）—— <b>权威判据仍是核里那一份</b>。
     *
     * <p>⚠️ <b>本守卫拦不到"裸写同步血量字段"那条路</b>（对手的 forceSetHealth 不走 setHealth）——
     * 那条路由 ② 每 tick 看门狗 ＋ {@link #healTwoLegs} 的第二通道兜住。
     *
     * @param self  目标实体（mixin 里传 {@code (Object) this}，形参类型故意用 {@code Object} —— mixin 体内不碰 MC 类型）
     * @param value 原入参
     * @return 要真正写进去的值：命中兜底 ⇒ 满血上限；否则逐字返回 {@code value}
     */
    public static float guardSetHealth(Object self, float value) {
        // 🔴 最便宜的闸门放最前面：正常写血（> 0）一律原样 —— 连类型都不用判。
        //    NaN 在这里也会走掉（`NaN <= 0` 是 false）⇒ 怪值照原样放行，绝不硬塞。
        if (!(value <= 0f)) {
            return value;
        }
        if (!(self instanceof ServerPlayer player)) {
            return value;   // 生物 / 客户端 LocalPlayer：一个字节都不改
        }
        try {
            if (!ShanhaiGuardCore.rewriteHealthDecision(
                    value, true, ShanhaiGuardEvents.isGuarded(player), ShanhaiGuardCore.enabled).yes) {
                return value;   // 权威判据（开关 / 受保护）
            }
            if (BUSY.get()) {
                // 🔴 防递归：我们自己的复位路径里又进来了 ⇒ 原样放行（绝不二次改写）
                return value;
            }
            BUSY.set(Boolean.TRUE);
            try {
                final float max = player.getMaxHealth();
                if (!(max > 0f)) {
                    // 上限本身非法（0/负/NaN）⇒ 不硬塞：塞进去只会造出另一个"血 0"的假复活
                    ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                            + "setHealth_guard 放弃改写：getMaxHealth()=" + max + " player="
                            + player.getName().getString());
                    return value;
                }
                // 🔴 用户点名的日志格式（逐字，别改）：
                //    [SHANHAI-GUARD] setHealth_guard value_was=<原值> -> <新值> player=<名字>
                ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "setHealth_guard value_was=" + value
                        + " -> " + max + " player=" + player.getName().getString());
                return max;
            } finally {
                BUSY.set(Boolean.FALSE);
            }
        } catch (Throwable t) {
            // 兜底：写血这条主路径绝不因我们的判断而改变（打出来，不静默）
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "setHealth_guard 抛异常（原样放行）：" + t);
            return value;
        }
    }

    // ═══════════════════════ 第二通道：直写同步血量字段（DATA_HEALTH_ID） ═══════════════════════
    //
    // 🔴 这是「SecondChannelHeal 那个空插槽」的实现：
    //    LivingEntity#setHealth(float)  →  this.getEntityData().set(DATA_HEALTH_ID, Mth.clamp(value, 0, max))
    //    我们绕开它，直接  entity.getEntityData().set(DATA_HEALTH_ID, value)
    //    `SynchedEntityData#set` 内部已经把三件事都做了（javap -c 实证）：
    //        DataItem.setValue(value) → entity.onSyncedDataUpdated(key) → DataItem.setDirty(true) ＋ isDirty = true
    //    ⇒ 下一次 ServerEntity 的 tick 同步就会把这个字段发到客户端（另一个 mod 对 setHealth 的 cancel 拦不到它）。

    /** 反射拿到的 {@code LivingEntity.DATA_HEALTH_ID}（取到一次就缓存；null = 还没取到/取不到）。 */
    private static volatile Object healthAccessor;

    /** 取到它的字段名（{@code DATA_HEALTH_ID} = 开发环境 ／ {@code f_20961_} = 生产 SRG 名）。 */
    private static volatile String healthAccessorVia;

    /** 只探测一次（失败也不反复刷日志）。 */
    private static final AtomicBoolean HEALTH_ACCESSOR_PROBED = new AtomicBoolean(false);

    /**
     * 取 {@code LivingEntity.DATA_HEALTH_ID} 那个 {@code EntityDataAccessor<Float>}（private static final ⇒ 反射）。
     *
     * <p>为什么两个名字都试：本类的字节码<b>会被 reobf</b>，但<b>反射用的是字符串</b>、不会被重映射
     * ⇒ 开发环境里字段叫 {@code DATA_HEALTH_ID}，生产（SRG）里叫 {@code f_20961_}。
     *
     * @return accessor；拿不到 ⇒ {@code null}（调用方必须当"第二通道不可用"处理，不许假装成功）
     */
    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> healthAccessor() {
        final Object cached = healthAccessor;
        if (cached != null) {
            return (EntityDataAccessor<Float>) cached;
        }
        if (!HEALTH_ACCESSOR_PROBED.compareAndSet(false, true)) {
            return null;
        }
        for (final String name : new String[] { "DATA_HEALTH_ID", "f_20961_" }) {
            try {
                final Field f = LivingEntity.class.getDeclaredField(name);
                f.setAccessible(true);
                final Object v = f.get(null);
                if (v instanceof EntityDataAccessor<?>) {
                    healthAccessor = v;
                    healthAccessorVia = name;
                    ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX
                            + "第二通道已就绪：LivingEntity.DATA_HEALTH_ID 取自字段名 " + name
                            + "（直写同步血量；绕开任何人对 setHealth 的 cancel）");
                    return (EntityDataAccessor<Float>) v;
                }
            } catch (Throwable ignored) {
                // 试下一个名字（开发名 / SRG 名）
            }
        }
        healthAccessorVia = "(不可用)";
        ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                + "第二通道【不可用】：反射没拿到 LivingEntity.DATA_HEALTH_ID（试过 DATA_HEALTH_ID / f_20961_）"
                + " ⇒ 直写同步血量这一条腿关掉，回血只剩 setHealth（日志会写 heal_channel=failed）");
        return null;
    }

    /**
     * 🔴 <b>读【同步血量字段的真值】</b>（{@code getEntityData().get(DATA_HEALTH_ID)}）——
     * <b>不是</b> {@code getHealth()}：对手给 {@code getHealth()} 的每条 return 加了偏移。
     *
     * @return 字段真值；accessor 拿不到或读取抛异常 ⇒ {@code Float.NaN}（= 明确"读不到"，<b>不当 0</b>）
     */
    public static float readHealthField(LivingEntity entity) {
        final EntityDataAccessor<Float> accessor = healthAccessor();
        if (accessor == null || entity == null) {
            return Float.NaN;
        }
        try {
            final Float value = entity.getEntityData().get(accessor);
            return value == null ? Float.NaN : value.floatValue();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "读同步血量字段抛异常（当作读不到）：" + t);
            return Float.NaN;
        }
    }

    /**
     * 🔴 <b>第二通道：直写同步血量字段</b>（{@code getEntityData().set(DATA_HEALTH_ID, value, true)}）。
     *
     * <p>{@code force = true} 是有意的：就算字段当前值恰好等于目标值，也把 {@code isDirty} 立起来重发一次
     * —— 客户端那边可能还停在"死"的状态上，必须让它收到一次真实的血量。
     *
     * @return true = 写进去了；false = accessor 拿不到 / 写入抛异常（调用方必须走"失败"分支，不许假装成功）
     */
    public static boolean forceHealthField(LivingEntity entity, float value) {
        final EntityDataAccessor<Float> accessor = healthAccessor();
        if (accessor == null || entity == null) {
            return false;
        }
        try {
            entity.getEntityData().set(accessor, value, true);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "直写同步血量抛异常：" + t);
            return false;
        }
    }

    /**
     * 🔴 <b>核里那个空插槽 {@link ShanhaiGuardCore.SecondChannelHeal} 的实现</b>：绑定一个实体，
     * 让核的 {@link ShanhaiGuardCore#healViaSecondChannel(ShanhaiGuardCore.SecondChannelHeal, float)}
     * 能按"这个玩家"直写（不碰全局插槽 ⇒ 多玩家并发也不会写错人）。
     */
    public static final class FieldHealChannel implements ShanhaiGuardCore.SecondChannelHeal {

        private final LivingEntity entity;

        public FieldHealChannel(LivingEntity entity) {
            this.entity = entity;
        }

        @Override
        public boolean writeHealth(float value) {
            return forceHealthField(entity, value);
        }
    }

    // ═══════════════ ⑤ 对手桥：用对手自己的 public API 反向修复（反射；失效自动退回值层） ═══════════════
    //
    // 对手（coremod=true 满功率）那三件套，以及本类怎么反向修复（全部反射 + try/catch，类找不到就跳过）：
    //   ① getHealth() 的每条 return 加 delta（delta 存在同步数据里，客户端同样带偏移）
    //        ⇒ 调它自己的 public static setHealthDelta(LivingEntity, float) 把 delta 写成 0
    //          ⇒ 它的 getHealth() = 血量字段 + 0 = 真值（假死/假血一起消失）
    //   ② setHealth 上挂【单向锁存】：counter>=5 且新值 > 当前血 ⇒ cancel
    //        ⇒ counter 在玩家 ForgeData 的 omnimobs_counter_data（实测 /data get entity @s ForgeData.omnimobs_counter_data）
    //          ⇒ 读到 >= 5 就 putInt(..., 0) ⇒ 我们的 setHealth 又能生效
    //   ③ 它自己的"判死"：checkEntityDead(LivingEntity) ⇒ 每 tick 问一次，true 就并入复位判据
    //
    // 🔴 这一层【依赖对手的类名/方法名/数据键】，换版本就会失效：
    //    失效时只记一行 `omnimobs_bridge unavailable: <原因>`，之后不再重试；
    //    值层（setHealth ／ 直写 DATA_HEALTH_ID）照旧工作 ⇒ 功能不会因此消失，只是少一层。

    /** 只探测对手 API 一次。 */
    private static final AtomicBoolean OMNI_PROBED = new AtomicBoolean(false);

    /** 运行时失败只刷一行日志。 */
    private static final AtomicBoolean OMNI_FAILURE_LOGGED = new AtomicBoolean(false);

    /** 反射到的三个 Method（一次拿到就缓存，绝不每 tick 查名字）；任一为 null ⇒ 这层不可用。 */
    private static volatile Method omniSetDelta;
    private static volatile Method omniGetDelta;
    private static volatile Method omniCheckDead;

    /** 不可用的原因（只用于日志/诊断）。 */
    private static volatile String omniUnavailable = "(未探测)";

    /** 一旦确认不可用（类缺失 / 方法抛异常）⇒ 之后一律跳过，不再重试、不再刷日志。 */
    private static volatile boolean omniDisabled = false;

    /**
     * 反射探测对手的 {@code flashfur.omnimobs.util.GetHealthModifyUtil} 三个 {@code public static} 方法。
     *
     * <p>失败（没装 / 换了版本 / 方法签名变了）⇒ 记一次 {@code omnimobs_bridge unavailable: <原因>}，
     * 并且<b>永久跳过这一层</b>（自动退回值层）。
     */
    private static boolean probeOmnimobs() {
        if (omniDisabled) {
            return false;
        }
        if (omniSetDelta != null) {
            return true;
        }
        if (!OMNI_PROBED.compareAndSet(false, true)) {
            return false;   // 另一个线程正在探测 / 已经探测失败
        }
        try {
            final Class<?> util = Class.forName(ShanhaiGuardCore.OMNIMOBS_UTIL_CLASS);
            final Method set = util.getMethod(ShanhaiGuardCore.OMNIMOBS_METHOD_SET_DELTA,
                    LivingEntity.class, float.class);
            final Method get = util.getMethod(ShanhaiGuardCore.OMNIMOBS_METHOD_GET_DELTA, LivingEntity.class);
            final Method dead = util.getMethod(ShanhaiGuardCore.OMNIMOBS_METHOD_CHECK_DEAD, LivingEntity.class);
            requirePublicStatic(set);
            requirePublicStatic(get);
            requirePublicStatic(dead);
            set.setAccessible(true);
            get.setAccessible(true);
            dead.setAccessible(true);
            omniSetDelta = set;
            omniGetDelta = get;
            omniCheckDead = dead;
            ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "omnimobs_bridge ready："
                    + ShanhaiGuardCore.OMNIMOBS_UTIL_CLASS + " 的 "
                    + ShanhaiGuardCore.OMNIMOBS_METHOD_SET_DELTA + " / "
                    + ShanhaiGuardCore.OMNIMOBS_METHOD_GET_DELTA + " / "
                    + ShanhaiGuardCore.OMNIMOBS_METHOD_CHECK_DEAD + " 都拿到了"
                    + "（⑤ 用对手自己的 API 反向修复：清 delta ＋ 清锁存计数器）");
            return true;
        } catch (Throwable t) {
            omniUnavailable = "omnimobs_bridge unavailable: " + t;
            omniDisabled = true;
            // 对手没装 / 版本不同都是正常情况（其它整合包就没有这个 mod）⇒ INFO，一行，之后不再提
            ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + omniUnavailable
                    + "（这一层跳过、之后不再重试；值层 setHealth / 直写 DATA_HEALTH_ID 照旧工作）");
            return false;
        }
    }

    /** 校验对手的方法确实是 {@code public static} 且参数对（javap 说是；变了就明确报不可用，不硬调）。 */
    private static void requirePublicStatic(Method method) {
        final int mod = method.getModifiers();
        if (!Modifier.isPublic(mod) || !Modifier.isStatic(mod)) {
            throw new IllegalStateException(method.getName() + " 不是 public static（签名变了 ⇒ 这层不可用）");
        }
    }

    /** 反射失败的处理：确认这一层不可用 ＋ 只刷一行日志（之后永不重试）。 */
    private static void noteOmniFailure(String what, Throwable t) {
        omniDisabled = true;
        omniUnavailable = "omnimobs_bridge unavailable: " + what + " → " + t;
        if (OMNI_FAILURE_LOGGED.compareAndSet(false, true)) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + omniUnavailable
                    + "（这一层之后不再重试；值层照旧工作）");
        }
    }

    /**
     * ⑤ <b>读对手给 {@code getHealth()} 加的偏移</b>（{@code obtainHealthDelta}）。
     *
     * @return 偏移值；这一层不可用 ⇒ {@code Float.NaN}
     */
    public static float obtainOmnimobsDelta(LivingEntity entity) {
        if (entity == null || !probeOmnimobs()) {
            return Float.NaN;
        }
        try {
            final Object value = omniGetDelta.invoke(null, entity);
            return value instanceof Number number ? number.floatValue() : Float.NaN;
        } catch (Throwable t) {
            noteOmniFailure("obtainHealthDelta", t);
            return Float.NaN;
        }
    }

    /**
     * ⑤ <b>问对手自己的"判死"</b>（{@code checkEntityDead}）—— 这是它最全的一条死亡信号。
     *
     * @return true = 它说这个实体死了（这一层不可用 / 抛异常 ⇒ false，由值层的另外三条判据兜）
     */
    public static boolean omnimobsSaysDead(LivingEntity entity) {
        if (entity == null || !probeOmnimobs()) {
            return false;
        }
        try {
            final Object value = omniCheckDead.invoke(null, entity);
            return value instanceof Boolean bool && bool.booleanValue();
        } catch (Throwable t) {
            noteOmniFailure("checkEntityDead", t);
            return false;
        }
    }

    /**
     * ⑤ <b>把 {@code getHealth()} 的偏移清零</b>（{@code setHealthDelta(entity, 0f)}）——
     * 对手的 {@code getHealth()} = 血量字段 + delta，delta 归零后读数就是真值，假死/假血一起消失。
     *
     * <p>只在 delta 非 0 时才写（避免每 tick 都发一次同步包）；写完记一句
     * {@code omnimobs_bridge=delta_reset}。
     *
     * @return 给日志的一段话；没有偏移 / 这一层不可用 ⇒ {@code null}
     */
    public static String clearOmnimobsDelta(LivingEntity entity) {
        if (entity == null || !probeOmnimobs()) {
            return null;
        }
        try {
            final Object current = omniGetDelta.invoke(null, entity);
            final float before = current instanceof Number number ? number.floatValue() : 0f;
            if (before == 0f) {
                return null;   // 没有偏移 ⇒ 不动它（别每 tick 发一次同步包）
            }
            omniSetDelta.invoke(null, entity, 0f);
            return "omnimobs_bridge=delta_reset ／ delta 原值=" + before + " → 0.0"
                    + "（它的 getHealth() 恢复真值）";
        } catch (Throwable t) {
            noteOmniFailure("clearOmnimobsDelta", t);
            return null;
        }
    }

    /**
     * ⑤ <b>清对手的「单向锁存计数器」</b>：{@code counter >= 5} 起它会 cancel 任何"加血"的 {@code setHealth}
     * ⇒ 不清零的话我们的第一条腿<b>永久无效</b>（这就是"中招后 setHealth 补血永久无效"的机制）。
     *
     * <p>这个键住在玩家 ForgeData 里（{@code player.getPersistentData()}，实测
     * {@code /data get entity @s ForgeData.omnimobs_counter_data}）—— <b>不需要反射</b>，但同样只对受保护玩家生效。
     *
     * @return 给日志的一段话；没锁 / 没有这个键 ⇒ {@code null}
     */
    public static String clearOmnimobsCounter(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            final CompoundTag data = player.getPersistentData();
            if (data == null) {
                return null;
            }
            final int before = data.getInt(ShanhaiGuardCore.OMNIMOBS_COUNTER_KEY);
            if (!ShanhaiGuardCore.shouldResetOmnimobsCounter(before)) {
                return null;   // 没到锁存阈值 ⇒ 不碰别人的数据
            }
            data.putInt(ShanhaiGuardCore.OMNIMOBS_COUNTER_KEY, 0);
            return "omnimobs_bridge=counter_reset ／ " + ShanhaiGuardCore.OMNIMOBS_COUNTER_KEY
                    + " 原值=" + before + " → 0（对手的 setHealth 锁已解）";
        } catch (Throwable t) {
            noteOmniFailure("clearOmnimobsCounter", t);
            return null;
        }
    }

    // ═══════════════════════════════ 两条腿补血（第二通道的落地） ═══════════════════════════════

    /**
     * 🔴 <b>两条腿补血的读数</b>：走的是哪条通道 ＋ 给日志的那一段话（{@code heal_channel=…}）。
     */
    public static final class HealOutcome {

        /** 最终生效的通道。 */
        public final ShanhaiGuardCore.HealChannel channel;

        /** 直接进日志的一段话（含 {@code heal_channel=…} 与两条腿各自的读数）。 */
        public final String detail;

        HealOutcome(ShanhaiGuardCore.HealChannel channel, String detail) {
            this.channel = channel;
            this.detail = detail;
        }

        /** 血量真的落盘了（setHealth 或 direct 之一成功）。 */
        public boolean landed() {
            return channel != ShanhaiGuardCore.HealChannel.FAILED;
        }
    }

    /**
     * 🔴 <b>两条腿补血</b>（① 实机证据要求的顺序）：
     *
     * <pre>
     *   第一腿：entity.setHealth(max)
     *           → 自证（能读字段真值就只用字段真值；读不到才退回 getHealth()）
     *   第二腿：第一条腿自证不通过（可能被别人的 @Inject cancel 了）
     *           → entity.getEntityData().set(DATA_HEALTH_ID, max, true)   ← 绕开 setHealth
     *           → 自证（🔴 必须用字段真值，绝不用 getHealth()）
     * </pre>
     *
     * <p>两条腿都失败 ⇒ {@code channel = FAILED}（<b>不假装成功</b>）。
     * 每次调用都会给核里的 {@code healFailureCount()} 记一次（走 {@link ShanhaiGuardCore#healRoundTrip}），
     * 语义与老的单腿版本一致："一次补血动作的自证次数/失败次数"。
     */
    public static HealOutcome healTwoLegs(LivingEntity entity) {
        if (entity == null) {
            return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED, "heal_channel=failed ／ 实体为空");
        }
        final float target = ShanhaiGuardCore.healTarget(entity.getMaxHealth());
        final StringBuilder bridge = new StringBuilder();   // ⑤ 对手桥这一段干了两件什么（进日志）

        // ── ⑤-1 🔴 先清对手的「单向锁存计数器」：不清它，下面第一腿一定被它的 @Inject 取消 ──
        if (entity instanceof ServerPlayer serverPlayer) {
            final String counter = clearOmnimobsCounter(serverPlayer);
            if (counter != null) {
                bridge.append(" ／ ").append(counter);
            }
        }

        // ── 第一腿：setHealth（老通道；可能被别人的 @Inject(HEAD, cancellable) 直接取消）──
        entity.setHealth(target);
        final float fieldAfterFirst = readHealthField(entity);
        // 计数用的读数必须与"自证用的读数"同源：字段真值读不到时才退回 getHealth()（否则会把"读不到"记成失败）
        final float firstReadBack = Float.isNaN(fieldAfterFirst) ? entity.getHealth() : fieldAfterFirst;
        final ShanhaiGuardCore.Decision first = ShanhaiGuardCore.setHealthProof(
                target, fieldAfterFirst, entity.getHealth());
        final ShanhaiGuardCore.HealChannel channel;
        final String detail;
        if (!first.yes) {
            ShanhaiGuardCore.healRoundTrip(target, firstReadBack);   // 计数（这一次自证通过）
            channel = ShanhaiGuardCore.HealChannel.SET_HEALTH;
            detail = "heal_channel=" + channel.label() + " ／ " + first.reason;
        } else {
            // ── 第二腿：直写同步血量字段（绕开 setHealth 的 cancel）──
            final boolean wrote = ShanhaiGuardCore.healViaSecondChannel(new FieldHealChannel(entity), target).yes;
            final float fieldAfterSecond = readHealthField(entity);
            final ShanhaiGuardCore.Decision second = ShanhaiGuardCore.directWriteProof(target, wrote, fieldAfterSecond);
            ShanhaiGuardCore.healRoundTrip(target, fieldAfterSecond);  // 计数（通过 ／ 失败，都记这一次）
            if (!second.yes) {
                channel = ShanhaiGuardCore.HealChannel.DIRECT;
                detail = "heal_channel=" + channel.label()
                        + " ／ 第一腿 setHealth 自证没通过（" + first.reason + "）"
                        + " ⇒ 第二腿直写同步血量成功：" + second.reason;
            } else {
                channel = ShanhaiGuardCore.HealChannel.FAILED;
                detail = "heal_channel=" + channel.label()
                        + " ／ 第一腿（" + first.reason + "）／ 第二腿（" + second.reason + "）";
            }
        }

        // ── ⑤-3 🔴 最后清对手的 getHealth() 偏移（delta）：它的 getHealth() = 血量字段 + delta ──
        final String delta = clearOmnimobsDelta(entity);
        if (delta != null) {
            bridge.append(" ／ ").append(delta);
        }
        return new HealOutcome(channel, detail + bridge);
    }

    // ═══════════════════════════ ②/③/④ 共用的复位动作（唯一一份） ═══════════════════════════

    /**
     * 🔴 <b>把「已经进入死亡流程」的人拉回来</b> —— 全工程<b>唯一一份</b>复位动作。
     *
     * <pre>
     *   两条腿补血（{@link #healTwoLegs}）               // 对手可能 cancel 掉 setHealth ⇒ 必须带第二通道
     *   entity.deathTime = 0;                          // 死亡计时器归零（1.20.1：public int，javap 已确认 f_20919_）
     *   entity.hurtTime  = 0;                          // 受击计时器归零（1.20.1：public int，javap 已确认 f_20916_）
     * </pre>
     *
     * <p>三个调用点：② {@code ShanhaiGuardEvents#guardOne} 每 tick 看门狗、③ 客户端每 tick、④ {@code die} 拒绝。
     * <b>类型取 {@code LivingEntity} 而不是 {@code ServerPlayer}</b>：客户端那一路拿到的只有 {@code LocalPlayer}
     * （它在客户端不是 {@code ServerPlayer}），而这三个成员都在 {@code LivingEntity} 上。
     *
     * @return 补血那一步的读数（通道 ＋ 给日志的话）
     */
    public static HealOutcome resetDeathFlow(LivingEntity entity) {
        if (entity == null) {
            return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED, "heal_channel=failed ／ 实体为空");
        }
        final HealOutcome outcome = healTwoLegs(entity);
        entity.deathTime = 0;
        entity.hurtTime = 0;
        return outcome;
    }

    // ═══════════════════════════════ ④ die(DamageSource) 直接拒绝 ═══════════════════════════════

    /**
     * 🔴 <b>服务端直接拒绝死亡</b>：{@code LivingEntity#die(DamageSource)} / {@code ServerPlayer#die(DamageSource)}
     * 的 {@code HEAD} 返回 true ⇒ 调用方 {@code ci.cancel()}，{@code die} 的<b>一行都不执行</b>。
     *
     * <p>判据全在核里（{@link ShanhaiGuardCore#refuseDieDecision}：开关 ＋ 受保护），本方法只负责执行：
     * 拒绝 ＋ 顺手用 {@link #resetDeathFlow}（两条腿）把血与两个计时器复位 ＋ 通知事件层打日志/发聊天栏。
     *
     * <p>🔴 <b>为什么必须同时挂在 {@code ServerPlayer#die} 上（javap 取证，不是推测）</b>：
     * <pre>
     *   javap -p -c net.minecraft.server.level.ServerPlayer
     *     public void die(DamageSource);       ← 方法体里【没有】任何 invokespecial ...die
     *   javap -p -c net.minecraft.world.entity.player.Player
     *        9: invokespecial  #1131  // Method net/minecraft/world/entity/LivingEntity.die:...
     *   javap -p -c net.minecraft.world.entity.LivingEntity
     *        2: invokestatic   #2223  // Method net/minecraftforge/common/ForgeHooks.onLivingDeath:...
     * </pre>
     * 即：玩家死时的虚分派落在 {@code ServerPlayer#die}，而它<b>从不调 {@code super.die(...)}</b>
     * ⇒ 只在 {@code LivingEntity#die} 上注入的话，<b>对真实玩家永远打不到</b>（那一条留着当基类兜底）。
     *
     * <p>🔴 <b>防递归</b>：{@link #BUSY} 为 true（说明我们自己正在复位）⇒ <b>仍然拒绝，但不再动手复位</b>
     * —— 只拒绝不动手 ⇒ 不会无限递归；而血量留给 ② 每 tick 看门狗拉回。
     *
     * @param self     目标实体（mixin 里传 {@code (Object) this}）
     * @param callSite 命中点（只进日志的原因串：{@code LivingEntity#die} / {@code ServerPlayer#die}）
     * @return true ⇒ 调用方必须 {@code ci.cancel()}
     */
    public static boolean refuseDie(Object self, String callSite) {
        if (!(self instanceof ServerPlayer player)) {
            return false;   // 生物 / 客户端 LocalPlayer / 别的实现：一个字节都不改
        }
        try {
            final ShanhaiGuardCore.Decision decision = ShanhaiGuardCore.refuseDieDecision(
                    ShanhaiGuardCore.enabled, ShanhaiGuardEvents.isGuarded(player));
            if (!decision.yes) {
                return false;   // 开关关 / 不是受保护玩家 ⇒ 原样放行
            }
            String healNote = "（重入：只拒绝、不再动手复位）";
            if (!BUSY.get()) {
                BUSY.set(Boolean.TRUE);
                try {
                    // 🔴 与 ② 同一套工具方法（别复制第二份）：两条腿补血 ＋ deathTime/hurtTime 归零。
                    //    这里调 setHealth 不会重入 ① 的 mixin：值 = 满血上限 > 0（① 的第一道闸门），
                    //    而且此时 BUSY=true（① 的第二道闸门）⇒ 双保险。
                    healNote = resetDeathFlow(player).detail;
                } finally {
                    BUSY.set(Boolean.FALSE);
                }
            }
            // 🆕 A 件·取证：REFUSED die 这条路上的外部调用帧（限流 3 次，标签 die_caller#N）
            noteDieCaller(player, callSite);
            ShanhaiGuardEvents.noteDieRefused(player, callSite, decision.reason + " ／ " + healNote);
            return true;
        } catch (Throwable t) {
            // 兜底：我们坏了也不许改变原版死亡行为（打出来，不静默）
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "die 拒绝判据抛异常（原样放行）：" + t);
            return false;
        }
    }

    /**
     * 🆕 <b>A 件·取证（die 那一路）</b>：把这一拍的<b>外部 mod 调用帧</b>打出来。
     *
     * <p>形状与 {@code removal_caller} 完全一致，只是标签换成 {@code die_caller}（规格点名）：
     * <pre>
     *   [SHANHAI-GUARD] die_caller#1 at=com.x.Boss#attack:88 frames=...
     * </pre>
     * <b>每局最多 3 次</b>，与 {@code removal_caller} 各记各的账（互不吃额度）。
     *
     * <p>🔴 只在"受保护玩家被拒绝死亡"之后调用（{@code decision.yes} 分支）；
     * 生物 / 别的玩家 / 没开开关 ⇒ 根本走不到这里（{@code refuseDie} 开头就 return false）。
     * 取证自己抛异常也只吞掉，<b>绝不改变"拒死"这个结论</b>。
     */
    private static void noteDieCaller(ServerPlayer player, String callSite) {
        try {
            final String trace = ShanhaiGuardCallerTrace.capture(ShanhaiGuardCallerTrace.LABEL_DIE);
            if (trace == null) {
                return;   // 额度用完（或抓栈抛异常）⇒ 一行都不打
            }
            ShanhaiMod.LOGGER.warn(trace + " ｜ callSite=" + callSite
                    + " ／ player=" + player.getName().getString());
        } catch (Throwable ignored) {
            // 取证是"附加动作"：它坏了绝不许影响"拦不拦死亡"
        }
    }
}
