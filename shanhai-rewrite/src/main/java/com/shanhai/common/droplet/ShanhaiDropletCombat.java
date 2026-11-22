package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 水滴的<b>命中伤害</b>：直接写目标的同步血量字段（规格 6），<b>不走 {@code hurt()} </b>。
 *
 * <h2>一、为什么是反射</h2>
 * 1.20.1 里 {@code LivingEntity.DATA_HEALTH_ID} 的声明是
 * <pre>
 *   private static final EntityDataAccessor&lt;Float&gt; DATA_HEALTH_ID;
 * </pre>
 * （{@code javap -p} 实测：<b>private</b>；源码 {@code LivingEntity.java} 第 1041/1050 行就是
 * {@code getHealth()/setHealth()} 直接读写这一个字段）⇒ 其它类<b>没有</b>合法的直接引用写法。
 * 本工程既有的 7 个 {@code DATA_HEALTH_ID} 使用点（{@code common/guard/ShanhaiGuardValueLayer} 等）
 * 用的就是同一套做法：<b>反射取 accessor，开发名 {@code DATA_HEALTH_ID} 与生产 SRG 名 {@code f_20961_} 都试</b>，
 * 取到一次就缓存。本类照抄这个口径，但<b>不依赖</b>守卫层（水滴是独立功能，守卫那套的可用性不该决定水滴能不能打死人）。
 *
 * <h2>二、写进去的是什么</h2>
 * {@code 当前值 - 1024}，<b>原样</b>写（不 clamp 到 0）：规格要的就是数据层真值，
 * 因此可能出现负血量；随后由 {@link #handOverToVanillaDeath} 交回原版死亡流程。
 *
 * <h2>三、为什么还要调一次 {@code die()}</h2>
 * 直写字段只会让 {@code isDeadOrDying()} 变真 —— 原版 {@code LivingEntity.baseTick()} 里那条
 * {@code if (isDeadOrDying() && level.shouldTickDeath(this)) tickDeath();} 只做"死亡计时 → 20 tick 后 remove"，
 * <b>不滚掉落、不发死亡音效、不发死亡消息</b>（源码 {@code LivingEntity.java} 第 410/546 行）。
 * {@code die(DamageSource)} 才是原版自然死亡的那一段（{@code dropAllDeathLoot} + {@code GameEvent.ENTITY_DIE}
 * + 死亡姿势，源码第 1303 行）。所以命中后若血量 ≤ 0，这里补一次 {@code die()}；
 * <b>仍然没有走 {@code hurt()}</b>，也没有任何免死/拦截。
 */
public final class ShanhaiDropletCombat {

    /** 本功能日志前缀（与实体、命令同一串，方便 grep 一整个功能）。 */
    public static final String PREFIX = "[SHANHAI-DROPLET]";

    /** 规格 6：每次命中扣的血量（直接写数据层，不走 hurt）。 */
    public static final float DIRECT_DAMAGE = 1024.0F;

    /** 开发环境（parchment/official）里那个字段名。 */
    public static final String HEALTH_FIELD_DEV = "DATA_HEALTH_ID";

    /** 生产（SRG/reobf）里同一个字段的名字。 */
    public static final String HEALTH_FIELD_SRG = "f_20961_";

    /** 反射拿到的 {@code LivingEntity.DATA_HEALTH_ID}（取到一次就缓存；null = 还没取到/取不到）。 */
    private static volatile EntityDataAccessor<Float> healthAccessor;

    /** 取到它的字段名（日志/自证用）。 */
    private static volatile String healthAccessorVia = "(未探测)";

    /** 只探测一次（失败也不反复刷日志）。 */
    private static final AtomicBoolean PROBED = new AtomicBoolean(false);

    /**
     * 对目标<b>直写</b>血量：{@code getEntityData().set(DATA_HEALTH_ID, 当前值 - 1024, true)}。
     *
     * @param target 命中目标
     * @param at     命中点（只用于日志；不参与计算）
     * @return true = 写进去了；false = accessor 拿不到 / 写入抛异常（<b>不假装成功</b>）
     */
    public static boolean applyDirectDamage(LivingEntity target, Vec3 at) {
        if (target == null) {
            return false;
        }
        // 🔴 驾驶免伤第 3 道闸：目标正骑在水滴里 ⇒ 这次直写不生效。
        //    理由：本方法是<b>直接写 {@code DATA_HEALTH_ID}</b>，整条路不经过 {@code hurt()}、
        //    也不 post 任何 LivingXXXEvent —— 光挂 LivingHurtEvent 拦不住"别人名下的水滴打骑乘者"。
        if (target.isPassenger() && target.getVehicle() instanceof ShanhaiDropletEntity) {
            ShanhaiMod.LOGGER.debug("{} direct_damage_skipped reason=target_is_piloting target={} at={}",
                    PREFIX, target.getUUID(), at);
            return false;
        }
        final EntityDataAccessor<Float> accessor = healthAccessor();
        if (accessor == null) {
            return false;
        }
        try {
            final Float raw = target.getEntityData().get(accessor);
            final float before = raw == null ? 0.0F : raw.floatValue();
            final float after = before - DIRECT_DAMAGE;
            // force = true：即使目标值恰好等于当前值也把 dirty 立起来重发一次（同守卫层第二通道的口径）
            target.getEntityData().set(accessor, Float.valueOf(after), true);
            ShanhaiMod.LOGGER.debug("{} hit target={} at={} hp {} -> {} (direct data write)",
                    PREFIX, target.getUUID(), at, before, after);
            if (after <= 0.0F) {
                handOverToVanillaDeath(target);
            }
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} direct health write failed target={} at={} err={}",
                    PREFIX, target.getUUID(), at, t.toString());
            return false;
        }
    }

    /**
     * 血量已经 ≤ 0 ⇒ 交回原版死亡流程（掉落 / 死亡事件 / 死亡姿势）。
     *
     * <p>幂等：{@code die()} 自己会挡 {@code isRemoved()} 与 {@code dead}（{@code LivingEntity.java} 第 1305 行）。
     */
    private static void handOverToVanillaDeath(LivingEntity target) {
        try {
            target.die(target.level().damageSources().generic());
        } catch (Throwable t) {
            // 死亡流程里的事件/掉落表可能由第三方 mod 抛异常：记下来，不让它打断水滴自己的 tick
            ShanhaiMod.LOGGER.warn("{} vanilla death hand-off failed target={} err={}",
                    PREFIX, target.getUUID(), t.toString());
        }
    }

    /**
     * 取 {@code LivingEntity.DATA_HEALTH_ID}（private static final ⇒ 反射；两个名字都试）。
     *
     * @return accessor；拿不到 ⇒ {@code null}（调用方必须当"打不死"处理，不许假装成功）
     */
    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> healthAccessor() {
        final EntityDataAccessor<Float> cached = healthAccessor;
        if (cached != null) {
            return cached;
        }
        if (!PROBED.compareAndSet(false, true)) {
            return healthAccessor;
        }
        for (final String name : new String[] { HEALTH_FIELD_DEV, HEALTH_FIELD_SRG }) {
            try {
                final Field field = LivingEntity.class.getDeclaredField(name);
                field.setAccessible(true);
                final Object value = field.get(null);
                if (value instanceof EntityDataAccessor<?> accessor) {
                    healthAccessor = (EntityDataAccessor<Float>) accessor;
                    healthAccessorVia = name;
                    ShanhaiMod.LOGGER.info("{} health_field ready field={} (direct data-layer write, no hurt())",
                            PREFIX, name);
                    return healthAccessor;
                }
            } catch (Throwable ignored) {
                // 试下一个名字（开发名 / SRG 名）
            }
        }
        healthAccessorVia = "(不可用)";
        ShanhaiMod.LOGGER.warn("{} health_field UNAVAILABLE tried={} / {} ⇒ 命中将不产生伤害（不静默假装成功）",
                PREFIX, HEALTH_FIELD_DEV, HEALTH_FIELD_SRG);
        return null;
    }

    /**
     * 自证/诊断：accessor 取自哪个字段名（{@code "(不可用)"} = 没取到）。
     *
     * <p>第一次调用会<b>主动探测一次</b>（只读反射、幂等）—— `/shanhai droplet status` 用它把
     * "命中通道到底通不通"打进日志，这样"水滴打不死人"与"水滴根本没打到"在日志上可区分。
     */
    public static String healthAccessorVia() {
        healthAccessor();
        return healthAccessorVia;
    }

    private ShanhaiDropletCombat() {}
}
