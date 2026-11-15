package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 🔴 <b>【创始现实修改模块】「值层兜底」的执行层</b>（2026-10-17 新增；只这一份，所有调用点共用）。
 *
 * <h2>为什么要有这一层（实机证据 ＋ 日志证据，两条都不推翻）</h2>
 * <pre>
 *   ① 实机：敌人不走 LivingAttackEvent 时 —— 事件层三条拦截都在生效、补血自证也成功，
 *      玩家却进入「死但没死透」：死亡界面被压住，人僵住不能动。
 *   ② 日志证据：有的第三方 mod 的杀人链【根本不经过 setHealth】——
 *      它直接裸写同步血量字段（LivingEntity.f_20961_，即 DATA_HEALTH_ID）⇒ setHealth 上的拦截看不到这一笔；
 *      它还：
 *        · 在 getHealth() 的 return 上做手脚 ⇒ 读回值 != 真实血量（假死 / 假血）；
 *        · 把别人的 setHealth 取消掉 ⇒ "以为补上了、其实没补"。
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
 * </ol>
 *
 * <p>🔴 <b>本类不引用任何第三方 mod 的类名／方法名／数据键</b>：所有判据只用
 * {@code LivingEntity} 的原版 API（{@code getHealth()} / {@code getMaxHealth()} / {@code getEntityData()}）
 * ＋ 下面用反射拿到的那一个原版字段 {@code DATA_HEALTH_ID}。写血那一路上另有一条
 * <b>与具体 mod 无关</b>的读侧交叉校验（{@link #noteReadSideMismatch(LivingEntity)}）。
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

    // ═══════════════════════════ 钩子自检的降级标志（data_only） ═══════════════════════════

    /**
     * 🔴 <b>降级标志</b>（{@code data_only}；由扩展侧
     * {@code com.shanhai.add.guard.selfcheck.ShanhaiHookSelfCheck} 在"我们的注入没了 ／ 判据失效"时置上）。
     *
     * <p>true ⇒ {@link #healTwoLegs(LivingEntity)} <b>跳过 setHealth 那条腿</b>，只走第二通道
     * （读 {@code DATA_HEALTH_ID} ＋ 直写同步血量字段）。理由：自检说 setHealth 那条注入可能已经被剥掉，
     * 那么"调 setHealth 再自证"这一步就是在靠一条没人守的路；直写字段本来就绕开它，而判据
     * （{@code directWriteProof} / {@code healRoundTrip}）<b>一字不改</b>。
     *
     * <p>🔴 <b>默认 false ⇒ 行为与加这个开关之前逐字相同</b>；它<b>不</b>参与任何拦截判定
     * （setHealth 入参改写 / die 拒绝 / setRemoved 拒绝都不看它），只改"补血先走哪条腿"。
     * 存储放在本体而不是扩展：依赖方向是 add → 本体，本体不许 import {@code com.shanhai.add.*}。
     */
    private static volatile boolean dataOnly;

    /** 现在是降级（data_only）模式吗。 */
    public static boolean dataOnly() {
        return dataOnly;
    }

    /**
     * 置/清降级标志（只有扩展侧的钩子自检会调）。
     *
     * <p>⚠️ 刻意<b>不打日志</b>：失效那一行由自检统一打，保证"失效时恰好一行"。
     */
    public static void setDataOnly(boolean on) {
        dataOnly = on;
    }

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
            // 🆕 T 件 R3（只读）：走到这儿必然是【外部直调 setHealth(<=0)】——
            //    上面「值 > 0 一律原样」那道闸门保证了我们自己的 setHealth(max) 永远进不到这里。
            //    刻意放在 BUSY 判断【之前】：重入也算"外部直调"，两条分支都要记到。
            //    ⚠️ 它只是往一个计数里置一个布尔，绝不影响本方法的返回值。
            ShanhaiGuardTriageBridge.noteDirectSetHealth(player, value);
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

    // ═══════════ 读侧交叉校验（与具体 mod 无关：只比原版两个读数） ═══════════

    /** 这一行日志的节流窗口（毫秒）：最多 5 秒一行（复用工程里既有的 {@link ShanhaiGuardTriage.Throttle}）。 */
    private static final ShanhaiGuardTriage.Throttle READ_SIDE_LOG =
            new ShanhaiGuardTriage.Throttle(5_000L);

    /** 命中次数（累计，从不减；这一行被节流时照样累加）。 */
    private static final AtomicLong READ_SIDE_HITS = new AtomicLong();

    /**
     * 🔴 <b>读侧交叉校验</b>：拿 {@code entity.getHealth()}（读侧）跟 {@link #readHealthField}
     * （同步血量字段的真值）比 —— <b>读数明显小于字段值</b> ⇒ 判「读侧被改写了」：字段里其实还有血，
     * {@code getHealth()} 却报了一个更小的数（最毒的一种是把活人报成 0 ⇒ 客户端/别的 mod 都当他死了）。
     *
     * <p>判据本体在纯逻辑核里（{@link ShanhaiGuardCore#readSideMismatch}，容差
     * {@link ShanhaiGuardCore#READ_SIDE_TOLERANCE} = 0.01）—— 本方法只做两件事：
     * ① 读两个读数；② 命中时记一行<b>限流</b>日志（最多 5 秒一行 ＋ 累计计数）。
     * <b>写血逻辑一个字都不改</b>：调方（{@link #healTwoLegs}）照旧把字段写回满值。
     *
     * <p>🔴 判据只碰原版这两样：{@code LivingEntity#getHealth()} 与
     * {@code getEntityData().get(DATA_HEALTH_ID)}（后者由 {@link #readHealthField} 提供）
     * ⇒ <b>零第三方类名／零第三方方法名／零自定义数据键</b>，也不需要任何配置项。
     *
     * <p>⚠ 读数与字段<b>相等</b>、读数<b>偏大</b>、任一读不到（NaN）⇒ 这一条都不成立（不猜、不报）。
     */
    private static void noteReadSideMismatch(LivingEntity entity) {
        final float field = readHealthField(entity);
        final float hp = entity.getHealth();
        if (!ShanhaiGuardCore.readSideMismatch(hp, field)) {
            return;   // 没有"读数明显偏小"⇒ 这一条不成立
        }
        final long hits = READ_SIDE_HITS.incrementAndGet();
        if (!READ_SIDE_LOG.admit("read_side_mismatch", System.currentTimeMillis())) {
            return;   // 5 秒内已经打过一行 ⇒ 只累计、不刷屏
        }
        ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                + "read_side_mismatch hp=" + hp + " field=" + field + " lost=" + (field - hp)
                + " hits=" + hits);
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
     *
     * <p>🔴 <b>降级（{@link #dataOnly()} = true）</b>：第一腿<b>直接跳过</b>，只走第二腿
     * （读 {@code DATA_HEALTH_ID} 真值 ＋ 直写同步血量字段）。判据一条都不改（还是
     * {@link ShanhaiGuardCore#directWriteProof} ＋ {@link ShanhaiGuardCore#healRoundTrip}），
     * 只改"先走哪条腿"；默认 false ⇒ 与加这个开关之前逐字相同。
     */
    public static HealOutcome healTwoLegs(LivingEntity entity) {
        if (entity == null) {
            return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED, "heal_channel=failed ／ 实体为空");
        }
        // 🔴 写血之前先做一次读侧交叉校验（每 tick 兜底写血这条路都会走到这里）：
        //    getHealth() 明显小于字段真值 ⇒ "读侧被改写了"，记一行限流日志（写血逻辑一个字都不改）。
        noteReadSideMismatch(entity);
        final float target = ShanhaiGuardCore.healTarget(entity.getMaxHealth());

        // ── 第一腿：setHealth（老通道；可能被别人的 @Inject(HEAD, cancellable) 直接取消）──
        // 🔴 降级（data_only）：钩子自检判"setHealth 那条注入已经没了"⇒ 跳过这条腿，
        //    直接进第二腿（读 DATA_HEALTH_ID 真值 ＋ 直写同步血量字段）。判据一条都不改，
        //    只是不再把"补血"押在一条没人守的 setHealth 上。
        final boolean dataOnlyHeal = dataOnly();
        final ShanhaiGuardCore.Decision first;
        float firstReadBack = Float.NaN;
        if (dataOnlyHeal) {
            first = ShanhaiGuardCore.Decision.of(true,
                    "data_only 降级：跳过 setHealth 腿（强制直写同步血量字段）");
        } else {
            entity.setHealth(target);
            final float fieldAfterFirst = readHealthField(entity);
            // 计数用的读数必须与"自证用的读数"同源：字段真值读不到时才退回 getHealth()（否则会把"读不到"记成失败）
            firstReadBack = Float.isNaN(fieldAfterFirst) ? entity.getHealth() : fieldAfterFirst;
            first = ShanhaiGuardCore.setHealthProof(target, fieldAfterFirst, entity.getHealth());
        }
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
                        + (dataOnlyHeal ? " ／ data_only 降级：跳过 setHealth 腿"
                                        : " ／ 第一腿 setHealth 自证没通过（" + first.reason + "）")
                        + " ⇒ 第二腿直写同步血量成功：" + second.reason;
            } else {
                channel = ShanhaiGuardCore.HealChannel.FAILED;
                detail = "heal_channel=" + channel.label()
                        + (dataOnlyHeal ? " ／ data_only 降级：跳过 setHealth 腿"
                                        : " ／ 第一腿（" + first.reason + "）")
                        + " ／ 第二腿（" + second.reason + "）";
            }
        }

        return new HealOutcome(channel, detail);
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
            // 🆕 T 件 R4＋R2：die 被触达 ⇒ R4；"拒死"本身就是一次救 ⇒ R2 的"救过"（两行都只读，不改返回值）
            ShanhaiGuardTriageBridge.noteDie(player);
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
