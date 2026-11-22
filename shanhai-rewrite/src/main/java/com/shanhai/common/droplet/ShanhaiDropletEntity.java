package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import com.shanhai.registry.ShanhaiEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

/**
 * 强相互作用水滴的<b>服务端本体</b>（规格 3 / 4 / 5 / 6 / 8 / 9 / 12 / 13）。
 *
 * <h2>一、为什么 extends {@link Entity} 而不是 {@code LivingEntity}</h2>
 * 水滴是"弹体"，不是生物：它不需要属性表（{@code Attributes}）、不需要生物 AI / 药水 / 刷怪笼那一整套；
 * 而规格 8 要的「{@code MAX_HEALTH = 1}、{@code setHealth(1)}、{@code getMaxHealth() = 1}、
 * {@code hurt()} 恒 false、{@code isInvulnerableTo()} 恒 true、{@code setInvulnerable(true)}」
 * 在 {@link Entity} 上同样能<b>逐条</b>满足（{@code hurt}/{@code isInvulnerableTo}/{@code setInvulnerable}
 * 都是 {@code Entity} 自己的方法，见 {@code javap}）。反过来若继承 {@code LivingEntity}，
 * 就得给这个"弹体"补 4 个抽象方法、注册默认属性、并接受 mob 药水/属性/死亡计时那一套 —— 全是与规格无关的负担。
 * ⇒ 血量是<b>本类自己的</b>一个字段，语义与规格 8 完全一致：永远是 1，且任何伤害路径都返回 false。
 *
 * <h2>二、移动：每 tick 现算方向 + 直接位移，速度由指令设（默认 40 格/秒）</h2>
 * <ul>
 *   <li>速度<b>不再是常量</b>：{@code /shanhai droplet speed 10~200}（格/秒，默认 40 = 老实现的 2 倍），
 *       每 tick 现读一次并换算成格/tick（{@link ShanhaiDropletSettings#maxSpeedPerTick}）。</li>
 *   <li><b>无惯性</b>：不读上一 tick 的速度做插值，每 tick 用"当前位置 → 目标点"重算方向；
 *       {@code setDeltaMovement} 与 {@code setPos} 都用这同一个方向。</li>
 *   <li><b>不许瞬移</b>：位移长度恒为 {@code 速度/20}（跟随到点那一 tick 为更小值）。</li>
 *   <li><b>不穿透</b>：命中（{@link #tryHit}）与撞地形（{@code ShanhaiDropletExplosion#firstTerrainAlong}）
 *       都<b>扫这一 tick 的整段线段</b> {@code from→to}（{@code AABB#clip}），不是只看终点
 *       ⇒ 10 格/tick 的高速下也不会从目标/方块中间穿过去。</li>
 *   <li><b>头朝前</b>：先按运动方向写 {@code yaw/pitch}，再 {@code setPos} ⇒
 *       {@link #makeBoundingBox()} 用的就是本 tick 的朝向；{@code yRotO/xRotO}（插值旧值）
 *       由原版 {@code Level#tickNonPassenger} 的 {@code setOldPosAndRot()} 在 tick 之前写好
 *       （本类在 {@link #tick()} 里再显式钉一次，见方法内注释）。</li>
 *   <li><b>无重力</b>：构造器 {@code setNoGravity(true)}。</li>
 *   <li><b>不受地形阻挡</b>：{@code noPhysics = true}（规格 7 的"像旁观者一样穿过去"）。
 *       爆炸开关打开时也不需要原版碰撞 —— 地形是<b>本类自己扫</b>的（见 {@link ShanhaiDropletExplosion}）。</li>
 * </ul>
 *
 * <h2>三、目标：128 格内最近的、且<b>没被别的水滴领走</b>的那个，一旦锁上就不换（规格 3 / 5）</h2>
 * 候选 = 拥有者 128 格内的 {@link LivingEntity}（活着的、不是拥有者）；模式 2 再叠一层
 * {@link Enemy} 过滤。因为水滴本身继承的是 {@link Entity}（不是 {@code LivingEntity}），
 * 「排除水滴自己 / 其他水滴」由<b>类型过滤</b>自动成立 —— 掉落物、经验球同理也不是 {@code LivingEntity}。
 * <p>🔴 2026-11-24：选目标<b>不再由每个水滴各自决定</b>，而是每 tick 由
 * {@link ShanhaiDropletTargets} 做一次<b>整层分配</b>（同一目标只给一个水滴；都被领完才允许复用）。
 * 锁定后只在"目标死了 / 被移除 / 跨维度 / 跑出 128 格"时释放（规格 5 的"除非目标消失/死"）。
 *
 * <h2>四、命中：数据层直写 + 5 tick 冷却（规格 6）</h2>
 * 命中判定是<b>线段扫掠</b>（{@code AABB#clip}）而不是"到没到终点"，详见 {@link #tryHit}；
 * 真正的扣血在 {@link ShanhaiDropletCombat#applyDirectDamage}（直写 {@code DATA_HEALTH_ID}，不走 {@code hurt()}）。
 *
 * <h2>五、跟随：拥有者视图坐标系里固定「左 0.6d / 上 0.8d / 前 0」（规格 9）</h2>
 * {@code 0.6² + 0.8² = 1} ⇒ 与拥有者<b>眼睛</b>的距离恰好 = d（<b>指令可设</b>，默认
 * {@link ShanhaiDropletSettings#DEFAULT_FOLLOW_DISTANCE} = <b>2.5</b> 格；
 * 老实现写死 3/4 ⇒ 5 格，用户说"有点太远"）。因为偏移写在本体视图坐标系里，
 * 拥有者转头/俯仰时这个点会跟着转，水滴按"速度/20"追过去，到点保持。
 *
 * <h2>六、给客户端 HUD 的同步字段（规格 12）</h2>
 * 规格点名的那个同步注解来自一个第三方库的「托管字段」体系（按本轮硬约束：它的库名/类名不写进
 * 代码/注释/资源）：那套东西只对实现了它自己的接口的对象生效，原版 {@link Entity} 不在其中
 * ⇒ 把注标在实体字段上也<b>不会</b>同步任何东西。所以这里用<b>两者底层同一套</b>的原版
 * {@link SynchedEntityData}：{@code defineId/define} 定义、服务端 {@code set}、客户端 {@code get}。
 * 给足 5 个字段：
 * {@link #DATA_HAS_TARGET}（有目标）、{@link #DATA_TARGET_NAME}（目标名）、{@link #DATA_MODE}（模式）、
 * {@link #DATA_EXPLOSION}（爆炸开关）、{@link #DATA_FOLLOWING}（是否跟随）。
 * 坐标与距离按规格由客户端自己按实体位置算，不占同步位。
 *
 * <h2>七、碰撞箱（规格 13）</h2>
 * 本体 3（长）× 1.5（宽）× 1.5（高）。原版 AABB 不能旋转 ⇒ 覆写 {@link #makeBoundingBox()}：
 * 把 3×1.5×1.5 的长方体按当前 {@code yaw/pitch} 旋转后<b>投影</b>成轴对齐盒子，
 * 盒<b>中心</b>对齐实体坐标（所以 y 也从 -0.75 到 +0.75）。
 *
 * <h2>八、玩家驾驶（2026-11-24 新增）</h2>
 * <ul>
 *   <li><b>进入</b>：右键水滴实体 ⇒ {@code startRiding}（规格原话）。射线要能命中实体 ⇒
 *       {@link #isPickable()} 返回 true；入口是 {@link #interact(Player, InteractionHand)}
 *       （原版 {@code ServerboundInteractPacket} 的落点，不是 {@code PlayerInteractEvent}）。</li>
 *   <li><b>座位在水滴正中</b>：覆写 {@link #positionRider(Entity, Entity.MoveFunction)}
 *       （原版那个单参版是 {@code final}，但它会委托到这个两参版）——
 *       让<b>乘客眼睛</b>落在水滴中心，而不是"脚底在中心"。为什么没有用
 *       {@code getPassengerRidingPosition}/{@code getPassengerAttachmentPoint}：那是更高版本的 API，
 *       1.20.1 <b>没有</b>（{@code javap} 已核）。</li>
 *   <li><b>移动（服务端权威）</b>：方向 = 骑乘者 {@code getViewVector(1f)}（含 pitch）；
 *       <b>无惯性</b>（每 tick 直接用方向 × 速度，不读上一 tick 速度）；
 *       速度 {@code 0…1.0} 格/tick（= 最多 20 格/秒），W/S 各按
 *       {@link ShanhaiDropletPilotCore#ACCEL_PER_TICK}（0.05/tick ⇒ 1 秒到满速）积分，
 *       <b>进水滴 = 静止</b>（{@link #onRiderEntered}）。</li>
 *   <li><b>自动驾驶</b>：{@code /shanhai droplet autopilot on} ⇒ 忽略 W/S，
 *       有目标就按"自动攻击"同一套（{@link #stepToward} + {@link #tryHit}）冲过去，
 *       <b>没目标原地静止</b>；Shift 仍可下车。</li>
 *   <li><b>瞄准辅助</b>：{@code assist} 开着且夹角 &lt; 阈值 ⇒ 行驶方向改为目标方向
 *       （<b>只改方向，绝不动玩家视角</b>）。</li>
 *   <li><b>下车</b>：Shift；落点由 {@link #safeSpotFor} 阶梯搜索（不卡方块、脚下有实地）。</li>
 * </ul>
 */
public class ShanhaiDropletEntity extends Entity implements ShanhaiDropletPilotCore.PilotView {

    // ------------------------------------------------------------------ 规格常量

    /** 规格 8：最大生命值（水滴永远 1 点）。 */
    public static final float MAX_HEALTH = 1.0F;

