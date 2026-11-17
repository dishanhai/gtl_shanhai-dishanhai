package com.shanhai.registry;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.crash.ShanhaiCrashBoltEntity;
import com.tterrag.registrate.util.entry.EntityEntry;
import net.minecraft.world.entity.MobCategory;

/**
 * 本工程<b>唯一</b>的实体注册点（2026-11-16 新增，阶段 3）。
 *
 * <h2>一、只注册 1 个 {@code EntityType}</h2>
 * 16 件异常投掷物共用 {@code shanhai:crash_bolt} 这一个实体类型，异常类型放在实体的同步字段里
 * （{@code ShanhaiCrashBoltEntity#DATA_INDEX}）。理由：
 * <ul>
 *   <li>渲染/物理/存读档的行为 16 件完全一样，注册 16 个类型只是把同一份东西抄 16 遍；</li>
 *   <li>注册表里多 15 个类型 = 多 15 个数字 id，对存档与网络同步都是纯负担；</li>
 *   <li>需求本来就写了「只注册 1 个 EntityType」。</li>
 * </ul>
 *
 * <h2>二、渲染：原版 {@code ThrownItemRenderer}，但注册点<b>不在本类</b></h2>
 * 渲染器本身仍用原版 {@code ThrownItemRenderer<T extends Entity & ItemSupplier>}（把 {@code ItemSupplier#getItem()}
 * 返回的 stack 平躺着画成 GROUND 物品 ⇒ 客户端看到的图标就是那一件异常物品的 64×64 贴图，连动画贴图都会动）。
 *
 * <p>🔴 但<b>注册点搬到了 {@code com.shanhai.client.event.ShanhaiEntityRenderers}</b>（{@code value = Dist.CLIENT}
 * 的订阅器）。原因不是风格问题，是 2026-11-16 冒烟实锤的事故：原写法把
 * {@code EntityBuilder#renderer(() -> ctx -> new ThrownItemRenderer<>(ctx))} 写在这里，
 * 而专用服务端在 {@code CONSTRUCT} 阶段校验这段 lambda 时就要解析 {@code ThrownItemRenderer}
 * 及其父类 {@code EntityRenderer} ⇒ {@code Attempted to load class … for invalid dist DEDICATED_SERVER}
 * ⇒ <b>专服直接起不来</b>。Registrate 内部那层 {@code DistExecutor} 只保护"监听器挂不挂"，
 * 保护不了"类字面量要不要被校验"。
 * ⇒ 结论：<b>本类（common）里不许出现任何 net.minecraft.client.* 的引用</b>。
 *
 * <h2>三、属性为什么是 0.5×0.5 / tracking 4 / interval 20</h2>
 * 逐项对齐原版箭 {@code EntityType.ARROW}：小物体、近距离同步即可
 * （投掷物只会出现在扔它的人附近，tracking range 拉大没有意义，反而费带宽）。
 */
public final class ShanhaiEntities {

    /** 异常投掷物（{@code shanhai:crash_bolt}）。 */
    public static final EntityEntry<ShanhaiCrashBoltEntity> CRASH_BOLT =
            ShanhaiRegistration.REGISTRATE
                    // ⚠️ 显式类型实参 <ShanhaiCrashBoltEntity> 不能省：EntityType.EntityFactory<T> 的
                    //    形参是 EntityType<T>，而本类构造器收 EntityType<? extends …>；
                    //    靠推断会退化成 T=Entity（javac 报"EntityType<Entity> 无法转换为 …"）。
                    .<ShanhaiCrashBoltEntity>entity("crash_bolt", ShanhaiCrashBoltEntity::new, MobCategory.MISC)
                    .properties(properties -> properties
                            .sized(0.5F, 0.5F)
                            .clientTrackingRange(4)
                            .updateInterval(20))
                    // 🔴 渲染器<b>不在这里</b>注册：本类是 common，引用 net.minecraft.client.* 会让专服起不来。
                    //    注册点 = com.shanhai.client.event.ShanhaiEntityRenderers（Dist.CLIENT 订阅器）。
                    .register();

    /**
     * 触达类（静态字段初始化即注册）。
     *
     * <p>为什么不写成 {@code @Mod.EventBusSubscriber} 或单独的 {@code RegistryEvent}：
     * 全工程的注册都走同一个 {@link ShanhaiRegistration#REGISTRATE}（类注释里写明"出现第二个实例，
     * 它注册的东西一个都不会进注册表，且不抛异常"），本类只负责<b>被触达</b>。
     */
    public static void init() {
        ShanhaiMod.LOGGER.info("[SHANHAI-CRASH-BOLT] entity_type registered id={} category={} renderer=vanilla:ThrownItemRenderer",
                ShanhaiRegistration.REGISTRATE.getModid() + ":crash_bolt", MobCategory.MISC);
    }

    private ShanhaiEntities() {}
}
