package com.shanhai.common.crash;

import com.shanhai.registry.ShanhaiEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 异常投掷物的<b>实体</b>：{@code extends AbstractArrow implements ItemSupplier}。
 * 16 件物品共用这<b>一个</b>实体类 + <b>一个</b> {@code EntityType}（异常类型放同步字段 {@link #DATA_INDEX}）。
 *
 * <h2>一、为什么 extends AbstractArrow</h2>
 * 原版三叉戟 {@code ThrownTrident} 的构造是 {@code (Level, LivingEntity, ItemStack)}，
 * 且 {@code getPickupItem()} 写死返回 {@code Items.TRIDENT} —— 没有可注入"异常类型"的口子，
 * 拿它当载体就得为 16 件各写一个子类。而基类 {@code AbstractArrow} 已经把我们需要的都给了：
 * <ul>
 *   <li>三个构造器（{@code (EntityType,Level)} / {@code (+x,y,z,Level)} / {@code (EntityType,LivingEntity,Level)}）；</li>
 *   <li>{@code protected boolean inGround} / {@code protected int inGroundTime} / {@code public Pickup pickup}；</li>
 *   <li>{@code protected void onHitBlock(BlockHitResult)} —— 落地那一刻的钩子；</li>
 *   <li>{@code protected abstract ItemStack getPickupItem()} —— 我们要实现的那一个；</li>
 *   <li>存读档已经在读写 {@code inGround}/{@code pickup}（我们只补自己的两个字段）。</li>
 * </ul>
 *
 * <h2>二、渲染：零代码</h2>
 * {@code ItemSupplier} 只有一个方法 {@code ItemStack getItem()}。原版的
 * {@code ThrownItemRenderer<T extends Entity & ItemSupplier>} 会拿这个 stack 平躺着画成 GROUND 物品
 * ⇒ 我们的实体在客户端显示的就是「那一件异常物品的 64×64 图标」，<b>没有一个字的自写渲染</b>
 * （注册那一行见 {@code ShanhaiEntities}：{@code .renderer(() -> ctx -> new ThrownItemRenderer<>(ctx))}）。
 *
 * <h2>三、落地即锁（三条锁）</h2>
 * <ol>
 *   <li><b>捡不走</b>：落地那刻 {@code pickup = DISALLOWED}
 *       ⇒ {@code AbstractArrow#tryPickup} 直接返回 false，{@code playerTouch} 收不走它；</li>
 *   <li><b>掉不下去</b>：{@code AbstractArrow#shouldFall()} 是 <b>private</b>（改不了），
 *       所以我们在 {@link #tick()} 前后各把 {@code inGround} 钉回 {@code true}
 *       —— 原版那个"支撑方块没了就 {@code startFalling()}"的分支因此永远进不去飞行物理分支，
 *       投掷物会停在落点；</li>
 *   <li><b>计时与实体解耦</b>：真正的 3 秒倒计时在 {@code ShanhaiCrashBoltScheduler} 的静态表里
 *       （键 = UUID），实体被移除、被卸载、被 /kill 都不会取消。</li>
 * </ol>
 *
 * <h2>四、读档（第二道闸在这里）</h2>
 * {@link #firstTickOnServer()} 在服务端第一次 tick 时做两件事：
 * 查 {@code ShanhaiCrashBoltSavedData} —— 若这一发<b>已经崩过</b>就立刻 {@code discard()}
 * （防止"进不去的存档"）；否则若它读档回来时已经是插地状态，就把倒计时<b>重新挂上</b>
 * （落地即锁的语义要跨存档成立）。
 */
public class ShanhaiCrashBoltEntity extends AbstractArrow implements ItemSupplier {

    /** NBT 键：异常序号（同步字段不随存档走，所以自己存一份）。 */
    public static final String TAG_INDEX = "ShanhaiBoltIndex";

    /** NBT 键：是否已经插过地（用于读档后重新挂倒计时）。 */
    public static final String TAG_LANDED = "ShanhaiBoltLanded";

    /** 同步字段：异常序号（0..15）。客户端靠它决定 {@link #getItem()} 返回哪件物品的图标。 */
    public static final EntityDataAccessor<Integer> DATA_INDEX =
            SynchedEntityData.defineId(ShanhaiCrashBoltEntity.class, EntityDataSerializers.INT);

    /** 是否已经落过地（自己存，因为原版 {@code inGroundTime} 会在"脱出"时归零）。 */
    private boolean landed;

    /** 服务端第一次 tick 的那一次检查做过没有（不存盘：每个会话都要重新判一次）。 */
    private boolean worldChecked;

    // ------------------------------------------------------------------ 构造

    /** 注册用的构造器（{@code EntityType.EntityFactory}）。 */
    public ShanhaiCrashBoltEntity(EntityType<? extends ShanhaiCrashBoltEntity> type, Level level) {
        super(type, level);
        // 它是"崩游戏的"，不是"打人的"：基础伤害 0，别让它在崩之前顺手打死村民。
        this.setBaseDamage(0.0D);
    }

    /** 投出时用的构造器（照 {@code ThrownTrident(Level, LivingEntity, ItemStack)} 的形状）。 */
    public ShanhaiCrashBoltEntity(Level level, LivingEntity owner, int index) {
        super(ShanhaiEntities.CRASH_BOLT.get(), owner, level);
        this.setBoltIndex(index);
        this.setBaseDamage(0.0D);
        // 第三个构造器会因为 owner 是玩家而把 pickup 设成 ALLOWED；这里就开始按住它。
        this.pickup = Pickup.DISALLOWED;
    }

    // ------------------------------------------------------------------ 同步字段

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_INDEX, Integer.valueOf(0));
    }

    /** 异常序号（越界值一律夹到合法区间，坏存档不会把渲染/崩端带崩）。 */
    public int boltIndex() {
        Integer raw = this.entityData.get(DATA_INDEX);
        int value = raw == null ? 0 : raw.intValue();
        if (value < 0) {
            return 0;
        }
        return Math.min(value, ShanhaiCrashBoltTable.COUNT - 1);
    }

    /** 设置异常序号（夹到合法区间）。 */
    public void setBoltIndex(int index) {
        this.entityData.set(DATA_INDEX, Integer.valueOf(Math.max(0, Math.min(index, ShanhaiCrashBoltTable.COUNT - 1))));
    }

    // ------------------------------------------------------------------ 渲染用的物品

    /** {@link ItemSupplier}：原版 {@code ThrownItemRenderer} 就画这个 stack 的图标。 */
    @Override
    public ItemStack getItem() {
        return ShanhaiCrashBoltTable.stack(this.boltIndex());
    }

    /** {@code AbstractArrow} 要的那一个：如果没有被改成 DISALLOWED，捡起来会得到这件物品。 */
    @Override
    protected ItemStack getPickupItem() {
        return ShanhaiCrashBoltTable.stack(this.boltIndex());
    }

    // ------------------------------------------------------------------ 落地

    /**
     * 落地那一刻：{@code super} 之后挂上级锁 + 倒计时。
     *
     * <p>⚠️ {@code onHitBlock} 客户端也会跑（客户端实体同样做 {@code level.clip}），
     * 所以真正的挂表只在 {@code !isClientSide} 那一支里做；
     * 两边的 {@code landed} 标记则都置上（客户端也要把"捡不走/掉不下去"表现成一致的）。
     */
    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        this.land(hit.getBlockPos());
    }

    private void land(BlockPos pos) {
        if (this.landed) {
            // 已经落过：绝不重置计时（挖/放方块可以骗出第二次 onHitBlock）
            this.pickup = Pickup.DISALLOWED;
            return;
        }
        this.landed = true;
        this.pickup = Pickup.DISALLOWED;                         // ① 捡不走
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoGravity(true);
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            // ③ 计时挂到 world tick 上（与实体解耦）
            ShanhaiCrashBoltScheduler.arm(serverLevel, this.getUUID(), pos, this.boltIndex());
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (!this.level().isClientSide && this.firstTickOnServer()) {
            return;   // 已被判定"这一发已经崩过"并移除
        }
        if (this.landed) {
            // ② 掉不下去：把 inGround 钉住，让 AbstractArrow#tick 永远走"插地"分支
            this.inGround = true;
        }
        super.tick();
        if (this.landed) {
            this.inGround = true;
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * 服务端第一次 tick 的检查（读档兜底）。
     *
     * @return {@code true} = 这一发已经崩过，实体已移除，本次 tick 不用再跑
     */
    private boolean firstTickOnServer() {
        if (this.worldChecked) {
            return false;
        }
        this.worldChecked = true;
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // 闸②：这一发崩过了？（崩之前的 discard 通常已经让它不在存档里；
        // 但如果它在某个更早的自动保存点被写进过 region 文件，就会走到这里）
        if (ShanhaiCrashBoltSavedData.isFired(serverLevel, this.getUUID())) {
            this.logAlreadyFired();
            this.discard();
            return true;
        }
        if (this.landed) {
            // 落地即锁跨存档：读档回来仍然插着地 ⇒ 重新挂上倒计时（幂等，已挂过不会重置）
            ShanhaiCrashBoltScheduler.arm(serverLevel, this.getUUID(), this.blockPosition(), this.boltIndex());
        }
        return false;
    }

    // ------------------------------------------------------------------ 存读档

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_INDEX, this.boltIndex());
        tag.putBoolean(TAG_LANDED, this.landed);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setBoltIndex(tag.getInt(TAG_INDEX));
        this.landed = tag.getBoolean(TAG_LANDED);
        if (this.landed) {
            // 读档回来就是插地状态：立刻把锁扣上（不等下一次 onHitBlock）
            this.pickup = Pickup.DISALLOWED;
            this.setNoGravity(true);
        }
    }

    // ------------------------------------------------------------------ 其它

    /** 落地后不再有任何"被推动"的可能（活塞/水流也不行）。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    /** 读档兜底那一支的日志。 */
    private void logAlreadyFired() {
        com.shanhai.ShanhaiMod.LOGGER.warn(
                "{} loaded_but_already_fired uuid={} index={} item={} pos={} ⇒ 直接移除（不再计时、不再崩）",
                ShanhaiCrashBoltScheduler.PREFIX, this.getUUID(), this.boltIndex(),
                ShanhaiCrashBoltTable.fullId(this.boltIndex()), this.blockPosition());
    }
}
