package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

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

    // ═══════════════ 🆕 2026-11-20：写入点钳制 ／ 出站包钳制（用户拍板：保护"数据"，不认任何 mod） ═══════════════
    //
    // 用户口径（这一节的唯一动因）：
    //   「要么你就干脆保护玩家的代码不被修改啊」
    //   「不认我，我也不认你，我就知道你改了我的状态」
    // ⇒ 判据只有两条，一个 mod 名都没有：
    //   ① 这次写/发的值会不会让一个活着的实体"读成死的"（<=0 ／ NaN ／ ±∞）；
    //   ② 这个实体是不是受保护玩家（ShanhaiGuardEvents.isGuarded，与客户端同源的那一份）。
    //
    // 落点选在【写入点】而不是"每 tick 事后纠"，原因是实机读数：
    //   对手 20/s 直接裸写同步血量字段 ⇒ 我们每 tick 补一次永远追不上，而且每一拍那个 0
    //   都已经同步给客户端 ⇒ 客户端每 tick 想弹死亡界面（[CHAT] CANCELLED ScreenEvent.Opening x20~23/s）。
    //   钳在写入点之后：字段里永远是 >= CLAMP_MIN_HEALTH ⇒ 客户端根本收不到"死"这个读数。

    /** 钳制后的最低血量（用户口径「改成钳到 1（或 0.5）」⇒ 取 1.0；血上限本身低于 1 的取上限）。 */
    public static final float CLAMP_MIN_HEALTH = 1.0F;

    /**
     * 这个值会不会让"活着的实体"读成死的（{@code <=0 ／ NaN ／ ±∞}）。
     *
     * <p>{@code NaN} 靠 {@code !(v > 0)} 兜住（NaN 的任何比较都是 false），
     * {@code +∞} 靠 {@link Float#isInfinite} 兜住（{@code Infinity > 0} 为真）。
     */
    public static boolean needsHealthClamp(float value) {
        return !(value > 0.0F) || Float.isInfinite(value);
    }

    /** {@code SynchedEntityData} 里那个"这条同步数据属于谁"的字段（反射一次就缓存）。 */
    private static Field SYNCED_DATA_ENTITY;

    /** 反射试过一次没有（失败也只打一行，不每 tick 重试）。 */
    private static boolean SYNCED_DATA_ENTITY_TRIED;

    /** {@code health_clamp} / {@code health_packet_clamp} 的 1/s 限流（毫秒时间戳）。 */
    private static long LAST_CLAMP_LOG = 0L;

    /** 被压掉的行数（1/s 窗口内又发生了几次；下一次那一行里带 {@code suppressed=}）。 */
    private static final AtomicLong CLAMP_SUPPRESSED = new AtomicLong();

    /** 累计钳制了几次写（日志/读数用）。 */
    private static final AtomicLong CLAMPED_WRITES = new AtomicLong();

    /** 累计钳制了几个出站包。 */
    private static final AtomicLong CLAMPED_PACKETS = new AtomicLong();

    /** 读数（一行；命令/自检可用）。 */
    public static String clampStatusLine() {
        return "health_clamp_writes=" + CLAMPED_WRITES.get()
                + " health_clamp_packets=" + CLAMPED_PACKETS.get()
                + " clamp_min=" + CLAMP_MIN_HEALTH
                + " clamp_log_suppressed=" + CLAMP_SUPPRESSED.get();
    }

    /**
     * 🔴 <b>写入点钳制</b>：{@code SynchedEntityData#set(...)} 的入参改写。
     *
     * <p>为什么看"值是 Float"就够：玩家身上被同步的 Float 字段实际上只有血量一个
     * （其余是 byte/int/boolean/Optional/枚举），而我们<b>还</b>要求"这个值会读成死的"才算 ——
     * 一个 {@code <=0/NaN/±∞} 的 Float 同步写入，在受保护玩家身上没有第二种合理解释。
     *
     * @param synchedData {@code (Object) this}（mixin 转发进来的那个实例）
     * @param value       原值
     * @return 要写进去的值：该钳就钳成 {@link #CLAMP_MIN_HEALTH}；其余<b>逐字原样</b>
     */
    public static Object clampHealthDataWrite(Object synchedData, Object value) {
        if (!(value instanceof Float)) {
            return value;                                   // 不是 Float ⇒ 与血量无关
        }
        final float v = ((Float) value).floatValue();
        if (!needsHealthClamp(v)) {
            return value;                                   // 🔴 快路：正常值一个字节都不碰
        }
        final Object owner = ownerOfSynchedData(synchedData);
        if (!(owner instanceof LivingEntity le)) {
            return value;
        }
        if (!(le instanceof Player player)) {
            return value;                                   // 只保护玩家（生物照常会死）
        }
        if (!ShanhaiGuardEvents.isGuarded(player)) {
            return value;                                   // 不是受保护玩家 ⇒ 原样放行（不许阻断正常死亡）
        }
        final float to = clampTarget(le);
        CLAMPED_WRITES.incrementAndGet();
        logClamp("health_clamp from=" + v + " to=" + to + " player=" + playerName(player)
                + " src=data site=" + firstExternalFrame());
        return Float.valueOf(to);
    }

    /**
     * 🔴 <b>写入点钳制·第二条腿：元数据同步写进字段【之后】</b>
     * （{@code SynchedEntityData#assignValues(List)} 的 {@code RETURN}）。
     *
     * <h2>为什么 {@link #clampHealthDataWrite} 补不到这条路（javap 实证）</h2>
     * <pre>
     *   ClientPacketListener#handleSetEntityData
     *     → SynchedEntityData#assignValues(List)             ← 客户端主线程
     *       → assignValue → DataItem.setValue                ← 🔴 裸赋值，零 clamp
     *       → entity.onSyncedDataUpdated(accessor)           ← LivingEntity 里没有 DATA_HEALTH_ID 分支
     *   ⇒ 这条路【不经过 set(EntityDataAccessor, T, Z)】⇒ 上面那个 @ModifyVariable 一次都命中不了；
     *     而 LivingEntity#getHealth() 读的就是这个字段（1.20.1 :1046）
     *   ⇒ 于是 {@code -Infinity} 能原样落进字段，且没有任何一处原版代码会拦它。
     * </pre>
     *
     * <h2>语义（严格：只有一条路会写，其余全部逐字不变）</h2>
     * <pre>
     *   拿不到所属实体（反射没就绪 ／ 字段名不对）   ⇒ false（一步都不做，绝不靠猜字段名硬写）
     *   所属实体不是 LivingEntity                ⇒ false
     *   所属实体不是 Player（生物 ／ 载具 ／ 掉落物）⇒ false（🔴 它们照常会死，我们不介入）
     *   读到的血量正常（>0 且有限）               ⇒ false（🔴 快路：一个字节都不碰）
     *   不是受保护玩家（{@link ShanhaiGuardEvents#isGuarded}）⇒ false（🔴 原样保留：{@code -∞} 也照样留着）
     *   血上限不可用（<=0 ／ NaN ／ ±∞）          ⇒ false（写 NaN 比 -∞ 更糟，与本类既有纪律一致）
     *   受保护玩家 ＋ 读数已经 {<=0 ／ NaN ／ ±∞} ⇒ {@code setHealth(getMaxHealth())}
     *   任何 Throwable                          ⇒ 调用方 catch，什么都不做
     * </pre>
     *
     * <h2>为什么动作是 {@code setHealth(getMaxHealth())}（走 public API）</h2>
     * <pre>
     *   entity.setHealth(max) → getEntityData().set(DATA_HEALTH_ID, Mth.clamp(max,0,max))
     *     ⇒ DataItem.setValue ⇒ entity.onSyncedDataUpdated(accessor) ⇒ DataItem.setDirty(true) ＋ isDirty = true
     *   ⇒ 原版的 dirty 标记／下一次同步照常发生（下一个同步拍会把正确值再发一遍）；
     *   ⇒ 而且它走的是 {@code set(…Z)V} = 上面那条 {@link #clampHealthDataWrite}：那个方法对 >0 的值
     *     逐字放行 ⇒ 不会二次改写、也不会递归（本方法只在 assignValues 的 RETURN 上，setHealth 不经过它）。
     * </pre>
     *
     * <h2>与 {@link #clampLocalPlayerHealth}（客户端每 tick 兜底）的关系</h2>
     * 那一条原来额外跳过创造／旁观，<b>2026-11-21 已经删掉</b>（原版假设 ≠ 保证，详见那边的注释）；
     * 本方法位于<b>写入点</b>，门与同属写入点的 {@link #clampHealthDataWrite} 逐字同一套：
     * <b>受保护 ＋ 值已成死值</b>，仅此两条 ⇒ 现在两处的门一致了。
     *
     * <p>日志复用既有那条 {@code health_clamp} 口径（{@link #logClamp}，1 行/秒限流），
     * 只是把 {@code src=} 换成 {@code sync_data} —— <b>不新增第二种日志行</b>。
     *
     * @param synchedData {@code (Object) this}（mixin 转发进来的那个 {@code SynchedEntityData}）
     * @return true = 这一拍真的把血写回了 {@code getMaxHealth()}
     */
    public static boolean clampHealthAfterAssign(Object synchedData) {
        final Object owner = ownerOfSynchedData(synchedData);
        if (!(owner instanceof LivingEntity le)) {
            return false;                                   // 拿不到实体 ⇒ 一步都不做
        }
        if (!(le instanceof Player player)) {
            return false;                                   // 只保护玩家（生物照常会死）
        }
        final float read = le.getHealth();                  // 刚灌进字段的那个值（原版 getHealth 读的就是它）
        if (!needsHealthClamp(read)) {
            return false;                                   // 🔴 快路：正常值一个字节都不碰
        }
        if (!ShanhaiGuardEvents.isGuarded(player)) {
            return false;                                   // 不受保护 ⇒ 原样放行（-∞ 也逐字保留）
        }
        final float max = le.getMaxHealth();
        if (!(max > 0.0F) || Float.isInfinite(max)) {
            return false;                                   // 上限不可用 ⇒ 硬写只会造出更糟的值
        }
        le.setHealth(max);                                  // 走 public API：原版 dirty/同步照常发生
        CLAMPED_WRITES.incrementAndGet();
        logClamp("health_clamp from=" + read + " to=" + max + " player=" + playerName(player)
                + " src=sync_data site=" + firstExternalFrame());
        return true;
    }

    /**
     * 🔴 <b>出站血量包钳制</b>：{@code new ClientboundSetHealthPacket(health, …)} 的第一个参数。
     *
     * <p>就算字段已经被写入点钳住了，这一道仍然要留 —— 它挡的是"别人把 {@code getHealth()} 的返回值
     * 做了手脚"（包里的数被改掉、而字段是真值）以及"我们与对手撞在同一拍"的中间态。
     *
     * @param self   收件人（{@code (Object) this} = 那个 {@code ServerPlayer}）
     * @param health 即将进包的那个数
     * @return 该钳就钳成 {@link #CLAMP_MIN_HEALTH}；其余<b>逐字原样</b>
     */
    public static float clampPacketHealth(Object self, float health) {
        if (!needsHealthClamp(health)) {
            return health;                                  // 🔴 快路
        }
        if (!(self instanceof Player player)) {
            return health;
        }
        if (!ShanhaiGuardEvents.isGuarded(player)) {
            return health;
        }
        final float to = clampTarget((LivingEntity) player);
        CLAMPED_PACKETS.incrementAndGet();
        logClamp("health_packet_clamp from=" + health + " to=" + to + " player=" + playerName(player)
                + " packet=ClientboundSetHealthPacket site=" + firstExternalFrame());
        return to;
    }

    /** 钳到哪：取 {@link #CLAMP_MIN_HEALTH}，但血上限本身更低时取上限（绝不超过 max）。 */
    private static float clampTarget(LivingEntity entity) {
        try {
            final float max = entity.getMaxHealth();
            if (max > 0.0F && !Float.isInfinite(max) && max < CLAMP_MIN_HEALTH) {
                return max;
            }
        } catch (Throwable ignored) {
            // 读不到上限就用常量
        }
        return CLAMP_MIN_HEALTH;
    }

    /**
     * 🔴 <b>客户端那一侧的钳制（薄包装）</b>：本地玩家的血量已经是 {@code <=0 ／ NaN ／ ±∞}
     * ⇒ 当场按「回满」处理（{@link #resetDeathFlow} —— 与其它三个调用点同一份动作）。
     *
     * <h2>为什么客户端必须再钳一次（2026-11-21 实机判据，不是推测）</h2>
     * 服务端那两道钳制（写入点 ＋ 出站血量包）都碰不到客户端把血量装进字段的那条路：
     * <pre>
     *   ClientPacketListener#handleSetEntityData      (…/client/multiplayer/ClientPacketListener.java:498)
     *     → SynchedEntityData#assignValues            (…/network/syncher/SynchedEntityData.java:185)
     *       → assignValue → DataItem#setValue         (同上 :207 / :231)   ← ① 不经过 set(…Z)V，
     *                                                                       写入点那个 mixin 一次都命中不了
     *   LivingEntity#setHealth → Mth.clamp(v,0,max)   (…/world/entity/LivingEntity.java:1050)  ← ② clamp 只在这条路上
     * </pre>
     * 于是 {@code -Infinity} 能原样落进客户端字段，而 {@code getHealth()} 读的就是这个字段
     * （{@code LivingEntity.java:1046}）⇒ {@code isDeadOrDying()} 恒为 true ⇒ 原版每 tick 都想开死亡界面。
     *
     * <h2>判据 ／ 动作 ／ 目标：全部复用既有那一份（这里不新写任何判据）</h2>
     * <ul>
     *   <li>判据 = {@link #needsHealthClamp}（与写入点钳制 ／ 出站包钳制<b>逐字同一套</b>）；</li>
     *   <li>动作 = {@link #resetDeathFlow}（两条腿补血 ＋ {@code deathTime = 0} ＋ {@code hurtTime = 0}）；</li>
     *   <li>目标 = {@code getMaxHealth()}（客户端要的是"人活着"，不是"最低限度活着"）。</li>
     * </ul>
     *
     * <h2>🔴 负向路径（每条都写"不做什么"）</h2>
     * <pre>
     *   entity == null ／ 不是 Player            ⇒ false，一个字节不写（生物照常会死）
     *   不在客户端（level().isClientSide()=false）⇒ false（服务端有自己的两道，不走这条）
     *   guarded == false                          ⇒ false（不受保护 ⇒ 不许阻断正常死亡）
     *   🔴 创造 / 旁观<b>不再</b>是提前返回的条件（2026-11-21 删掉那道门；理由见方法体第一段注释）
     *   读数与字段都是正常值                       ⇒ false（快路：一次 > 0 比较，不写、不记日志）
     *   getMaxHealth() 不可用（NaN ／ ±∞ ／ 非正）  ⇒ false（硬写会把字段写成 NaN，比 0 更糟）
     *   🔴 只动传进来的这一个实体：不遍历、不查找、不缓存。调用方只传 {@code Minecraft#player}
     *      （= {@code LocalPlayer}）⇒ 其他实体（含别的玩家、生物）一个字都不会被改。
     * </pre>
     *
     * @param entity  只能是客户端本地玩家（{@code Minecraft#player}）
     * @param guarded 调用方已经算好的 {@link ShanhaiGuardEvents#isGuarded(Player)} 结论（同一份，不重算）
     * @return true = 这一拍真的把血写回了 {@code getMaxHealth()}
     */
    public static boolean clampLocalPlayerHealth(LivingEntity entity, boolean guarded) {
        if (entity == null || !(entity instanceof Player player)) {
            return false;
        }
        if (!entity.level().isClientSide()) {
            return false;                                  // 只做客户端那一侧
        }
        if (!guarded) {
            return false;                                  // 不受保护 ⇒ 原样放行（不许阻断正常死亡）
        }
        // 🔴 2026-11-21 07:1x 实机后【删掉】原来这一道门。原代码就是这三行：
        //      if (player.isCreative() || player.isSpectator()) { return false; }
        //    为什么删：**「创造模式不会死」是原版的假设，不是保证。**
        //      只要对手不走 {@code Player#hurt}（直接写同步血量字段 ／ 直接调 {@code die} ／
        //      改 {@code getHealth()} 的读侧），创造模式这道门<b>根本不在路上</b>。
        //    实机这一次就是它：受保护玩家在创造模式下，客户端 getHealth() 读到 -Infinity
        //      （字段真值 20.0）⇒ 这里 return false ⇒ 客户端一次都没救 ⇒ 死亡界面 203 次。
        //    ⇒ 创造 / 旁观<b>照常救</b>；防误伤的闸门仍是下面那条 {@link #needsHealthClamp}
        //      （读数与字段都正常就一个字节都不写 ⇒ 不会把创造模式的真实行为遮住）。
        final float read = entity.getHealth();             // 读侧：实机日志里那个 -Infinity 就是它
        final boolean fieldKnown = healthAccessor() != null;
        final float field = fieldKnown ? readHealthField(entity) : Float.NaN;
        if (!needsHealthClamp(read) && !(fieldKnown && needsHealthClamp(field))) {
            return false;                                  // 🔴 快路：正常值一个字节都不碰
        }
        final float max = entity.getMaxHealth();
        if (!(max > 0.0F) || Float.isInfinite(max)) {
            return false;                                  // 上限不可用 ⇒ 一步都不写
        }
        final HealOutcome outcome = resetDeathFlow(entity);
        CLAMPED_WRITES.incrementAndGet();
        logClamp("health_clamp from=" + read + " to=" + max
                + " field=" + (fieldKnown ? Float.toString(field) : "unreadable")
                + " player=" + playerName(player) + " src=client site=" + firstExternalFrame());
        return outcome.landed();
    }

    /** 玩家名（只进日志；拿不到 ⇒ {@code ?}，诊断行自己不许再抛）。 */
    private static String playerName(Player player) {
        try {
            return player.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 这条同步数据属于谁（{@code SynchedEntityData.entity}，{@code private final}）。
     *
     * <p>两个名字都试：开发环境 {@code entity} ／ 生产 SRG {@code f_135344_}（javap 查证：
     * {@code private final net.minecraft.world.entity.Entity f_135344_;}）。
     * 取不到 ⇒ 返回 {@code null} ⇒ 调用方<b>原样放行</b>（绝不因为"读不到"就把人弄成无敌）。
     */
    private static Object ownerOfSynchedData(Object synchedData) {
        if (synchedData == null) {
            return null;
        }
        try {
            if (!SYNCED_DATA_ENTITY_TRIED) {
                SYNCED_DATA_ENTITY_TRIED = true;
                Class<?> c = synchedData.getClass();
                while (c != null && SYNCED_DATA_ENTITY == null) {
                    for (final String name : new String[] { "entity", "f_135344_" }) {
                        try {
                            final Field f = c.getDeclaredField(name);
                            f.setAccessible(true);
                            SYNCED_DATA_ENTITY = f;
                            break;
                        } catch (NoSuchFieldException ignored) {
                            // 试下一个名字
                        }
                    }
                    if (SYNCED_DATA_ENTITY == null) {
                        c = c.getSuperclass();
                    }
                }
                if (SYNCED_DATA_ENTITY == null) {
                    ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                            + "health_clamp_unavailable reason=synched_data_entity_field_missing"
                            + " tried=[entity,f_135344_] ⇒ 写入点钳制降级（出站包钳制仍生效）");
                }
            }
            return SYNCED_DATA_ENTITY == null ? null : SYNCED_DATA_ENTITY.get(synchedData);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 栈里第一个<b>外部</b>帧（"谁写的"）。判"外部"的口径复用
     * {@link ShanhaiGuardCallerTrace#isSkipped(String)}（原版/Forge/Mixin/我们自己的类都算内部）。
     *
     * @return {@code 类名#方法:行}；一个都找不到 ⇒ {@code unknown}
     */
    private static String firstExternalFrame() {
        try {
            final StackTraceElement[] frames = new Throwable().getStackTrace();
            for (StackTraceElement e : frames) {
                final String cn = e.getClassName();
                if (cn.startsWith("com.shanhai.") || cn.startsWith("java.") || cn.startsWith("jdk.")) {
                    continue;   // 我们自己的壳（mixin 转发 / 本类 / 抓栈工具）不算"谁写的"
                }
                if (ShanhaiGuardCallerTrace.isSkipped(cn)) {
                    continue;
                }
                return cn + "#" + e.getMethodName() + ":" + e.getLineNumber();
            }
        } catch (Throwable ignored) {
            // 抓栈坏了只影响这一项读数
        }
        return "unknown";
    }

    /**
     * 钳制日志（🔴 <b>1 秒最多一行</b>，被压掉的次数进下一行的 {@code suppressed=}）。
     *
     * <p>为什么必须限流：对手是 20/s 的写，不限流就是 20 行/秒的刷屏 ——
     * 而这条日志的价值只在"钳制到底有没有在工作"，一行带计数的读数就够。
     */
    private static void logClamp(String body) {
        final long now = System.currentTimeMillis();
        if (now - LAST_CLAMP_LOG < 1000L) {
            CLAMP_SUPPRESSED.incrementAndGet();
            return;
        }
        LAST_CLAMP_LOG = now;
        final long suppressed = CLAMP_SUPPRESSED.getAndSet(0L);
        try {
            ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + body
                    + (suppressed > 0L ? " suppressed=" + suppressed : "")
                    + " ／ " + clampStatusLine());
        } catch (Throwable ignored) {
            // 日志失败绝不许影响写血
        }
    }

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

    // ══════════ 🆕 2026-11-21：判据层（读侧）第一层 —— LivingEntity#m_21224_ 的 RETURN ══════════
    //
    // 🔴 为什么这一层只能算「第一层」，而不是全部（javap 取证，不是推测）：
    //   对手在 getHealth() / isDeadOrDying() 上挂的是 @At("HEAD") + cancellable + setReturnValue，
    //   其中一个 priority = Integer.MAX_VALUE。Mixin 的应用次序是「低 priority 先应用、HEAD 注入
    //   后应用者排在最前」⇒ 一旦对方在 HEAD 上 cancel 并 setReturnValue，**后面（含 RETURN）一条都不跑**。
    //   ⇒ 这一层只在「没人 HEAD-cancel」时有效；真正的救命刀在【消费点】
    //     （客户端 Minecraft#m_91152_ / Minecraft#m_91398_ 的那两次读取、ClientPacketListener#m_142747_ 的死亡包）。
    //   ⇒ 🔴 本层不许为了抢它去调 priority 或换注入点（平局由加载顺序决定，不可控；
    //     与对手正面抢注入点只会让"到底谁生效"变成不可判）。

    /** 本层把 {@code true} 改写成 {@code false} 的累计次数（"判据层是否还归我们"的读数之一）。 */
    private static final AtomicLong JUDGE_SUPPRESSED = new AtomicLong();

    /** "第一次真的生效"只打一行（证明本层挂上了）。 */
    private static final AtomicBoolean JUDGE_ANNOUNCED = new AtomicBoolean(false);

    /** 判据层本层生效过几次。 */
    public static long judgeSuppressedCount() {
        return JUDGE_SUPPRESSED.get();
    }

    /** 判据层的读数（命令 ／ 探针日志用；一行）。 */
    public static String judgeStatusLine() {
        return "judge_suppressed=" + JUDGE_SUPPRESSED.get();
    }

    /**
     * 🔴 <b>判据层第一层</b>：{@code LivingEntity#isDeadOrDying()}（SRG {@code m_21224_}）的 {@code RETURN} 改写。
     *
     * <h2>语义（严格；每一条都要能对着代码读出来）</h2>
     * <pre>
     *   原返回值 false（原版认为"没死"）        ⇒ false（🔴 一个字节都不碰、逐位放行，先判这一条最便宜）
     *   {@code this} 不是 Player（怪物/载具/掉落物）⇒ false（🔴 硬红线：它们的行为必须逐字不变）
     *   不是受保护玩家（{@link ShanhaiGuardEvents#isGuarded}）⇒ false（原样保留原版结论）
     *   受保护玩家 ＋ 原返回值 true            ⇒ true（调用方据此 {@code cir.setReturnValue(false)}）
     *   任何 Throwable                        ⇒ false（= 不设返回值，原版读数照走）
     * </pre>
     *
     * <p>⚠️ <b>性能</b>：本方法在<b>每一个</b> {@code LivingEntity} 的每次 {@code isDeadOrDying()} 上被调用
     * （{@code baseTick} 每 tick、怪物也在跑）。所以第一行就是"原版没判死就返回"——
     * 正常世界里 {@code isGuarded}（要扫一遍物品栏）<b>一次都不会被调到</b>。
     *
     * <p>🔴 <b>为什么本方法在普通类里</b>：mixin 类体不许引 MC 成员（会拿开发名去 SRG 命名的类上找）。
     * 本类是普通类，会被 reobf ⇒ {@code isGuarded} / {@code getHealth} 在运行期对得上。
     *
     * @param self     目标实体（mixin 里传 {@code (Object) this}）
     * @param original 原版方法的返回值（{@code cir.getReturnValueZ()}）
     * @return true = 调用方必须把返回值改写成 {@code false}
     */
    public static boolean guardIsDeadOrDying(Object self, boolean original) {
        if (!original) {
            return false;                       // 🔴 快路：原版说"没死" ⇒ 逐位放行
        }
        try {
            if (!(self instanceof Player player)) {
                return false;                   // 🔴 非玩家：行为逐字不变（硬红线）
            }
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                return false;                   // 不受保护 ⇒ 原版结论原样保留
            }
            JUDGE_SUPPRESSED.incrementAndGet();
            if (JUDGE_ANNOUNCED.compareAndSet(false, true)) {
                ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "judge_layer_engaged"
                        + " layer=return at=LivingEntity#m_21224_@RETURN"
                        + " field=" + readHealthField(player) + " read=" + readHealthSafe(player)
                        + " player=" + playerName(player)
                        + " ／ 本层只在「没人 HEAD-cancel isDeadOrDying」时有效（见类注释）");
            }
            return true;
        } catch (Throwable t) {
            return false;                       // 我们坏了 ⇒ 不设返回值（原版读数照走）
        }
    }

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
     * 🔴 <b>两条腿补血</b>（🆕 2026-11-21 顺序对调：<b>直写字段在前，setHealth 退成兜底</b>）
     *
     * <pre>
     *   第一腿（主通道）：entity.getEntityData().set(DATA_HEALTH_ID, max, true)   ← 直写同步血量字段
     *           → 自证：回读字段 == 目标（{@link ShanhaiGuardCore#directWriteProof}）
     *   第二腿（兜底）：第一腿没落地（被改回 ／ 写不进）时才调 entity.setHealth(max)
     *           → 自证：🔴 仍只认【字段回读】（第三个实参传 NaN ⇒ 核里那条"字段读不到就退回
     *             getHealth()"的退化读数彻底关掉，见 {@link ShanhaiGuardCore#setHealthProof}）
     *   字段真值读不到（第二通道不可用）⇒ 只调 setHealth，并明确报 {@code channel=unproven}
     *           （"调了 setHealth" 不等于 "补上了"，所以这一笔【不记账】）
     * </pre>
     *
     * <p>为什么对调：现场 {@code field=20.0} 而 {@code hp=-Infinity} —— 目标值与读数都可能被改写，
     * 而 {@code setHealth} 还会被对手的 {@code @Inject(setHealth, HEAD, cancellable)} 单向取消。
     * 直写字段不走 {@code setHealth}，也就谈不上被它取消。
     *
     * <p>两条腿都失败 ⇒ {@code channel = FAILED}（<b>不假装成功</b>）。
     * 每次能自证的调用都会给核里的 {@code healFailureCount()} 记一次（走 {@link ShanhaiGuardCore#healRoundTrip}），
     * 语义与老版本一致："一次补血动作的自证次数/失败次数"。
     *
     * <p>🔴 <b>降级（{@link #dataOnly()} = true）</b>：直写没落地时<b>不再退回 setHealth</b>
     * （钩子自检说那条注入可能已经没人守了）⇒ 直接报 FAILED。判据一条都不改，只改"还走不走 setHealth"。
     */
    public static HealOutcome healTwoLegs(LivingEntity entity) {
        if (entity == null) {
            return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED, "heal_channel=failed ／ 实体为空");
        }
        // 🔴 写血之前先做一次读侧交叉校验（每 tick 兜底写血这条路都会走到这里）：
        //    getHealth() 明显小于字段真值 ⇒ "读侧被改写了"，记一行限流日志（写血逻辑一个字都不改）。
        noteReadSideMismatch(entity);
        final float target = ShanhaiGuardCore.healTarget(entity.getMaxHealth());

        // ═════════ 🆕 2026-11-21：口径统一成【字段真值】—— 判要判字段，写要直写字段 ═════════
        // 现场证据（这一节存在的唯一理由）：field=20.0 而 hp=-Infinity。
        //   ⇒ getHealth() 的读数与目标值都可能被改写；而 setHealth 还会被对手的
        //      @Inject(setHealth, HEAD, cancellable) 单向取消（提高血量时直接 cancel）。
        // ⇒ ① 判"要不要补"用字段真值（调用方已做：ShanhaiGuardEvents#guardOne / checkLife）；
        //    ② 补的动作【直写 DATA_HEALTH_ID】，不再把 setHealth 当主通道；
        //    ③ "算不算补上了"只认【回读字段 == 目标】——
        //       🔴 getHealth() 一律不参与记账，它只进日志的 read=（这就是本条规矩的可审计点）。
        final float fieldBefore = readHealthField(entity);
        final boolean fieldKnown = !Float.isNaN(fieldBefore);
        final boolean dataOnlyHeal = dataOnly();

        if (!fieldKnown) {
            // 第二通道不可用（accessor 没拿到）⇒ 只剩 setHealth，而没有任何东西能证明它落地
            entity.setHealth(target);
            logGuardHeal("unproven", fieldBefore, target, readHealthSafe(entity), false);
            return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED,
                    "heal_channel=failed ／ 同步血量字段真值读不到（第二通道不可用）"
                            + " ⇒ setHealth(" + target + ") 已调用但【无法自证】，不记账");
        }

        // ── 第一腿（现在是主通道）：直写同步血量字段（绕开任何人对 setHealth 的 cancel）──
        final boolean wrote = forceHealthField(entity, target);
        final float fieldAfterDirect = readHealthField(entity);
        final ShanhaiGuardCore.Decision direct =
                ShanhaiGuardCore.directWriteProof(target, wrote, fieldAfterDirect);
        if (!direct.yes) {
            ShanhaiGuardCore.healRoundTrip(target, fieldAfterDirect);   // 计数：这一次自证通过
            logGuardHeal("field", fieldBefore, target, readHealthSafe(entity), true);
            return new HealOutcome(ShanhaiGuardCore.HealChannel.DIRECT,
                    "heal_channel=" + ShanhaiGuardCore.HealChannel.DIRECT.label()
                            + " ／ 直写同步血量字段成功（绕开 setHealth 的 cancel）：" + direct.reason);
        }
        if (dataOnlyHeal) {
            ShanhaiGuardCore.healRoundTrip(target, fieldAfterDirect);   // 计数：这一次自证失败
            logGuardHeal("failed", fieldBefore, target, readHealthSafe(entity), false);
            return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED,
                    "heal_channel=failed ／ 直写（" + direct.reason + "）"
                            + " ／ data_only 降级：不再走 setHealth 腿");
        }

        // ── 第二腿（兜底）：直写没落地（被改回 ／ 写不进）⇒ 退回 setHealth；
        //    🔴 成败仍只认【字段回读】—— 第三个实参传 NaN，把核里那条
        //    "字段读不到就退回 getHealth()" 的退化读数彻底关掉。
        entity.setHealth(target);
        final float fieldAfterSet = readHealthField(entity);
        final ShanhaiGuardCore.Decision fallback =
                ShanhaiGuardCore.setHealthProof(target, fieldAfterSet, Float.NaN);
        ShanhaiGuardCore.healRoundTrip(target, fieldAfterSet);
        if (!fallback.yes) {
            logGuardHeal("setHealth", fieldBefore, target, readHealthSafe(entity), true);
            return new HealOutcome(ShanhaiGuardCore.HealChannel.SET_HEALTH,
                    "heal_channel=" + ShanhaiGuardCore.HealChannel.SET_HEALTH.label()
                            + " ／ 直写第一腿没落地（" + direct.reason + "）"
                            + " ⇒ setHealth 兜底落地：" + fallback.reason);
        }
        logGuardHeal("failed", fieldBefore, target, readHealthSafe(entity), false);
        return new HealOutcome(ShanhaiGuardCore.HealChannel.FAILED,
                "heal_channel=failed ／ 直写（" + direct.reason + "）"
                        + " ／ setHealth 兜底（" + fallback.reason + "）");
    }

    /** {@code guard_heal} 的 1 秒限流（补血每 tick 都可能发生，不许刷屏）。 */
    private static final ShanhaiGuardTriage.Throttle GUARD_HEAL_LOG = new ShanhaiGuardTriage.Throttle(1_000L);

    /** {@code guard_heal} 被限流压掉的条数（下一行带 {@code suppressed=}）。 */
    private static final AtomicLong GUARD_HEAL_SUPPRESSED = new AtomicLong();

    /** {@code guard_heal} 一共落地了几条（累计，从不减）。 */
    private static final AtomicLong GUARD_HEAL_LOGGED = new AtomicLong();

    /**
     * 🔴 <b>补血那一行（规格逐字：{@code guard_heal channel=<通道> from=<字段旧值> to=<新值> read=<getHealth()>}）</b>。
     *
     * <p>{@code channel} 取值：{@code field}（直写同步血量字段成了）／{@code setHealth}（直写没落地、setHealth 兜底成了）／
     * {@code failed}（两条腿都没落地）／{@code unproven}（字段真值读不到，无从自证 ⇒ 不记账）。
     *
     * <p>{@code read=} 是 {@code getHealth()} 的读数，<b>只作对照</b>（现场 {@code field=20.0} 而 {@code read=-Infinity}）。
     * 它<b>永不参与</b>这条日志的 {@code landed=} 结论 —— 结论只来自字段回读。
     */
    private static void logGuardHeal(String channel, float from, float to, float read, boolean landed) {
        final long hits = GUARD_HEAL_LOGGED.incrementAndGet();
        if (!GUARD_HEAL_LOG.admit("guard_heal", System.currentTimeMillis())) {
            GUARD_HEAL_SUPPRESSED.incrementAndGet();
            return;
        }
        final long suppressed = GUARD_HEAL_SUPPRESSED.getAndSet(0L);
        try {
            ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "guard_heal channel=" + channel
                    + " from=" + from + " to=" + to + " read=" + read
                    + " landed=" + landed
                    + (suppressed > 0L ? " suppressed=" + suppressed : "")
                    + " total=" + hits);
        } catch (Throwable ignored) {
            // 日志失败绝不许影响写血
        }
    }

    /**
     * {@code getHealth()} 的<b>只读快照</b>（只进日志；读不到 ⇒ {@code NaN}）。
     *
     * <p>为什么不直接用 {@code entity.getHealth()}：读侧可能被改成抛异常的形态，
     * 而这条路上的所有判断都<b>不依赖</b>它 —— 拿它换一行日志不值得冒断掉补血的险。
     */
    private static float readHealthSafe(LivingEntity entity) {
        try {
            return entity == null ? Float.NaN : entity.getHealth();
        } catch (Throwable t) {
            return Float.NaN;
        }
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
