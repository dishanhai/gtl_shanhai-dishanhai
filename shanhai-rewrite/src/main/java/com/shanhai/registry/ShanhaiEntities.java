package com.shanhai.registry;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.crash.ShanhaiCrashBoltEntity;
import com.shanhai.common.droplet.ShanhaiDropletEntity;
import com.tterrag.registrate.util.entry.EntityEntry;
import net.minecraft.world.entity.MobCategory;

/**
 * 本工程<b>唯一</b>的实体注册点（2026-11-16 新增，阶段 3；2026-11-22 起共 <b>2</b> 个 {@code EntityType}）。
 *
 * <h2>一、实体注册点（2 个 {@code EntityType}：{@code crash_bolt} / {@code strong_interaction_droplet}）</h2>
 * 前者的 16 件异常投掷物共用 {@code shanhai:crash_bolt} 这一个实体类型，异常类型放在实体的同步字段里
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
     * 强相互作用水滴（{@code shanhai:strong_interaction_droplet}）。
     *
     * <h4>尺寸为什么是 1.5 × 1.5</h4>
     * 规格 13：本体 <b>3（长）× 1.5（宽）× 1.5（高）</b>。原版 {@code EntityType.Builder#sized} 只能写
     * "宽 × 高"（水平是正方形），<b>写不出"朝某个方向长 3"</b>；长度方向由实体自己覆写的
     * {@code makeBoundingBox()} 负责（按 yaw/pitch 把 3×1.5×1.5 投影成轴对齐盒，见
     * {@code ShanhaiDropletEntity#makeBoundingBox}）。这里给的 1.5×1.5 是"横截面"，
     * 同时也是 {@code getBbWidth()/getBbHeight()}（剔除、拾取半径等原版逻辑）的读数。
     *
     * <h4>tracking / updateInterval 为什么是 12 / 1</h4>
     * 规格 4 恒定 1 格/tick = 20 格/秒 ⇒ 必须每 tick 同步位置，否则客户端看到的是"一跳一跳"；
     * 12 区块 = 192 格，大于索敌半径 128 格（玩家在 128 格外也还能看见它在飞）。
     */
    public static final EntityEntry<ShanhaiDropletEntity> DROPLET =
            ShanhaiRegistration.REGISTRATE
                    .<ShanhaiDropletEntity>entity("strong_interaction_droplet", ShanhaiDropletEntity::new, MobCategory.MISC)
                    .properties(properties -> properties
                            .sized(1.5F, 1.5F)
                            .clientTrackingRange(12)
                            .updateInterval(1))
                    // 🔴 渲染器同样<b>不在这里</b>注册（本类是 common）：见 ShanhaiEntityRenderers 里那一行。
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
        ShanhaiMod.LOGGER.info("[SHANHAI-DROPLET] entity_type registered id={} category={} size={}x{} tracking={}ch interval={}t renderer=client:droplet_fx",
                ShanhaiRegistration.REGISTRATE.getModid() + ":strong_interaction_droplet", MobCategory.MISC,
                ShanhaiDropletEntity.BODY_GIRTH, ShanhaiDropletEntity.BODY_LENGTH, 12, 1);
    }

    private ShanhaiEntities() {}
}