    /**
     * 默认速度（格/tick）= 默认 40 格/秒 ÷ 20 = <b>2.0</b>。
     *
     * <p>🔴 2026-11-24 起速度<b>不是常量</b>：每 tick 从拥有者的设置里现读
     * （{@code /shanhai droplet speed 10~200}，默认 40 格/秒 = 老实现 20 格/秒的两倍）。
     * 这个常量只用于文档与自检基准，运动逻辑一律用 {@link ShanhaiDropletSettings#maxSpeedPerTick}。
     */
    public static final double DEFAULT_SPEED_PER_TICK =
            ShanhaiDropletSettings.DEFAULT_MAX_SPEED_PER_SEC / ShanhaiDropletSettings.TICKS_PER_SECOND;

    /** 规格 3：索敌半径（格），以<b>拥有者</b>为圆心。 */
    public static final double TARGET_RANGE = 128.0D;

    /** 规格 6：同一目标的命中冷却（tick）。 */
    public static final int HIT_COOLDOWN_TICKS = 5;

    /**
     * 规格 9：跟随点在拥有者视图坐标系里的「左」比例 —— 0.6（<b>比例，不是格</b>）。
     *
     * <p>用户要求"方向比例保持 3:4（左上），只缩放距离" ⇒ 单位化之后就是 0.6 / 0.8。
     * 实际偏移 = {@code 0.6 × followDistance}，而 {@code 0.6² + 0.8² = 1} ⇒
     * 与眼睛的距离恰好等于设置的那个数。
     */
    public static final double FOLLOW_RATIO_LEFT = ShanhaiDropletSettings.FOLLOW_RATIO_LEFT;

    /** 规格 9：跟随点的「上」比例 —— 0.8（见 {@link #FOLLOW_RATIO_LEFT}）。 */
    public static final double FOLLOW_RATIO_UP = ShanhaiDropletSettings.FOLLOW_RATIO_UP;

    /** 规格 13：本体长度（沿朝向，格）。 */
    public static final double BODY_LENGTH = 3.0D;

    /** 规格 13：本体宽度 = 高度（格）。 */
    public static final double BODY_GIRTH = 1.5D;

    private static final double HALF_LENGTH = BODY_LENGTH / 2.0D;
    private static final double HALF_GIRTH = BODY_GIRTH / 2.0D;

    /** 命中判定的额外膨胀（格）：让"擦着边"也算撞上，不要求几何上严格相交。 */
    private static final double HIT_PAD = 0.05D;

    /** 位移小于这个长度（格）就当作"没有方向"：保持上一 tick 的朝向。 */
    private static final double FACE_MIN_STEP = 0.1D;

    /** {@link #FACE_MIN_STEP} 的平方（比较用，省一次开方）。 */
    private static final double FACE_MIN_STEP_SQR = FACE_MIN_STEP * FACE_MIN_STEP;

