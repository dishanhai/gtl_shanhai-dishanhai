package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 水滴爆炸的<b>拥有者豁免</b>：让拥有者<b>完全不吃</b>这一次爆炸的伤害与击退，其它实体照旧。
 *
 * <h2>一、机制（就用原版公开事件，不动原版 Explosion）</h2>
 * {@code ServerLevel#explode} 是同步调用，它内部 {@code Explosion#explode()} 会：
 * <ol>
 *   <li>收集半径内实体成一张 {@code List<Entity>}；</li>
 *   <li>把<b>同一个列表实例</b>交给 {@code ForgeEventFactory.onExplosionDetonate} →
 *       {@link ExplosionEvent.Detonate}（字节码实证：{@code Detonate} 构造器 {@code putfield entityList}，
 *       {@code getAffectedEntities()} 直接 {@code getfield} 同一个字段，没有拷贝）；</li>
 *   <li>事件之后才遍历这张表做 {@code hurt} + {@code setDeltaMovement}（击退）。
 *       ⇒ 在事件里把拥有者从表里摘掉，<b>伤害与击退一起没有</b>（不是临时无敌那种手脚）。</li>
 * </ol>
 *
 * <h2>二、标记的生命周期</h2>
 * {@link ShanhaiDropletExplosion#detonate} 在调 {@code level.explode(...)} <b>之前</b>
 * {@link #begin(UUID)}（记下这次爆炸的拥有者 UUID），在 <b>finally</b> 里 {@link #end()}
 * ⇒ 正常返回、抛异常、事件被取消都不会把标记留到下一次爆炸。
 * 用 {@link ThreadLocal} 存（爆炸与事件在同一线程同步发生），并保存/恢复上一层标记 ⇒ 嵌套爆炸也正确。
 * 没有拥有者（{@code /summon} 出来的水滴、或 source 不是水滴）⇒ 标记为 {@code null}
 * ⇒ 本处理器<b>一个都不摘</b>，别的爆炸完全不受影响。
 *
 * <h2>三、明确不做的事</h2>
 * <ul>
 *   <li>不给玩家加临时无敌 / 不改 {@code HurtableEntity}；</li>
 *   <li>不改原版 {@code Explosion} 类；</li>
 *   <li>不碰 {@code ShanhaiGuard*}；</li>
 *   <li>不碰方块表（{@code getAffectedBlocks()}）：建造物的破坏仍由
 *       {@link ShanhaiDropletExplosion#detonate} 自己的半径扫描负责，与实体豁免无关。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletExplosionProtection {

    /** 日志前缀（与水滴其它部分同一串）。 */
    public static final String PREFIX = ShanhaiDropletCombat.PREFIX;

    /** 当前正在进行的这次爆炸的拥有者 UUID（null = 不豁免任何人）。 */
    private static final ThreadLocal<UUID> ACTIVE_OWNER = new ThreadLocal<>();

    /** 上一层标记（嵌套爆炸用；null 表示"上一层没有标记"）。 */
    private static final ThreadLocal<UUID> PREVIOUS_OWNER = new ThreadLocal<>();

    /** 第一次真的摘掉拥有者时打一条 INFO（之后降到 DEBUG，避免"每 tick 撞地形"时刷屏）。 */
    private static final AtomicBoolean LOGGED_FIRST_REMOVAL = new AtomicBoolean(false);

    private ShanhaiDropletExplosionProtection() {}

    /**
     * 进入一次"由水滴引发、需要豁免拥有者"的爆炸。
     *
     * @param ownerUuid 拥有者 UUID；{@code null} ⇒ 这次爆炸不豁免任何人
     */
    static void begin(UUID ownerUuid) {
        PREVIOUS_OWNER.set(ACTIVE_OWNER.get());
        ACTIVE_OWNER.set(ownerUuid);
    }

    /**
     * 退出这次爆炸（<b>放在 finally 里</b>）：恢复上一层标记；
     * 没有上一层就把两个 ThreadLocal 都删掉（线程池线程上不留垃圾）。
     */
    static void end() {
        final UUID previous = PREVIOUS_OWNER.get();
        PREVIOUS_OWNER.remove();
        if (previous == null) {
            ACTIVE_OWNER.remove();
        } else {
            ACTIVE_OWNER.set(previous);
        }
    }

    /** 自检/诊断：当前线程正在豁免的拥有者（没有 ⇒ null）。 */
    public static UUID activeOwnerUuid() {
        return ACTIVE_OWNER.get();
    }

    /**
     * {@link ExplosionEvent.Detonate}：把拥有者从"受这次爆炸影响的实体表"里摘掉。
     *
     * <p>用 {@link EventPriority#LOWEST}：这是"减法"，放在最后执行，
     * 保证别的监听器先做完它们的事、我们再摘（摘掉就是最终结果）。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        final UUID ownerUuid = ACTIVE_OWNER.get();
        if (ownerUuid == null) {
            return;   // 不是水滴引发的爆炸 / 水滴没有拥有者 ⇒ 一个都不摘
        }
        final List<Entity> affected = event.getAffectedEntities();
        final int removed = ShanhaiDropletExplosionOwnership.removeOwner(affected, Entity::getUUID, ownerUuid);
        if (removed <= 0) {
            return;
        }
        if (LOGGED_FIRST_REMOVAL.compareAndSet(false, true)) {
            ShanhaiMod.LOGGER.info("{} explosion_owner_exempt owner={} removed={} remaining_entities={} (first hit)",
                    PREFIX, ownerUuid, removed, affected.size());
        } else {
            ShanhaiMod.LOGGER.debug("{} explosion_owner_exempt owner={} removed={} remaining_entities={}",
                    PREFIX, ownerUuid, removed, affected.size());
        }
    }
}