    /**
     * 规格 9 的换维度方式：<b>不走传送门</b>，原地坐标搬过去。
     *
     * <p>默认的 {@code Entity#changeDimension(ServerLevel)} 用的是 {@code getPortalForcer()}：
     * 主世界 ↔ 下界会去找/造传送门，别的一律返回 {@code null}（{@code Entity#findDimensionEntryPoint}
     * 在"两边都不是下界/末地"时就直接返回 null）。水滴要的是"跟着拥有者过去"，
     * 所以给一个自定义 {@link ITeleporter}，只把当前位置/朝向原样带到目标维度。
     */
    private static final ITeleporter FOLLOW_TELEPORTER = new ITeleporter() {
        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destination,
                                        Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            return new PortalInfo(entity.position(), Vec3.ZERO, entity.getYRot(), entity.getXRot());
        }
    };

    // ------------------------------------------------------------------ NBT 键

    public static final String TAG_OWNER = "ShanhaiDropletOwner";
    public static final String TAG_TARGET = "ShanhaiDropletTarget";
    public static final String TAG_HIT_COOLDOWN = "ShanhaiDropletHitCooldown";
    public static final String TAG_HEALTH = "ShanhaiDropletHealth";

    // ------------------------------------------------------------------ 同步字段（规格 12）

    /** 是否<b>有目标</b>（false ⇒ 正在跟随拥有者）。 */
    public static final EntityDataAccessor<Boolean> DATA_HAS_TARGET =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.BOOLEAN);

    /** 目标显示名（无目标 ⇒ 空串）。 */
    public static final EntityDataAccessor<String> DATA_TARGET_NAME =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.STRING);

    /** 当前攻击模式 1/2/3（拥有者设置的一份拷贝，只读给客户端 HUD）。 */
    public static final EntityDataAccessor<Integer> DATA_MODE =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.INT);

    /** 爆炸地形破坏开关（拥有者设置的一份拷贝，只读给客户端 HUD）。 */
    public static final EntityDataAccessor<Boolean> DATA_EXPLOSION =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.BOOLEAN);

    /** 是否处于"跟随拥有者"（= 没有目标）。 */
    public static final EntityDataAccessor<Boolean> DATA_FOLLOWING =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.BOOLEAN);

    /** 驾驶：是否有玩家在水滴里（客户端据此决定画不画绿框/准星圈）。 */
    public static final EntityDataAccessor<Boolean> DATA_PILOTED =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.BOOLEAN);

    /** 驾驶：自动驾驶是否开启（服务端权威，客户端只显示）。 */
    public static final EntityDataAccessor<Boolean> DATA_AUTOPILOT =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.BOOLEAN);

    /** 驾驶：瞄准辅助是否开启。 */
    public static final EntityDataAccessor<Boolean> DATA_ASSIST =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * 驾驶：瞄准辅助判定角度（度，默认 5）。
     *
     * <p>用 {@link EntityDataSerializers#INT} 存<b>十分之一度</b>而不是 DOUBLE：
     * 1.20.1 的 {@code EntityDataSerializers} <b>没有</b> DOUBLE（{@code javap} 已核：
     * BYTE/INT/LONG/FLOAT/…/BOOLEAN/PARTICLE/ROTATIONS/BLOCK_POS/…）。
     * 设置的夹取本身保留 1 位小数 ⇒ 「度 × 10 取整」<b>无损</b>，客户端再除回来。
     */
    public static final EntityDataAccessor<Integer> DATA_ASSIST_ANGLE =
            SynchedEntityData.defineId(ShanhaiDropletEntity.class, EntityDataSerializers.INT);

    // ------------------------------------------------------------------ 运行时状态

    /** 拥有者 UUID（规格 1 放置时写入；存读档保留）。 */
    private UUID ownerUuid;

    /** 当前锁定的目标（服务端字段；不存盘，存的是 {@link #targetUuid}）。 */
    private LivingEntity target;

    /** 目标 UUID（读档恢复用）。 */
    private UUID targetUuid;

    /** 命中冷却剩余 tick。 */
    private int hitCooldown;

    /** 上一 tick 的运动方向（目标点与当前位置重合时保持朝向用）。 */
    private Vec3 lastStep = Vec3.ZERO;

    /** 规格 8 的血量（永远 1；写大了会被 {@link #setHealth} 夹回 1）。 */
    private float health = MAX_HEALTH;

    /** 上一次同步给客户端的"目标名"（避免每 tick 都拼字符串 / 每 tick 都置脏）。 */
    private String lastSyncedTargetName = "";

    // ------------------------------------------------------------------ 驾驶态（不落盘：进水滴即静止）

    /** 手动驾驶的当前速度（格/tick，恒在 0…{@link ShanhaiDropletPilotCore#MAX_SPEED_PER_TICK}）。 */
    private double pilotSpeed;

    /** 瞄准辅助这一 tick 是否锁定（只用于日志/读数，不影响移动：移动直接看 {@link #pilotAssistLocked} 那次判定）。 */
    private boolean pilotAssistLocked;

    /** 上一次日志里的"锁定"状态（只在变化时打一条，避免每 tick 刷屏）。 */
    private boolean lastLoggedAssistLocked;

    // ------------------------------------------------------------------ 构造

    /** 注册用的构造器（{@code EntityType.EntityFactory}）。 */
    public ShanhaiDropletEntity(EntityType<? extends ShanhaiDropletEntity> type, Level level) {
        super(type, level);
        this.initState();
    }

    /** 放置用的构造器（右键方块时由物品调用）。 */
    public ShanhaiDropletEntity(Level level, Player owner) {
        super(ShanhaiEntities.DROPLET.get(), level);
        this.ownerUuid = owner.getUUID();
        this.initState();
    }

    private void initState() {
        this.noPhysics = true;          // 规格 7：穿地形（爆炸开时地形由本类自己扫）
        this.setNoGravity(true);        // 规格 4：无重力
        this.setInvulnerable(true);     // 规格 8：不受任何伤害
        this.setHealth(MAX_HEALTH);     // 规格 8：setHealth(1)
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_HAS_TARGET, Boolean.FALSE);
        this.entityData.define(DATA_TARGET_NAME, "");
        this.entityData.define(DATA_MODE, Integer.valueOf(ShanhaiDropletSettings.DEFAULT_MODE));
        this.entityData.define(DATA_EXPLOSION, Boolean.valueOf(ShanhaiDropletSettings.DEFAULT_EXPLOSION));
        this.entityData.define(DATA_FOLLOWING, Boolean.TRUE);
        // 驾驶：新字段一律<b>追加在末尾</b>，既有 5 个 accessor 的编号顺序不变（老存档/老客户端不受影响）
        this.entityData.define(DATA_PILOTED, Boolean.FALSE);
        this.entityData.define(DATA_AUTOPILOT, Boolean.valueOf(ShanhaiDropletSettings.DEFAULT_AUTOPILOT));
        this.entityData.define(DATA_ASSIST, Boolean.valueOf(ShanhaiDropletSettings.DEFAULT_ASSIST));
        this.entityData.define(DATA_ASSIST_ANGLE,
                Integer.valueOf(ShanhaiDropletEntity.angleTenths(ShanhaiDropletSettings.DEFAULT_ASSIST_ANGLE)));
    }

    // ------------------------------------------------------------------ 规格 8：自己的血量 / 免疫

    /** 规格 8：{@code getMaxHealth() = 1}。 */
    public float getMaxHealth() {
        return MAX_HEALTH;
    }

    /** 规格 8：血量（永远 1；夹在 [0, {@link #MAX_HEALTH}]）。 */
    public float getHealth() {
        return this.health;
    }

    /** 规格 8：写血量（夹在 [0, {@link #MAX_HEALTH}]）。 */
    public void setHealth(float value) {
        this.health = Mth.clamp(value, 0.0F, MAX_HEALTH);
    }

    /** 规格 8：<b>自己覆写 {@code hurt()} 返回 false</b> —— 任何伤害都吃不进去。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    /** 规格 8：{@code isInvulnerableTo} 恒 true。 */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /** 不被自己的爆炸推着走（爆炸只负责把地形挖开）。 */
    @Override
    public boolean ignoreExplosion() {
        return true;
    }

    /** 不被活塞/水流推动。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    // ------------------------------------------------------------------ 驾驶：进入 / 座位 / 离开

    /**
     * 让实体的射线能命中它（默认 {@code false}，只有 {@code LivingEntity} 才 true）。
     *
     * <p>规格要"右键水滴进入"，而原版的右键实体是 {@code ServerboundInteractPacket} →
     * {@code Player#interactOn} → {@link #interact}，前提是<b>射线先能命中</b>
     * （{@code ProjectileUtil} 里那道 {@code isPickable()} 判定）。不覆写这个方法，
     * 右键会直接穿过去、什么都进不去。
     *
     * <p>副作用（已知、可接受）：水滴会抢占它所在位置的右键/攻击射线。
     * 水滴本来就是无敌的（{@code hurt} 恒 false），误点没有副作用；这条写进交付报告的已知限制。
     */
    @Override
    public boolean isPickable() {
        return true;
    }

    /**
     * 右键水滴 ⇒ 进入（骑乘）。只允许<b>拥有者</b>进入。
     *
     * <p>为什么限拥有者：驾驶相关的三个设置（自动驾驶/瞄准辅助/角度）都存在
     * <b>拥有者</b>的 {@code PersistentData} 里（与模式/爆炸开关同源）；别人上来会读到别人的设置，
     * 语义就乱了。非拥有者右键给一条明确提示，不静默失败。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            // 客户端只回手势；真正上载具由服务端判定后同步（乘客列表是同步字段）
            return this.isOwnedBy(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!this.isOwnedBy(serverPlayer)) {
            serverPlayer.sendSystemMessage(ShanhaiDropletPilotText.notOwner());
            return InteractionResult.FAIL;
        }
        if (serverPlayer.isPassenger()) {
            return InteractionResult.PASS;         // 已经在别的东西上（船/马）：不抢
        }
        if (!serverPlayer.startRiding(this, true)) {
            ShanhaiMod.LOGGER.warn("{} pilot_enter_failed owner={} droplet={}",
                    ShanhaiDropletPilotNetwork.PREFIX, serverPlayer.getUUID(), this.getUUID());
            return InteractionResult.FAIL;
        }
        this.onRiderEntered(serverPlayer);
        serverPlayer.sendSystemMessage(ShanhaiDropletPilotText.enterHint());
        return InteractionResult.CONSUME;
    }

    /** 只允许一个乘客（驾驶是单人座；也让 {@code getFirstPassenger()} 的含义唯一）。 */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    /** 允许玩家骑上来（默认 {@code Entity#canRide} 返回 false）。 */
    @Override
    protected boolean canRide(Entity vehicle) {
        return true;
    }

    /**
     * 座位 = <b>水滴几何中心</b>（让眼睛落在中心，而不是脚底落在中心）。
     *
     * <p>原版那个单参 {@code positionRider(Entity)} 是 {@code final}，但它的字节码是
     * {@code invokedynamic Entity::setPos} 之后 {@code invokevirtual positionRider(Entity, MoveFunction)}
     * ⇒ 覆写这个两参版就等于覆写了座位算法（{@code javap -c} 已核）。
     *
     * <p>用 {@code passenger.getEyeHeight()} 现算而不是常量 1.62：
     * 骑乘时按 Shift 会进入潜行姿态（眼高 1.62 → 1.27），现算才能保证<b>任何姿态下相机都在水滴中心</b>。
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        if (!this.hasPassenger(passenger)) {
            return;
        }
        if (this.level().isClientSide) {
            // 🔴 "坐在里面一卡一卡"的机械性护栏：相机插值用的是<b>乘客自己</b>的 xo/yo/zo。
            //    这里在把乘客挪到新座位<b>之前</b>，先把插值起点钉成"它现在的位置"（= 上一 tick 的座位），
            //    语义等价于原版 setOldPosAndRot()。原版若已刷过就是同值（无副作用）；
            //    若没刷过，这一行就把"每 tick 硬跳 2 格"变成平滑插值。
            passenger.xo = passenger.getX();
            passenger.yo = passenger.getY();
            passenger.zo = passenger.getZ();
        }
        callback.accept(passenger, this.getX(), this.getY() - (double) passenger.getEyeHeight(), this.getZ());
    }

    /** 位置偏移由 {@link #positionRider} 直接算，这里给 0 免得多一层语义。 */
    @Override
    public double getPassengersRidingOffset() {
        return 0.0D;
    }

    /**
     * 任何下车路径（Shift / 原版 / {@code /eject} / 收回）都会经过这里 ⇒
     * <b>安全落点只需写一处</b>。先让原版把乘客摘掉，再把玩家挪到阶梯搜索出来的位置。
     */
    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (this.level().isClientSide || !(passenger instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final Vec3 safe = this.safeSpotFor(serverPlayer);
        serverPlayer.teleportTo((ServerLevel) this.level(), safe.x, safe.y, safe.z,
                serverPlayer.getYRot(), serverPlayer.getXRot());
        this.onRiderLeft(serverPlayer, "remove_passenger", safe);
    }

    /** 原版有些下车路径会"问落点"（船/矿车用得多）⇒ 与 {@link #removePassenger} 同一处算法。 */
    @Override
    public Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity passenger) {
        return this.safeSpotFor(passenger);
    }

    /**
     * 安全落点：以水滴为圆心做<b>阶梯搜索</b>，先近后远、先正前再环向、先平层再上下。
     *
     * <p>接受条件两条（都要满足）：
     * ① {@code level.noCollision(rider, 候选盒)} —— 玩家不会被卡在方块里；
     * ② 候选点下方 0.05 格那个方块 {@code blocksMotion()} —— 脚下有实地，不会悬空掉下去。
     * <p>全部失败才退回水滴原位（并打 warn）：退回原位至少不会把人塞进墙里 ——
     * 水滴是 {@code noPhysics} 的，玩家站在原位会被原版自己的碰撞推开。
     */
    private Vec3 safeSpotFor(net.minecraft.world.entity.LivingEntity rider) {
        final AABB box = rider.getBoundingBox();
        final double halfX = box.getXsize() * 0.5D;
        final double halfZ = box.getZsize() * 0.5D;
        final double height = box.getYsize();
        final float yaw = rider.getYRot();
        final Vec3 origin = this.position();
        final double[] radii = { 1.2D, 1.8D, 2.5D, 3.5D, 5.0D };
        final double[] offsets = { 0.0D, 1.0D, -1.0D, 2.0D, -2.0D };
        for (final double dy : offsets) {
            for (final double radius : radii) {
                for (int i = 0; i < 8; i++) {
                    final double angle = Math.toRadians((double) yaw + (double) (i * 45));
                    // 原版朝向：yaw=0 朝 +Z ⇒ 前方 = (-sin, +cos)
                    final double x = origin.x - Math.sin(angle) * radius;
                    final double z = origin.z + Math.cos(angle) * radius;
                    final double y = origin.y + dy;
                    final AABB candidate = new AABB(
                            x - halfX, y, z - halfZ,
                            x + halfX, y + height, z + halfZ);
                    if (!this.level().noCollision(rider, candidate)) {
                        continue;
                    }
                    final BlockPos below = BlockPos.containing(x, y - 0.05D, z);
                    if (!this.level().getBlockState(below).blocksMotion()) {
                        continue;
                    }
                    ShanhaiMod.LOGGER.info("{} safe_spot chosen radius={} dy={} dir={} pos=({},{},{})",
                            ShanhaiDropletPilotNetwork.PREFIX, Double.valueOf(radius), Double.valueOf(dy),
                            Integer.valueOf(i * 45), Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
                    return new Vec3(x, y, z);
                }
            }
        }
        ShanhaiMod.LOGGER.warn("{} safe_spot fallback=origin pos={} 阶梯搜索全失败（水滴可能整个埋在地里）",
                ShanhaiDropletPilotNetwork.PREFIX, origin);
        return origin;
    }

    // ------------------------------------------------------------------ 虚空：不许被原版丢掉

    /** 上一次打"虚空夹住"日志的游戏时间（限流 5 秒一条，避免每 tick 刷屏）。 */
    private long lastVoidLogTick = Long.MIN_VALUE;

    /**
     * 🔴 虚空修复的<b>根因与修法</b>：原版 {@code Entity#checkBelowWorld()} 在
     * {@code y < minBuildHeight - 64} 时会调 {@code onBelowWorld()}（默认实现是 {@code discard()}）
     * ⇒ 水滴掉到虚空深处就"消失/死"。这里<b>覆写整个方法</b>：不调 super、改为把 Y 夹到
     * {@code minBuildHeight - }{@link ShanhaiDropletPilotCore#VOID_MARGIN_BELOW_MIN_BUILD}。
     * 顺带把速度归零（否则它会在夹住的一瞬间继续朝下加速，看上去像"贴地抖动"）。
     */
    @Override
    public void checkBelowWorld() {
        final double floor = ShanhaiDropletPilotCore.voidFloorFor(this.level().getMinBuildHeight());
        final double y = this.getY();
        if (y >= floor) {
            return;
        }
        this.setPos(this.getX(), floor, this.getZ());
        this.setDeltaMovement(Vec3.ZERO);
        final long now = this.level().getGameTime();
        if (this.lastVoidLogTick == Long.MIN_VALUE || now - this.lastVoidLogTick >= 100L) {
            this.lastVoidLogTick = now;
            ShanhaiMod.LOGGER.info("{} void_clamp from={} to={} floor=min_build-{}",
                    ShanhaiDropletPilotNetwork.PREFIX, Double.valueOf(y), Double.valueOf(floor),
                    Integer.valueOf((int) ShanhaiDropletPilotCore.VOID_MARGIN_BELOW_MIN_BUILD));
        }
    }

    /** 兜底第二道：即使哪条路径绕过了 {@link #checkBelowWorld()}，也不许把水滴丢掉（只夹回地板）。 */
    @Override
    protected void onBelowWorld() {
        final double floor = ShanhaiDropletPilotCore.voidFloorFor(this.level().getMinBuildHeight());
        if (this.getY() < floor) {
            this.setPos(this.getX(), floor, this.getZ());
        }
        ShanhaiMod.LOGGER.warn("{} void_onBelowWorld_intercepted droplet={} y={} floor={}",
                ShanhaiDropletPilotNetwork.PREFIX, this.getUUID(), Double.valueOf(this.getY()),
                Double.valueOf(floor));
    }

    // ------------------------------------------------------------------ 网络位移计数（⑤ 卡顿取证用）

    /** 客户端被"硬吸附"（{@code teleportTo}）的次数 —— 判据：应当接近 0。 */
    private int clientTeleportCount;

    /** 客户端收到的位置更新（{@code lerpTo}）次数 —— 判据：约每秒 20 次。 */
    private int clientLerpCount;

    @Override
    public void teleportTo(double x, double y, double z) {
        super.teleportTo(x, y, z);
        if (this.level().isClientSide) {
            this.clientTeleportCount++;
            if (this.clientTeleportCount <= 5 || this.clientTeleportCount % 100 == 0) {
                ShanhaiMod.LOGGER.info("{} client_teleport_snap n={} to=({},{},{})",
                        ShanhaiDropletPilotNetwork.PREFIX, Integer.valueOf(this.clientTeleportCount),
                        Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
            }
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        super.lerpTo(x, y, z, yRot, xRot, steps, teleport);
        if (this.level().isClientSide) {
            this.clientLerpCount++;
        }
    }

    /** 诊断：客户端被硬吸附的次数（{@code teleportTo}）。 */
    public int clientTeleportCount() {
        return this.clientTeleportCount;
    }

    /** 诊断：客户端位置更新次数（{@code lerpTo}）。 */
    public int clientLerpCount() {
        return this.clientLerpCount;
    }

    // ------------------------------------------------------------------ 规格 13：旋转的长方体 → 轴对齐 AABB

    /**
     * 规格 13：3（长）× 1.5（宽）× 1.5（高）的长方体按当前 {@code yaw/pitch} 投影成轴对齐包围盒，中心对齐。
     *
     * <h4>推导（局部轴 → 世界轴）</h4>
     * 原版朝向约定：{@code yaw = 0} 时前方是 <b>+Z</b>（{@code Vec3.directionFromRotation} 实测），
     * 于是本体坐标系的三根基向量是：
     * <pre>
     *   forward(+Z) = (-sin(yaw)·cos(pitch), -sin(pitch),  cos(yaw)·cos(pitch))
     *   right  (+X) = ( cos(yaw),               0,           sin(yaw))
     *   up     (+Y) = forward × right = (-sin(pitch)·sin(yaw), cos(pitch), sin(pitch)·cos(yaw))
     * </pre>
     * 长方体的 8 个顶点 = {@code ±0.75·right ±0.75·up ±1.5·forward}；轴对齐盒的<b>半宽</b>就是
     * 各顶点在该轴上的最大绝对值 = {@code 0.75·|right_i| + 0.75·|up_i| + 1.5·|forward_i|}
     * （三根轴正交且长方体的 8 个符号组合都取到 ⇒ 逐轴取绝对值和即可，不必真转 8 个点）。
     * <p>中心对齐 ⇒ 盒 = {@code [pos - h, pos + h]}（y 也一样，所以 y 从 -0.75 到 +0.75）。
     */
    @Override
    protected AABB makeBoundingBox() {
        final double yawRad = this.getYRot() * (double) Mth.DEG_TO_RAD;
        final double pitchRad = this.getXRot() * (double) Mth.DEG_TO_RAD;
        final double sinYaw = Mth.sin((float) yawRad);
        final double cosYaw = Mth.cos((float) yawRad);
        final double sinPitch = Mth.sin((float) pitchRad);
        final double cosPitch = Mth.cos((float) pitchRad);

        // forward（局部 +Z）
        final double fx = -sinYaw * cosPitch;
        final double fy = -sinPitch;
        final double fz = cosYaw * cosPitch;
        // right（局部 +X，水平）
        final double rx = cosYaw;
        final double ry = 0.0D;
        final double rz = sinYaw;
        // up = forward × right（局部 +Y）
        final double ux = fy * rz - fz * ry;
        final double uy = fz * rx - fx * rz;
        final double uz = fx * ry - fy * rx;

        final double hx = HALF_GIRTH * Math.abs(rx) + HALF_GIRTH * Math.abs(ux) + HALF_LENGTH * Math.abs(fx);
        final double hy = HALF_GIRTH * Math.abs(ry) + HALF_GIRTH * Math.abs(uy) + HALF_LENGTH * Math.abs(fy);
        final double hz = HALF_GIRTH * Math.abs(rz) + HALF_GIRTH * Math.abs(uz) + HALF_LENGTH * Math.abs(fz);
        return new AABB(this.getX() - hx, this.getY() - hy, this.getZ() - hz,
                this.getX() + hx, this.getY() + hy, this.getZ() + hz);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        // 本 tick 之前的位姿：渲染插值的 "o / Old" 值（规格 4 的「写 yaw/pitch 及其 o」）。
        // 原版 Level#tickNonPassenger 在 tick 之前已经 setOldPosAndRot() 过一次，这里再显式钉一次，
        // 保证"tick() 被任何路径调用"时 o 值都等于本 tick 的起点。
        final double prevX = this.getX();
        final double prevY = this.getY();
        final double prevZ = this.getZ();
        final float prevYaw = this.getYRot();
        final float prevPitch = this.getXRot();

        super.tick();

        if (!this.level().isClientSide) {
            this.serverTick();
        }

        // 两种模式都不让原版物理插手：地形要么直接穿（爆炸关），要么由本类自己扫开（爆炸开）
        this.noPhysics = true;

        this.xo = prevX;
        this.yo = prevY;
        this.zo = prevZ;
        this.yRotO = prevYaw;
        this.xRotO = prevPitch;
    }

    /** 服务端每 tick 的全部逻辑（客户端只负责按同步包插值显示）。 */
    private void serverTick() {
        if (this.hitCooldown > 0) {
            --this.hitCooldown;
        }
        final ServerPlayer owner = this.resolveOwner();
        if (owner == null) {
            // 拥有者不在线：设置没有来源 ⇒ 原地保持，目标清掉（不猜、不乱飞、不清方块）
            this.clearTarget();
            this.entityData.set(DATA_HAS_TARGET, Boolean.FALSE);
            this.entityData.set(DATA_FOLLOWING, Boolean.TRUE);
            return;
        }
        final int mode = ShanhaiDropletSettings.mode(owner);
        final boolean explosion = ShanhaiDropletSettings.explosion(owner);
        final double speedPerTick = ShanhaiDropletSettings.maxSpeedPerTick(owner);
        final double followDistance = ShanhaiDropletSettings.followDistance(owner);

        // ⓪ 规格 9：拥有者换维度 ⇒ 水滴跟着换过去（本 tick 不再位移）
        if (owner.level() != this.level()) {
            this.followOwnerInto(owner);
            this.pushSyncedState(mode, explosion);
            return;
        }

        // ⓪′ 驾驶分支（2026-11-24）：只要有乘客，本 tick 的移动/命中全部由驾驶逻辑接管。
        //     判据用 getFirstPassenger() 而不是 isVehicle()：水滴只允许一个乘客（canAddPassenger 已限 1），
        //     所以"第一个乘客"就是"唯一乘客"。
        if (this.getFirstPassenger() instanceof ServerPlayer rider) {
            this.tickPiloted(owner, rider, mode, explosion, speedPerTick);
            return;
        }
        this.entityData.set(DATA_PILOTED, Boolean.FALSE);

        // ① 规格 3 / 5：索敌 / 目标保持 —— 2026-11-24 起是"整层一次分配"（同一目标不重复锁）
        ShanhaiDropletTargets.register(this);
        if (this.level() instanceof ServerLevel serverLevel) {
            ShanhaiDropletTargets.allocate(serverLevel);
        }

        final LivingEntity current = this.target;
        final Vec3 goal = current != null
                ? current.getBoundingBox().getCenter()
                : this.followPoint(owner, followDistance);

        // ② 规格 4：每 tick 现算方向（无惯性、无加速度）＋ 这一 tick 真正要走的那一段
        //    🔴 2026-11-24：公式抽到 stepToward(...)，【自动驾驶与自动攻击共用同一份】（不抄第二份）
        final Vec3 from = this.position();
        // 只有"跟随且已经到点"这一种情况允许位移 < 一格；追目标时永远走满（撞过去 → 掉头 → 再撞）
        final Vec3 step = this.stepToward(goal, speedPerTick, current == null);

        // ③ 规格 4：朝向 = 运动方向（先写朝向，再 setPos ⇒ 本 tick 的碰撞箱按新朝向算）。
        //    位移小到没有方向（悬停时拥有者只转头）就保持上一 tick 的朝向，避免"抖头"。
        if (step.lengthSqr() > FACE_MIN_STEP_SQR) {
            this.faceTowards(step);
        }
        final Vec3 to = from.add(step);

        // ④ 规格 7：爆炸开 ⇒ 先扫这一 tick 的<b>整段</b>线段，撞到方块就在进入点炸开（洞开了再照常飞）
        //    🔴 高速（最多 10 格/tick）下这里必须扫整段：只看终点会整块穿过去
        if (explosion) {
            final Vec3 terrainHit = ShanhaiDropletExplosion.firstTerrainAlong(this.level(), from, to);
            if (terrainHit != null) {
                ShanhaiDropletExplosion.detonate(this.level(), this, terrainHit);
            }
        }

        // ⑤ 规格 5 / 6：命中判定走这一 tick 的<b>整段</b>线段（swept），不是"到没到终点"
        if (current != null) {
            this.tryHit(current, from, to);
        }

        // ⑥ 规格 4：位移。位移长度 = 速度/20（跟随到点那一 tick 更小），绝不瞬移
        this.setPos(to.x, to.y, to.z);
        this.setDeltaMovement(step);
        if (step.lengthSqr() > FACE_MIN_STEP_SQR) {
            this.lastStep = step.normalize();
        }

        // ⑦ 规格 12：把 HUD 要的字段同步出去
        this.pushSyncedState(mode, explosion);
    }

    // ------------------------------------------------------------------ 拖动位移的共用公式

    /**
     * 朝一个目标点走一步（<b>自动攻击与自动驾驶共用这一份公式</b>）。
     *
     * <p>与 2026-11-24 之前的写法逐字等价（只是从 {@code serverTick} 里抽出来）：
     * <ol>
     *   <li>{@code allowArriveHold} 且距离 ≤ 步长 ⇒ 这一步就走到点上（不越过）—— 只给"跟随拥有者"用；</li>
     *   <li>否则只要有方向就<b>走满</b> {@code speedPerTick}（追目标时故意越过、掉头、再撞）；</li>
     *   <li>方向退化（与目标点重合）⇒ 沿用上一 tick 的方向，避免原地抖头。</li>
     * </ol>
     */
    private Vec3 stepToward(Vec3 goal, double speedPerTick, boolean allowArriveHold) {
        final Vec3 delta = goal.subtract(this.position());
        final double distance = delta.length();
        if (allowArriveHold && distance <= speedPerTick) {
            return delta;
        }
        if (distance > 1.0E-6D) {
            return delta.scale(speedPerTick / distance);
        }
        if (this.lastStep.lengthSqr() > 1.0E-9D) {
            return this.lastStep.scale(speedPerTick);
        }
        return Vec3.ZERO;
    }

    // ------------------------------------------------------------------ 驾驶（服务端权威）

    /**
     * 驾驶下的每 tick 逻辑。调用前 {@code serverTick} 已经确认"有乘客"。
     *
     * <p>顺序：下车判定 → 目标分配（与自动攻击同一套）→ 同步驾驶态 → 自动驾驶 / 手动 →
     * 爆炸扫地形 → 扫掠命中 → 位移 → 同步 HUD 字段。
     */
    private void tickPiloted(ServerPlayer owner, ServerPlayer rider, int mode, boolean explosion,
                             double speedPerTick) {
        final long now = this.level().getGameTime();
        this.entityData.set(DATA_PILOTED, Boolean.TRUE);

        final ShanhaiDropletPilotNetwork.IntentStore.Intent intent =
                ShanhaiDropletPilotNetwork.IntentStore.poll(rider.getUUID(), now);

        // ① 下车：Shift（包里那一位 与 原版 sneak 同步，两条任一成立就下）
        if (intent.shift || rider.isShiftKeyDown()) {
            this.exitPilot(rider, "shift");
            return;
        }
        // ② 拥有者/乘客换维度（乘客理论上跟着载具走）⇒ 强制下车，避免跨维度驾驶的状态怪相
        if (owner.level() != this.level() || rider.level() != this.level()) {
            this.exitPilot(rider, "dimension");
            return;
        }

        // ③ 目标：与自动攻击<b>同一套</b>整层分配（不是第二份索敌）
        ShanhaiDropletTargets.register(this);
        if (this.level() instanceof ServerLevel serverLevel) {
            ShanhaiDropletTargets.allocate(serverLevel);
        }
        LivingEntity current = this.target;

        final boolean autopilot = ShanhaiDropletSettings.autopilot(owner);
        final boolean assist = ShanhaiDropletSettings.assist(owner);
        final double assistAngle = ShanhaiDropletSettings.assistAngle(owner);
        this.pushPilotSyncedState(autopilot, assist, assistAngle);

        // ④ 自动驾驶：忽略 W/S；有目标按自动攻击冲过去并重复攻击，没目标原地静止（规格原文）
        if (autopilot) {
            this.pilotSpeed = ShanhaiDropletPilotCore.MIN_SPEED_PER_TICK;   // 切回手动时从静止起步
            this.pilotAssistLocked = false;
            if (current == null) {
                this.setDeltaMovement(Vec3.ZERO);
                this.pushSyncedState(mode, explosion);
                return;
            }
            this.autoAttackStep(current, explosion, speedPerTick);
            this.pushSyncedState(mode, explosion);
            return;
        }

        // ⑤ 方向<b>先</b>定（规格顺序：视角方向 → 候选方向 → 夹角 → 取<b>最小角</b>那只 → 若 < 阈值则用它的方向）
        //    🔴 2026-11-25 修正（实机日志定死）：之前只拿"分配器给的那一只"跟准星比，而分配器给的是
        //    「离拥有者最近的那只」；玩家瞄的往往是<b>另一只</b> ⇒ 角度动辄 130°+、reason=angle_gt
        //    （日志统计：reason=no_target 65 / angle_gt 54 / applied 8，angle=142°/137°/129°）。
        //    现在遍历<b>这一 tick 的全部候选</b>（复用 candidatesByDistance，不另写索敌），取夹角最小的那只。
        final LivingEntity allocatorTarget = current;
        final Vec3 view = rider.getViewVector(1.0F).normalize();
        //  🔴 2026-11-25 选 B：把「不攻击」与「辅助瞄准」解耦 —— 模式 3 也拿候选（用"攻击所有生物"口径），
        //     否则"不攻击"必然 reason=no_target；出手仍由 AssistPick.attackAllowed 挡住（见下面的 tryHit）。
        final int assistMode = AssistPick.assistCandidateMode(mode,
                ShanhaiDropletSettings.MODE_NONE, ShanhaiDropletSettings.MODE_ALL);
        final List<LivingEntity> assistCandidates = assist
                ? this.candidatesByDistance(owner, assistMode)
                : java.util.Collections.emptyList();
        final double[] assistAngles = new double[assistCandidates.size()];
        for (int i = 0; i < assistCandidates.size(); i++) {
            final Vec3 toCandidate = assistCandidates.get(i).getBoundingBox().getCenter()
                    .subtract(this.position());
            assistAngles[i] = ShanhaiDropletPilotCore.angleDeg(view.x, view.y, view.z,
                    toCandidate.x, toCandidate.y, toCandidate.z);
        }
        final int pickedIndex = pickBestAngleIndex(assistAngles, assistAngle);
        Vec3 direction = view;
        LivingEntity picked = null;
        if (pickedIndex >= 0) {
            final LivingEntity candidate = assistCandidates.get(pickedIndex);
            final Vec3 toPicked = candidate.getBoundingBox().getCenter().subtract(this.position());
            if (toPicked.lengthSqr() > 1.0E-12D) {
                direction = toPicked.normalize();
                picked = candidate;
                // 🔴 朝谁飞就打谁：不改 target 的话，水滴朝它飞、tryHit 却还在打分配器原来那只
                this.assignTarget(candidate);
                current = candidate;
            }
        }
        this.pilotAssistLocked = picked != null;
        this.logAssistDecision(assist, this.pilotAssistLocked, pickedIndex, assistAngle,
                assistAngles, allocatorTarget, picked, mode);

        // ⑥ <b>再</b>算速度：上限来自设置（{@code /shanhai droplet speed}，默认 40 格/秒 = 2.0 格/tick），
        //    加速率 = 上限 / 20 ⇒ 任何档位都是 1 秒到满速
        this.pilotSpeed = ShanhaiDropletPilotCore.integrateSpeed(
                this.pilotSpeed, intent.forward, intent.backward, speedPerTick);

        // 速度 0 时也把朝向钉在方向上 ⇒ 水滴鼻子跟准星（"视角朝向即运动方向"在静止时也成立）
        this.faceTowards(direction);

        if (this.pilotSpeed <= 1.0E-9D) {
            this.setDeltaMovement(Vec3.ZERO);
            this.pushSyncedState(mode, explosion);
            return;
        }

        // ⑦ 位移 = 方向 × 速度（无惯性）；爆炸扫地形与扫掠命中都走整段 from→to
        final Vec3 from = this.position();
        final Vec3 step = direction.scale(this.pilotSpeed);
        final Vec3 to = from.add(step);
        if (explosion) {
            final Vec3 terrainHit = ShanhaiDropletExplosion.firstTerrainAlong(this.level(), from, to);
            if (terrainHit != null) {
                ShanhaiDropletExplosion.detonate(this.level(), this, terrainHit);
            }
        }
        // 🔴 选 B 的第二半：模式"不攻击"时<b>不许出手</b>（辅助可以锁、可以改方向，但 tryHit 不调）
        if (current != null && AssistPick.attackAllowed(mode, ShanhaiDropletSettings.MODE_NONE)) {
            this.tryHit(current, from, to);
        }
        this.setPos(to.x, to.y, to.z);
        this.setDeltaMovement(step);
        this.lastStep = direction;
        this.logServerMove(from, step);
        this.pushSyncedState(mode, explosion);
    }

    // ------------------------------------------------------------------ 驾驶诊断（③ 辅助 / ⑤ 卡顿）

    /** 上一次"辅助判定"日志的游戏时间（限流 2 秒 = 40 tick）。 */
    private long lastAssistLogTick = Long.MIN_VALUE;

    /** 上一次"服务端位移"日志的游戏时间（限流 2 秒）。 */
    private long lastMoveLogTick = Long.MIN_VALUE;

    /**
     * 辅助选择器（<b>纯函数、零状态</b>）：在候选夹角数组里挑「夹角最小且<b>严格小于</b>阈值」的下标。
     *
     * <p>为什么"取最小角"而不是"取分配器给的那只"：分配器给的是<b>离拥有者最近</b>的目标（多水滴不重复锁
     * 的那条规则），跟"玩家准星指着哪只"是两件事。实机日志里 54 次 `reason=angle_gt`、角度 142° ——
     * 就是"它比的那只在身后"。
     *
     * <h4>为什么放在嵌套类里</h4>
     * 直接调外层实体的静态方法会触发 {@code Entity.<clinit>}（→ {@code EntityDataSerializers} →
     * registry bootstrap）⇒ <b>不启动游戏就跑不了</b>。放进静态嵌套类后，加载本类<b>不会</b>初始化外层
     * （JLS 12.4.1），于是这一小段纯逻辑可以在<b>离线探针</b>里直接跑到（含负对照）。
     */
    public static final class AssistPick {

        /**
         * 「不攻击」与「辅助瞄准」解耦（用户选 B）：即使 {@code mode == MODE_NONE}，辅助也要拿到候选
         * ⇒ 候选来源换成"攻击所有生物"的口径；<b>是否出手</b>由 {@link #attackAllowed} 单独挡。
         *
         * @param mode 当前攻击模式
         * @param modeNone 模式"不攻击"的取值（传 {@code ShanhaiDropletSettings.MODE_NONE}）
         * @param modeAll 模式"攻击所有生物"的取值（传 {@code ShanhaiDropletSettings.MODE_ALL}）
         * @return 用来取候选的模式
         */
        public static int assistCandidateMode(int mode, int modeNone, int modeAll) {
            return mode == modeNone ? modeAll : mode;
        }

        /**
         * 命中闸门：{@code MODE_NONE}（不攻击）<b>永远不出手</b> —— 辅助可以锁、可以改方向，但不许打。
         *
         * <p>这就是"解耦"的另一半：候选放宽了，命中必须单独挡，否则"辅助选中就顺手打"。
         */
        public static boolean attackAllowed(int mode, int modeNone) {
            return mode != modeNone;
        }

        /**
         * @param anglesDeg 每个候选与准星方向的夹角（度）；NaN 视为不可选
         * @param thresholdDeg 阈值（度）
         * @return 选中下标；空数组 / 全 NaN / 最小角 ≥ 阈值 ⇒ {@code -1}
         */
        public static int bestIndex(double[] anglesDeg, double thresholdDeg) {
            if (anglesDeg == null || anglesDeg.length == 0) {
                return -1;
            }
            int best = -1;
            double bestAngle = Double.MAX_VALUE;
            for (int i = 0; i < anglesDeg.length; i++) {
                final double angle = anglesDeg[i];
                if (Double.isNaN(angle)) {
                    continue;
                }
                if (angle < bestAngle) {
                    bestAngle = angle;
                    best = i;
                }
            }
            if (best < 0) {
                return -1;
            }
            // 边界只认 Core 那一处（严格 <）：4.9° 命中、5.0° 不命中
            return ShanhaiDropletPilotCore.assistLocks(bestAngle, thresholdDeg) ? best : -1;
        }

        /** 候选夹角里的最小值（度）；空数组 / 全 NaN ⇒ {@code NaN}。日志用。 */
        public static double minAngle(double[] anglesDeg) {
            if (anglesDeg == null || anglesDeg.length == 0) {
                return Double.NaN;
            }
            double min = Double.MAX_VALUE;
            for (final double angle : anglesDeg) {
                if (!Double.isNaN(angle) && angle < min) {
                    min = angle;
                }
            }
            return min == Double.MAX_VALUE ? Double.NaN : min;
        }

        private AssistPick() {}
    }

    /** 调用点转发（语义名更贴近用法；真正可离线断言的是 {@link AssistPick#bestIndex}）。 */
    public static int pickBestAngleIndex(double[] anglesDeg, double thresholdDeg) {
        return AssistPick.bestIndex(anglesDeg, thresholdDeg);
    }

    /**
     * ③ 的取证行（2 秒一条）：一眼看出"为什么没锁 / 锁了谁"。
     *
     * <pre>[SHANHAI-DROPLET-PILOT] assist applied=0|1 angle=.. thr=.. cand=.. min_angle=.. target=.. picked=.. reason=..</pre>
     *
     * <p>{@code target} = 分配器这一 tick 给的那只（可能为空）；{@code picked} = 辅助按"最小角"选中的那只；
     * {@code angle} = 选中那只的角度（没选中时给最小角，空候选给 {@code n/a}）。
     * <p>{@code reason} 取值：{@code off}（辅助关）/ {@code no_target}（没候选）/
     * {@code angle_gt}（全部超阈值）/ {@code mode_none}（<b>锁定生效但"不攻击"模式不出手</b>）/
     * {@code applied}（锁定且会出手）。
     */
    private void logAssistDecision(boolean assist, boolean locked, int pickedIndex, double threshold,
                                   double[] angles, LivingEntity allocatorTarget, LivingEntity picked,
                                   int mode) {
        final long now = this.level().getGameTime();
        if (this.lastAssistLogTick != Long.MIN_VALUE && now - this.lastAssistLogTick < 40L) {
            return;
        }
        this.lastAssistLogTick = now;
        final double min = AssistPick.minAngle(angles);
        final String reason;
        if (!assist) {
            reason = "off";
        } else if (angles == null || angles.length == 0) {
            reason = "no_target";
        } else if (picked == null || pickedIndex < 0) {
            reason = "angle_gt";
        } else if (!AssistPick.attackAllowed(mode, ShanhaiDropletSettings.MODE_NONE)) {
            // 选 B 之后新增的一档：锁定了、方向也修正了，但当前是"不攻击"⇒ 不出手
            reason = "mode_none";
        } else {
            reason = "applied";
        }
        final String angleText = pickedIndex >= 0 && angles != null && pickedIndex < angles.length
                ? oneDecimal(angles[pickedIndex])
                : (Double.isNaN(min) ? "n/a" : oneDecimal(min));
        final String targetName = allocatorTarget == null
                ? "none" : ShanhaiDropletPilotCore.trimName(allocatorTarget.getDisplayName().getString(), 24);
        final String pickedName = picked == null
                ? "none" : ShanhaiDropletPilotCore.trimName(picked.getDisplayName().getString(), 24);
        ShanhaiMod.LOGGER.info("{} assist applied={} angle={} thr={} cand={} min_angle={} target={} "
                        + "picked={} reason={}",
                ShanhaiDropletPilotNetwork.PREFIX, Integer.valueOf(locked ? 1 : 0), angleText,
                oneDecimal(threshold), Integer.valueOf(angles == null ? 0 : angles.length),
                Double.isNaN(min) ? "n/a" : oneDecimal(min), targetName, pickedName, reason);
    }

    /**
     * ⑤ 的取证行（2 秒一条）：这一 tick 真实走了多远 + 渲染插值起点是否等于位移前位置。
     *
     * <p>{@code xo_ok=false} 意味着 {@code xo/yo/zo} 与"位移前位置"不一致 ⇒ 客户端插值会跳。
     */
    private void logServerMove(Vec3 from, Vec3 step) {
        final long now = this.level().getGameTime();
        if (this.lastMoveLogTick != Long.MIN_VALUE && now - this.lastMoveLogTick < 40L) {
            return;
        }
        this.lastMoveLogTick = now;
        final boolean xoOk = Math.abs(this.xo - from.x) < 1.0E-9D
                && Math.abs(this.yo - from.y) < 1.0E-9D
                && Math.abs(this.zo - from.z) < 1.0E-9D;
        ShanhaiMod.LOGGER.info("{} server_move dx={} dy={} dz={} speed={} pos=({},{},{}) "
                        + "xo_ok={} xo=({},{},{}) teleports_seen={} lerps_seen={}",
                ShanhaiDropletPilotNetwork.PREFIX,
                oneDecimal(step.x), oneDecimal(step.y), oneDecimal(step.z),
                ShanhaiDropletPilotCore.speedText(this.pilotSpeed),
                oneDecimal(this.getX()), oneDecimal(this.getY()), oneDecimal(this.getZ()),
                Boolean.valueOf(xoOk),
                oneDecimal(this.xo), oneDecimal(this.yo), oneDecimal(this.zo),
                Integer.valueOf(this.clientTeleportCount), Integer.valueOf(this.clientLerpCount));
    }

    /** 一位小数的字符串（日志用，避免 %f 的六位小数刷屏）。 */
    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    /**
     * 自动驾驶的"冲向目标"那一步 = <b>自动攻击逻辑</b>（同一份 {@link #stepToward}、同一个 {@link #tryHit}、
     * 同一套爆炸扫地形），只把目标从"分配结果"换成调用方给的 {@code target}。
     */
    private void autoAttackStep(LivingEntity target, boolean explosion, double speedPerTick) {
        final Vec3 from = this.position();
        final Vec3 goal = target.getBoundingBox().getCenter();
        final Vec3 step = this.stepToward(goal, speedPerTick, false);
        if (step.lengthSqr() > FACE_MIN_STEP_SQR) {
            this.faceTowards(step);
        }
        final Vec3 to = from.add(step);
        if (explosion) {
            final Vec3 terrainHit = ShanhaiDropletExplosion.firstTerrainAlong(this.level(), from, to);
            if (terrainHit != null) {
                ShanhaiDropletExplosion.detonate(this.level(), this, terrainHit);
            }
        }
        this.tryHit(target, from, to);
        this.setPos(to.x, to.y, to.z);
        this.setDeltaMovement(step);
        if (step.lengthSqr() > FACE_MIN_STEP_SQR) {
            this.lastStep = step.normalize();
        }
    }

    /** 进入水滴：速度归零（规格「一开始为静止」）、同步驾驶态、打一条可判日志。 */
    private void onRiderEntered(ServerPlayer rider) {
        this.pilotSpeed = ShanhaiDropletPilotCore.MIN_SPEED_PER_TICK;
        this.pilotAssistLocked = false;
        this.lastLoggedAssistLocked = false;
        this.setDeltaMovement(Vec3.ZERO);
        final boolean autopilot = ShanhaiDropletSettings.autopilot(rider);
        final boolean assist = ShanhaiDropletSettings.assist(rider);
        final double angle = ShanhaiDropletSettings.assistAngle(rider);
        this.pushPilotSyncedState(autopilot, assist, angle);
        this.pushSyncedState(ShanhaiDropletSettings.mode(rider), ShanhaiDropletSettings.explosion(rider));
        ShanhaiMod.LOGGER.info("{} pilot_enter owner={} droplet={} speed=0 autopilot={} assist={} angle={}",
                ShanhaiDropletPilotNetwork.PREFIX, rider.getUUID(), this.getUUID(),
                Boolean.valueOf(autopilot), Boolean.valueOf(assist), Double.valueOf(angle));
    }

    /** 离开水滴：速度归零、清意图槽、同步驾驶态（落点由 {@link #removePassenger} 已处理）。 */
    private void onRiderLeft(ServerPlayer rider, String reason, Vec3 safe) {
        this.pilotSpeed = ShanhaiDropletPilotCore.MIN_SPEED_PER_TICK;
        this.pilotAssistLocked = false;
        this.lastLoggedAssistLocked = false;
        this.setDeltaMovement(Vec3.ZERO);
        this.entityData.set(DATA_PILOTED, Boolean.FALSE);
        final boolean hadIntent = ShanhaiDropletPilotNetwork.forget(rider.getUUID());
        ShanhaiMod.LOGGER.info("{} pilot_exit owner={} droplet={} reason={} safe=({},{},{}) speed=0 had_intent={}",
                ShanhaiDropletPilotNetwork.PREFIX, rider.getUUID(), this.getUUID(), reason,
                Double.valueOf(safe.x), Double.valueOf(safe.y), Double.valueOf(safe.z),
                Boolean.valueOf(hadIntent));
    }

    /** 请求下车（落点统一由 {@link #removePassenger} 处理，不在这里另算一份）。 */
    private void exitPilot(ServerPlayer rider, String reason) {
        ShanhaiMod.LOGGER.info("{} pilot_exit_request rider={} droplet={} reason={}",
                ShanhaiDropletPilotNetwork.PREFIX, rider.getUUID(), this.getUUID(), reason);
        rider.stopRiding();
    }

    /** 把驾驶态推进同步字段（客户端据此画绿框/准星圈）。 */
    private void pushPilotSyncedState(boolean autopilot, boolean assist, double assistAngle) {
        this.entityData.set(DATA_PILOTED, Boolean.TRUE);
        this.entityData.set(DATA_AUTOPILOT, Boolean.valueOf(autopilot));
        this.entityData.set(DATA_ASSIST, Boolean.valueOf(assist));
        this.entityData.set(DATA_ASSIST_ANGLE,
                Integer.valueOf(angleTenths(ShanhaiDropletSettings.clampAssistAngle(assistAngle))));
    }

    /** 度数 → 十分之一度（同步字段的整数编码；夹取已保证只有 1 位小数，所以是无损的）。 */
    public static int angleTenths(double angleDeg) {
        return (int) Math.round(angleDeg * 10.0D);
    }

    /** 十分之一度 → 度数（客户端读同步字段后换算）。 */
    public static double angleFromTenths(int tenths) {
        return (double) tenths / 10.0D;
    }

    // ------------------------------------------------------------------ 驾驶态读取（客户端 PilotView）

    /** 驾驶：自动驾驶是否开启（同步值）。 */
    @Override
    public boolean pilotAutopilot() {
        return this.entityData.get(DATA_AUTOPILOT).booleanValue();
    }

    /** 驾驶：瞄准辅助是否开启（同步值）。 */
    @Override
    public boolean pilotAssist() {
        return this.entityData.get(DATA_ASSIST).booleanValue();
    }

    /** 驾驶：瞄准辅助判定角度（同步值，度；同步字段按十分之一度存整数）。 */
    @Override
    public double pilotAssistAngleDeg() {
        return angleFromTenths(this.entityData.get(DATA_ASSIST_ANGLE).intValue());
    }

    /** 驾驶：当前是否有玩家在里面（同步值）。 */
    public boolean piloted() {
        return this.entityData.get(DATA_PILOTED).booleanValue();
    }

    /** 驾驶：当前手动速度（格/tick，服务端字段；客户端读不到，仅供服务端命令/日志）。 */
    public double pilotSpeedPerTick() {
        return this.pilotSpeed;
    }

    /** 驾驶：这一 tick 瞄准辅助是否锁定（服务端字段）。 */
    public boolean pilotAssistLocked() {
        return this.pilotAssistLocked;
    }

    /** 驾驶：机器可判的读数（{@code /shanhai droplet status} 用）。 */
    public String pilotStatsLine() {
        final String targetName = this.target == null ? "none"
                : ShanhaiDropletPilotCore.trimName(this.target.getDisplayName().getString(), 18);
        return "piloted=" + this.piloted()
                + " speed=" + ShanhaiDropletPilotCore.speedText(this.pilotSpeed)
                + " autopilot=" + this.pilotAutopilot()
                + " assist=" + this.pilotAssist()
                + " angle=" + ShanhaiDropletPilotCore.speedText(this.pilotAssistAngleDeg())
                + " locked=" + this.pilotAssistLocked
                + " target=" + targetName
                + " teleports=" + this.clientTeleportCount
                + " lerps=" + this.clientLerpCount
                + " dropped=" + ShanhaiDropletPilotNetwork.IntentStore.droppedCount();
    }

    // ------------------------------------------------------------------ 规格 9：跟随

    /**
     * 跟随点 = 拥有者<b>眼睛</b>位置 + left × (0.6·d) + up × (0.8·d)。
     *
     * <p>「左 / 上」都取<b>拥有者的本体视图坐标系</b>：
     * {@code left} 由 yaw 决定（水平，永远有定义；朝正上/正下也不会退化），
     * {@code up = look × left}（含 pitch）。两个偏移正交，且 {@code 0.6² + 0.8² = 1}
     * ⇒ 与眼睛的距离恒为 <b>d</b>（指令可设，默认 2.5；比例仍是 3:4 的"左上"）。
     *
     * @param owner    拥有者
     * @param distance 与眼睛的距离（格）
     */
    private Vec3 followPoint(ServerPlayer owner, double distance) {
        final double yawRad = owner.getYRot() * (double) Mth.DEG_TO_RAD;
        final Vec3 left = new Vec3(Math.cos(yawRad), 0.0D, Math.sin(yawRad));
        final Vec3 up = owner.getLookAngle().cross(left);
        return owner.getEyePosition()
                .add(left.scale(ShanhaiDropletSettings.followLeft(distance)))
                .add(up.scale(ShanhaiDropletSettings.followUp(distance)));
    }

    /** 规格 9：拥有者换维度 ⇒ 跟着换（{@link #FOLLOW_TELEPORTER} 只搬坐标，不找传送门）。 */
    private void followOwnerInto(ServerPlayer owner) {
        if (!(owner.level() instanceof ServerLevel destination)) {
            return;
        }
        final Entity moved = this.changeDimension(destination, FOLLOW_TELEPORTER);
        if (moved instanceof ShanhaiDropletEntity droplet && moved != this) {
            // 换维度对非玩家实体会造一个新实例：拥有者/目标/冷却/血量由 NBT 搬过去（restoreFrom → load），
            // 这里只把"目标实体引用"重新解析一次，并把位置钉回搬之前的位置（防止目标维度没有落点）。
            droplet.restoreTargetFromUuid();
            droplet.setPos(this.position().x, this.position().y, this.position().z);
        }
        ShanhaiMod.LOGGER.info("{} dimension_follow owner={} to={}",
                ShanhaiDropletCombat.PREFIX, owner.getUUID(), destination.dimension().location());
    }

    // ------------------------------------------------------------------ 规格 3：索敌（供给分配器用）

    /**
     * <b>仍然有效</b>的已锁定目标；没有 / 已失效 ⇒ {@code null}（调用方随后会重新分配）。
     *
     * <p>只做"清账 + 读档恢复"，<b>不选新目标</b> —— 选目标由 {@link ShanhaiDropletTargets}
     * 整层统一分配（否则每个水滴各选最近的那个，就会三颗水滴锁同一个目标）。
     */
    LivingEntity lockedTargetUsable(ServerPlayer owner, int mode) {
        if (mode == ShanhaiDropletSettings.MODE_NONE) {
            this.clearTarget();
            return null;
        }
        if (this.target == null && this.targetUuid != null) {
            this.restoreTargetFromUuid();
        }
        if (this.target != null && !this.isTargetUsable(owner, mode, this.target)) {
            this.clearTarget();
        }
        return this.target;
    }

    /** 分配结果落地（{@code null} = 这一 tick 不索敌）。 */
    void assignTarget(LivingEntity living) {
        this.target = living;
        this.targetUuid = living == null ? null : living.getUUID();
    }

    /**
     * 这一 tick 的全部候选，按<b>拥有者距离升序</b>排好。
     *
     * <p>分配器的判据是"各自领最近的、且没被别人领走的"⇒ 必须有序；排序规模是
     * 128 格内的实体数（一般个位数），不建缓存。
     */
    List<LivingEntity> candidatesByDistance(ServerPlayer owner, int mode) {
        final AABB area = owner.getBoundingBox().inflate(TARGET_RANGE);
        final List<LivingEntity> candidates = this.level().getEntitiesOfClass(
                LivingEntity.class, area, entity -> this.isCandidate(owner, mode, entity));
        candidates.sort((a, b) -> Double.compare(owner.distanceToSqr(a), owner.distanceToSqr(b)));
        return candidates;
    }

    /** 读档后按 UUID 把目标找回来（服务端 O(1) 查询）。 */
    private void restoreTargetFromUuid() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        final Entity entity = serverLevel.getEntity(this.targetUuid);
        if (entity instanceof LivingEntity living) {
            this.target = living;
        } else {
            this.targetUuid = null;
        }
    }

    /** 规格 3 / 5：目标是否仍然有效（唯一会让我们<b>放弃</b>已锁目标的几种情况）。 */
    private boolean isTargetUsable(ServerPlayer owner, int mode, LivingEntity candidate) {
        if (candidate.isRemoved() || !candidate.isAlive() || candidate.isDeadOrDying()) {
            return false;
        }
        if (candidate.level() != this.level()) {
            return false;
        }
        if (candidate == owner || candidate.getUUID().equals(this.ownerUuid)) {
            return false;
        }
        if (mode == ShanhaiDropletSettings.MODE_ENEMY && !(candidate instanceof Enemy)) {
            return false;
        }
        return owner.distanceTo(candidate) <= TARGET_RANGE;
    }

    /** 规格 3 的候选过滤（AABB 是方的 ⇒ 半径必须再用真实距离卡一次）。 */
    private boolean isCandidate(ServerPlayer owner, int mode, LivingEntity candidate) {
        if (candidate == owner || candidate.getUUID().equals(this.ownerUuid)) {
            return false;
        }
        if (candidate.isRemoved() || !candidate.isAlive() || candidate.isDeadOrDying()) {
            return false;
        }
        if (candidate.level() != this.level()) {
            return false;
        }
        if (mode == ShanhaiDropletSettings.MODE_ENEMY && !(candidate instanceof Enemy)) {
            return false;
        }
        return owner.distanceTo(candidate) <= TARGET_RANGE;
    }

    private void clearTarget() {
        this.target = null;
        this.targetUuid = null;
    }

    // ------------------------------------------------------------------ 规格 5 / 6：命中

    /**
     * 线段扫掠命中判定：这一 tick 的位移是 {@code from → to}（长度 = 速度/20，最多 10 格），
     * 用目标包围盒（{@link #HIT_PAD} 稍微膨胀）对这条线段做 {@link AABB#clip(Vec3, Vec3)}。
     *
     * <h4>为什么不能只看"终点在不在目标盒里"</h4>
     * 目标可能只有 0.5 格宽（蝙蝠/小鸡），而 {@code /shanhai droplet speed 200} 时每 tick 走
     * <b>10 格</b> —— 起点在盒子一侧、终点早已在另一侧，只看终点会整只跳过去。
     * 这里 {@code clip} 是原版的 slab 射线裁剪，返回<b>进入点</b>：
     * 只要线段与盒子相交就一定命中（不存在隧穿）。起点已经在盒子里时 {@code clip} 返回 empty，
     * 用 {@code contains(from)} 补一条"已经在里面 ⇒ 也算命中"。
     *
     * <p>🔴 自证：{@code temp/droplet-render/probe} 里那条"10 格/tick、目标 0.5 格宽、从另一侧穿过去"
     * 的探针就是这个判据的守卫；把扫掠改成"只判终点"它必须报红（负对照已跑过）。
     *
     * <p>冷却 {@link #HIT_COOLDOWN_TICKS} = 5 tick：命中判定照做（朝向/位置不受影响），
     * 但 5 tick 内不重复扣血；目标是 1024 点/次，冷却只是防"每 tick 反复打"。
     */
    private void tryHit(LivingEntity candidate, Vec3 from, Vec3 to) {
        final AABB box = candidate.getBoundingBox().inflate(HIT_PAD);
        final Optional<Vec3> contact = box.clip(from, to);
        if (!segmentHitsBox(box, from, to)) {
            return;   // 这一 tick 的线段没有碰到目标
        }
        final Vec3 at = contact.orElse(from);
        if (this.hitCooldown > 0) {
            return;   // 冷却中：判到了但不打
        }
        this.hitCooldown = HIT_COOLDOWN_TICKS;
        final boolean dealt = ShanhaiDropletCombat.applyDirectDamage(candidate, at);
        ShanhaiMod.LOGGER.debug("{} hit target={} at={} dealt={} next_hit_in={}t",
                ShanhaiDropletCombat.PREFIX, candidate.getUUID(), at, dealt, HIT_COOLDOWN_TICKS);
        // 规格 5：目标已经死/消失 ⇒ 立刻释放（下一 tick 重新索敌或回跟随点）；否则保持锁定继续掉头再打
        if (!candidate.isAlive() || candidate.isRemoved() || candidate.isDeadOrDying()) {
            this.clearTarget();
        }
    }

    /**
     * <b>纯逻辑</b>：这一 tick 的线段 {@code from→to} 有没有碰到这个盒（线段扫掠，不是判终点）。
     *
     * <p>实现落在 {@link ShanhaiDropletSweep}（零实体依赖，离线探针直接跑它）。
     */
    public static boolean segmentHitsBox(AABB box, Vec3 from, Vec3 to) {
        return ShanhaiDropletSweep.segmentHitsBox(box, from, to);
    }

    // ------------------------------------------------------------------ 规格 4：朝向

    /**
     * 用运动方向写 {@code yaw/pitch}（{@code forward = (-sinYaw·cosPitch, -sinPitch, cosYaw·cosPitch)} 的反解）。
     *
     * <p>yaw 走"相对上一 tick 最近的一边"：否则在 ±180° 边界上，渲染插值会倒着转一整圈。
     */
    private void faceTowards(Vec3 dir) {
        final Vec3 unit = dir.normalize();
        final float rawYaw = (float) (Mth.atan2(-unit.x, unit.z) * (180.0D / Math.PI));
        final float rawPitch = (float) (-Math.asin(Mth.clamp(unit.y, -1.0D, 1.0D)) * (180.0D / Math.PI));
        final float previousYaw = this.getYRot();
        this.setYRot(previousYaw + Mth.wrapDegrees(rawYaw - previousYaw));
        this.setXRot(Mth.clamp(rawPitch, -90.0F, 90.0F));
    }

    // ------------------------------------------------------------------ 规格 12：同步字段

    /** 把 HUD 需要的 5 个字段推到 {@link SynchedEntityData}（只在值变化时置脏由原版负责）。 */
    private void pushSyncedState(int mode, boolean explosion) {
        final boolean hasTarget = this.target != null && !this.target.isRemoved() && this.target.isAlive();
        this.entityData.set(DATA_MODE, Integer.valueOf(mode));
        this.entityData.set(DATA_EXPLOSION, Boolean.valueOf(explosion));
        this.entityData.set(DATA_HAS_TARGET, Boolean.valueOf(hasTarget));
        this.entityData.set(DATA_FOLLOWING, Boolean.valueOf(!hasTarget));
        final String name = hasTarget ? this.target.getDisplayName().getString() : "";
        if (!name.equals(this.lastSyncedTargetName)) {
            this.lastSyncedTargetName = name;
            this.entityData.set(DATA_TARGET_NAME, name);
        }
    }

    /** 规格 12：有目标。 */
    public boolean hasTarget() {
        return this.entityData.get(DATA_HAS_TARGET).booleanValue();
    }

    /** 规格 12：目标显示名（无目标 = 空串）。 */
    public String targetName() {
        return this.entityData.get(DATA_TARGET_NAME);
    }

    /** 规格 12：攻击模式 1/2/3。 */
    public int attackMode() {
        return this.entityData.get(DATA_MODE).intValue();
    }

    /** 规格 12：爆炸开关。 */
    public boolean explosionEnabled() {
        return this.entityData.get(DATA_EXPLOSION).booleanValue();
    }

    /** 规格 12：是否跟随（= 没有目标）。 */
    public boolean following() {
        return this.entityData.get(DATA_FOLLOWING).booleanValue();
    }

    // ------------------------------------------------------------------ 拥有者

    /** 拥有者 UUID（可能为 null：坏存档 / 直接 /summon 出来的水滴）。 */
    public UUID ownerUuid() {
        return this.ownerUuid;
    }

    /** 这个水滴是不是 {@code player} 名下的。 */
    public boolean isOwnedBy(Player player) {
        return this.ownerUuid != null && this.ownerUuid.equals(player.getUUID());
    }

    /** 解析拥有者（跨维度：从服务器玩家表按 UUID 取，不要求同维度）。分配器也要用 ⇒ 包内可见。 */
    ServerPlayer resolveOwner() {
        if (this.ownerUuid == null) {
            return null;
        }
        final var server = this.getServer();
        return server == null ? null : server.getPlayerList().getPlayer(this.ownerUuid);
    }

    /**
     * 规格 10：清掉 {@code owner} 名下<b>所有</b>水滴（所有维度），返回清掉的个数。
     *
     * <p>先收集再 {@code discard()}：{@code getAllEntities()} 的迭代器与"边遍历边移除"不兼容。
     */
    public static int discardOwnedBy(ServerPlayer owner) {
        final List<ShanhaiDropletEntity> found = collectOwnedBy(owner);
        for (final ShanhaiDropletEntity droplet : found) {
            droplet.discard();
        }
        return found.size();
    }

    /** 规格 10 / 命令 status：{@code owner} 名下<b>所有维度</b>里的水滴（只读，不收拾）。 */
    public static List<ShanhaiDropletEntity> collectOwnedBy(ServerPlayer owner) {
        final List<ShanhaiDropletEntity> found = new ArrayList<>();
        final var server = owner.getServer();
        if (server == null) {
            return found;
        }
        for (final ServerLevel level : server.getAllLevels()) {
            for (final Entity entity : level.getAllEntities()) {
                if (entity instanceof ShanhaiDropletEntity droplet && droplet.isOwnedBy(owner)) {
                    found.add(droplet);
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ 存读档

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        if (this.ownerUuid != null) {
            tag.putUUID(TAG_OWNER, this.ownerUuid);
        }
        if (this.targetUuid != null) {
            tag.putUUID(TAG_TARGET, this.targetUuid);
        }
        tag.putInt(TAG_HIT_COOLDOWN, this.hitCooldown);
        tag.putFloat(TAG_HEALTH, this.health);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        this.ownerUuid = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
        this.targetUuid = tag.hasUUID(TAG_TARGET) ? tag.getUUID(TAG_TARGET) : null;
        this.hitCooldown = Math.max(0, tag.getInt(TAG_HIT_COOLDOWN));
        if (tag.contains(TAG_HEALTH)) {
            this.setHealth(tag.getFloat(TAG_HEALTH));
        }
    }
}
